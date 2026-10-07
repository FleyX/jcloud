package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.hutool.core.util.RandomUtil;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.enums.CommonStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.NotificationLogMapper;
import com.fleyx.jcloud.mapper.ShareMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.SystemConfigMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.EmailShareLinkDto;
import com.fleyx.jcloud.model.dto.SmtpConfigDto;
import com.fleyx.jcloud.model.po.NotificationLog;
import com.fleyx.jcloud.model.po.Share;
import com.fleyx.jcloud.model.po.SystemConfig;
import com.fleyx.jcloud.service.EmailShareService;
import com.fleyx.jcloud.service.support.EmailShareRecipientSupport;
import com.fleyx.jcloud.service.support.EmailShareSender;
import com.fleyx.jcloud.service.support.SmtpConfigSupport;
import com.fleyx.jcloud.util.IdUtil;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 邮件分享（链接分享模式）集成测试（工单 07）。
 * <p>
 * 正向用例经 GreenMail 本地 SMTP 仿真验证真实发信回路；异步发送结果通过轮询落库结果断言。
 * 不使用事务回滚（异步线程看不到未提交数据），测试数据按唯一 ID/邮箱隔离并在用例结束后物理清理。
 */
class EmailShareServiceTest extends IntegrationTestBase {

    /**
     * 本地 SMTP 仿真服务，动态端口避免冲突。
     */
    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL =
            new GreenMailExtension(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));

    @Autowired
    private EmailShareService emailShareService;

    @Autowired
    private EmailShareRecipientSupport recipientSupport;

    @Autowired
    private SmtpConfigSupport smtpConfigSupport;

    @Autowired
    private ShareMapper shareMapper;

    @Autowired
    private NotificationLogMapper notificationLogMapper;

    @Autowired
    private SystemConfigMapper systemConfigMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    @Autowired
    private RedissonClient redissonClient;

    private final String tag = Long.toUnsignedString(System.nanoTime(), 36);

    private final List<String> createdUserIds = new ArrayList<>();

    private final List<String> createdSpaceIds = new ArrayList<>();

    private final List<String> createdShareIds = new ArrayList<>();

    private final List<String> usedRecipients = new ArrayList<>();

    @BeforeEach
    void clearSmtpConfig() {
        systemConfigMapper.delete(new LambdaQueryWrapper<SystemConfig>()
                .likeRight(SystemConfig::getConfigKey, "notification.smtp."));
    }

    @AfterEach
    void cleanup() {
        createdShareIds.forEach(shareMapper::deleteById);
        createdShareIds.clear();
        if (!usedRecipients.isEmpty()) {
            notificationLogMapper.delete(new LambdaQueryWrapper<NotificationLog>()
                    .in(NotificationLog::getRecipient, usedRecipients));
            usedRecipients.clear();
        }
        for (String userId : createdUserIds) {
            redissonClient.getBucket(EmailShareRecipientSupport.KEY_PREFIX + userId, StringCodec.INSTANCE).delete();
        }
        createdUserIds.forEach(userMapper::deleteById);
        createdSpaceIds.forEach(storageSpaceMapper::deleteById);
        createdUserIds.clear();
        createdSpaceIds.clear();
        systemConfigMapper.delete(new LambdaQueryWrapper<SystemConfig>()
                .likeRight(SystemConfig::getConfigKey, "notification.smtp."));
    }

    /**
     * 两个收件人：GreenMail 各收一封，HTML 含分享链接与访问密码，发送记录两条成功。
     */
    @Test
    void shouldSendLinkMailToAllRecipientsAndRecordSuccess() throws Exception {
        UserWithSpace owner = prepareUser();
        configureSmtp();
        Share share = insertShare(owner.user().getId());
        String first = "a-" + tag + "@example.com";
        String second = "b-" + tag + "@example.com";
        String shareUrl = "https://jcloud.example.com/s/" + share.getShareCode();

        emailShareService.sendLink(owner.user().getId(), owner.user().getUsername(),
                linkDto(share, shareUrl, "pass123", List.of(first, second)));

        assertTrue(GREEN_MAIL.waitForIncomingEmail(5000, 2), "未在超时内收到两封邮件");
        MimeMessage[] messages = GREEN_MAIL.getReceivedMessages();
        assertEquals(2, messages.length);
        List<String> actualRecipients = new ArrayList<>();
        for (MimeMessage message : messages) {
            actualRecipients.add(message.getAllRecipients()[0].toString());
            assertEquals(owner.user().getUsername() + " 与你分享了文件", message.getSubject());
            String body = String.valueOf(message.getContent());
            assertTrue(body.contains(shareUrl), "邮件正文应含分享链接");
            assertTrue(body.contains("pass123"), "邮件正文应含访问密码");
        }
        assertTrue(actualRecipients.contains(first));
        assertTrue(actualRecipients.contains(second));

        awaitNotificationLogCount(List.of(first, second), 2);
        awaitRecentRecipients(owner.user().getId(), List.of(first, second));
    }

    /**
     * 非分享所有者调用被拒。
     */
    @Test
    void shouldRejectWhenNotShareOwner() {
        UserWithSpace owner = prepareUser();
        UserWithSpace other = prepareUser();
        configureSmtp();
        Share share = insertShare(owner.user().getId());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> emailShareService.sendLink(other.user().getId(), other.user().getUsername(),
                        linkDto(share, "https://jcloud.example.com/s/" + share.getShareCode(), null,
                                List.of("a-" + tag + "@example.com"))));
        assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
    }

    /**
     * 收件人为空、格式非法、超过 20 个均被拒。
     */
    @Test
    void shouldRejectInvalidRecipients() {
        UserWithSpace owner = prepareUser();
        configureSmtp();
        Share share = insertShare(owner.user().getId());
        String url = "https://jcloud.example.com/s/" + share.getShareCode();

        BusinessException empty = assertThrows(BusinessException.class,
                () -> emailShareService.sendLink(owner.user().getId(), owner.user().getUsername(),
                        linkDto(share, url, null, List.of())));
        assertEquals(ResultCode.PARAM_ERROR, empty.getResultCode());

        BusinessException invalid = assertThrows(BusinessException.class,
                () -> emailShareService.sendLink(owner.user().getId(), owner.user().getUsername(),
                        linkDto(share, url, null, List.of("not-an-email"))));
        assertTrue(invalid.getMessage().contains("格式不正确"));

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add("u" + i + "-" + tag + "@example.com");
        }
        BusinessException overflow = assertThrows(BusinessException.class,
                () -> emailShareService.sendLink(owner.user().getId(), owner.user().getUsername(),
                        linkDto(share, url, null, tooMany)));
        assertTrue(overflow.getMessage().contains("最多"));
    }

    /**
     * 未配置发件邮箱时抛 BusinessException，提示通知功能不可用。
     */
    @Test
    void shouldRejectWhenSmtpNotConfigured() {
        UserWithSpace owner = prepareUser();
        Share share = insertShare(owner.user().getId());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> emailShareService.sendLink(owner.user().getId(), owner.user().getUsername(),
                        linkDto(share, "https://jcloud.example.com/s/" + share.getShareCode(), null,
                                List.of("a-" + tag + "@example.com"))));
        assertTrue(exception.getMessage().contains("未配置发件邮箱"));
    }

    /**
     * 收件人记忆：去重、最新在前、上限 5 个。
     */
    @Test
    void shouldKeepRecentRecipientsDedupedAndCapped() {
        UserWithSpace owner = prepareUser();
        String userId = owner.user().getId();
        String key = EmailShareRecipientSupport.KEY_PREFIX + userId;
        redissonClient.getBucket(key, StringCodec.INSTANCE).delete();

        recipientSupport.record(userId, List.of("a@example.com", "b@example.com"));
        assertEquals(List.of("a@example.com", "b@example.com"), recipientSupport.list(userId));

        recipientSupport.record(userId, List.of("b@example.com", "c@example.com"));
        assertEquals(List.of("b@example.com", "c@example.com", "a@example.com"), recipientSupport.list(userId));

        recipientSupport.record(userId, List.of("d@example.com", "e@example.com", "f@example.com", "g@example.com"));
        assertEquals(List.of("d@example.com", "e@example.com", "f@example.com", "g@example.com", "b@example.com"),
                recipientSupport.list(userId));
    }

    private UserWithSpace prepareUser() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        createdUserIds.add(userWithSpace.user().getId());
        createdSpaceIds.add(userWithSpace.space().getId());
        return userWithSpace;
    }

    private void configureSmtp() {
        SmtpConfigDto dto = new SmtpConfigDto();
        dto.setHost("127.0.0.1");
        dto.setPort(GREEN_MAIL.getSmtp().getPort());
        dto.setUsername("");
        dto.setEncryption(SmtpConfigSupport.ENCRYPTION_NONE);
        dto.setFromAddress("from@example.com");
        dto.setFromName("jcloud");
        smtpConfigSupport.save(dto);
    }

    private Share insertShare(String userId) {
        Share share = new Share();
        share.setId(IdUtil.nextId());
        share.setUserId(userId);
        share.setName("测试分享");
        share.setShareCode(RandomUtil.randomString("abcdefghjkmnpqrstuvwxyz23456789", 8));
        share.setViewCount(0L);
        share.setStatus(CommonStatus.ENABLED.getCode());
        share.setDeleteAt(0L);
        shareMapper.insert(share);
        createdShareIds.add(share.getId());
        return share;
    }

    private EmailShareLinkDto linkDto(Share share, String shareUrl, String password, List<String> recipients) {
        EmailShareLinkDto dto = new EmailShareLinkDto();
        dto.setShareCode(share.getShareCode());
        dto.setShareUrl(shareUrl);
        dto.setShareName(share.getName());
        dto.setPassword(password);
        dto.setRecipients(recipients);
        usedRecipients.addAll(recipients);
        return dto;
    }

    private void awaitNotificationLogCount(List<String> recipients, long expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (countSuccessLogs(recipients) < expected) {
            if (System.currentTimeMillis() > deadline) {
                fail("发送记录未在超时内落库: recipients=" + recipients);
            }
            sleepQuietly();
        }
    }

    private long countSuccessLogs(List<String> recipients) {
        return notificationLogMapper.selectCount(new LambdaQueryWrapper<NotificationLog>()
                .eq(NotificationLog::getEventType, EmailShareSender.EVENT_TYPE)
                .in(NotificationLog::getRecipient, recipients));
    }

    private void awaitRecentRecipients(String userId, List<String> expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!recipientSupport.list(userId).containsAll(expected)) {
            if (System.currentTimeMillis() > deadline) {
                fail("收件人记忆未在超时内更新: userId=" + userId);
            }
            sleepQuietly();
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }
}
