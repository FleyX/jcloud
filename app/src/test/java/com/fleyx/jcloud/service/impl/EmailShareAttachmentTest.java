package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.NotificationEventType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.NotificationLogMapper;
import com.fleyx.jcloud.mapper.NotificationMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.SystemConfigMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.EmailShareAttachmentDto;
import com.fleyx.jcloud.model.dto.SmtpConfigDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.Notification;
import com.fleyx.jcloud.model.po.NotificationLog;
import com.fleyx.jcloud.model.po.SystemConfig;
import com.fleyx.jcloud.service.EmailShareService;
import com.fleyx.jcloud.service.support.EmailShareRecipientSupport;
import com.fleyx.jcloud.service.support.EmailShareSender;
import com.fleyx.jcloud.service.support.NotificationLogSupport;
import com.fleyx.jcloud.service.support.SmtpConfigSupport;
import com.fleyx.jcloud.util.IdUtil;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 邮件分享（附件直发模式）集成测试（工单 08）。
 * <p>
 * 正向用例经 GreenMail 本地 SMTP 仿真验证真实附件发信回路；异步发送结果与失败补发的站内通知
 * 通过轮询落库结果断言。不使用事务回滚（异步线程看不到未提交数据），测试数据按唯一 ID/邮箱隔离
 * 并在用例结束后物理清理。
 */
class EmailShareAttachmentTest extends IntegrationTestBase {

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
    private FileMapper fileMapper;

    @Autowired
    private NotificationLogMapper notificationLogMapper;

    @Autowired
    private NotificationMapper notificationMapper;

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

    private final List<String> createdNodeIds = new ArrayList<>();

    private final List<String> usedRecipients = new ArrayList<>();

    @BeforeEach
    void clearSmtpConfig() {
        systemConfigMapper.delete(new LambdaQueryWrapper<SystemConfig>()
                .likeRight(SystemConfig::getConfigKey, "notification.smtp."));
        systemConfigMapper.delete(new LambdaQueryWrapper<SystemConfig>()
                .likeRight(SystemConfig::getConfigKey, "notification.email-share."));
    }

    @AfterEach
    void cleanup() {
        createdNodeIds.forEach(fileMapper::deleteById);
        createdNodeIds.clear();
        if (!usedRecipients.isEmpty()) {
            notificationLogMapper.delete(new LambdaQueryWrapper<NotificationLog>()
                    .in(NotificationLog::getRecipient, usedRecipients));
            usedRecipients.clear();
        }
        for (String userId : createdUserIds) {
            notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                    .eq(Notification::getUserId, userId));
            redissonClient.getBucket(EmailShareRecipientSupport.KEY_PREFIX + userId, StringCodec.INSTANCE).delete();
        }
        createdUserIds.forEach(userMapper::deleteById);
        createdSpaceIds.forEach(storageSpaceMapper::deleteById);
        createdUserIds.clear();
        createdSpaceIds.clear();
        systemConfigMapper.delete(new LambdaQueryWrapper<SystemConfig>()
                .likeRight(SystemConfig::getConfigKey, "notification.smtp."));
        systemConfigMapper.delete(new LambdaQueryWrapper<SystemConfig>()
                .likeRight(SystemConfig::getConfigKey, "notification.email-share."));
    }

    /**
     * 本地小文件：GreenMail 收到含附件的邮件（附件名与内容一致），发送记录成功，最近收件人更新。
     */
    @Test
    void shouldSendAttachmentMailAndRecordSuccess() throws Exception {
        UserWithSpace owner = prepareUser();
        configureSmtp(GREEN_MAIL.getSmtp().getPort());
        FileNode node = insertLocalFile(owner, "note-" + tag + ".txt", "hello-attachment");
        String recipient = "att-" + tag + "@example.com";

        emailShareService.sendAttachment(owner.user().getId(), owner.user().getUsername(),
                attachmentDto(List.of(node.getId()), List.of(recipient)));

        assertTrue(GREEN_MAIL.waitForIncomingEmail(5000, 1), "未在超时内收到附件邮件");
        MimeMessage message = GREEN_MAIL.getReceivedMessages()[0];
        assertEquals(recipient, message.getAllRecipients()[0].toString());
        assertEquals(node.getName(), attachmentName(message));
        assertTrue(attachmentContent(message).contains("hello-attachment"), "附件内容应写入文件字节");

        awaitNotificationLogSuccess(List.of(recipient));
        awaitRecentRecipients(owner.user().getId(), recipient);
        assertEquals(0, countNotifications(owner.user().getId()), "成功发送不应产生站内通知");
    }

    /**
     * 总大小超过上限（配置为 1MB）时同步拒绝，提示改用链接分享。
     */
    @Test
    void shouldRejectWhenOverSizeLimit() {
        UserWithSpace owner = prepareUser();
        configureSmtp(GREEN_MAIL.getSmtp().getPort(), 1);
        FileNode node = insertLocalFile(owner, "big-" + tag + ".bin", "x");
        node.setSize(2L * 1024 * 1024);
        fileMapper.updateById(node);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> emailShareService.sendAttachment(owner.user().getId(), owner.user().getUsername(),
                        attachmentDto(List.of(node.getId()), List.of("over-" + tag + "@example.com"))));
        assertTrue(exception.getMessage().contains("超过附件大小上限 1MB"));
        assertTrue(exception.getMessage().contains("请改用链接分享"));
    }

    /**
     * 文件夹节点与他人文件节点均被拒绝。
     */
    @Test
    void shouldRejectFolderAndOthersFile() {
        UserWithSpace owner = prepareUser();
        UserWithSpace other = prepareUser();
        configureSmtp(GREEN_MAIL.getSmtp().getPort());
        FileNode folder = insertFolder(owner);
        FileNode otherFile = insertLocalFile(other, "other-" + tag + ".txt", "x");

        BusinessException folderException = assertThrows(BusinessException.class,
                () -> emailShareService.sendAttachment(owner.user().getId(), owner.user().getUsername(),
                        attachmentDto(List.of(folder.getId()), List.of("f-" + tag + "@example.com"))));
        assertEquals(ResultCode.PARAM_ERROR, folderException.getResultCode());

        BusinessException otherException = assertThrows(BusinessException.class,
                () -> emailShareService.sendAttachment(owner.user().getId(), owner.user().getUsername(),
                        attachmentDto(List.of(otherFile.getId()), List.of("o-" + tag + "@example.com"))));
        assertEquals(ResultCode.NOT_FOUND, otherException.getResultCode());
    }

    /**
     * SMTP 不可达导致发送失败：写失败记录并给操作者补一条 email_share_failed 站内通知（含文件名）。
     */
    @Test
    void shouldRecordFailureAndNotifyOperatorWhenSendFails() throws Exception {
        UserWithSpace owner = prepareUser();
        configureSmtp(closedPort());
        FileNode node = insertLocalFile(owner, "fail-" + tag + ".txt", "x");
        String recipient = "fail-" + tag + "@example.com";

        emailShareService.sendAttachment(owner.user().getId(), owner.user().getUsername(),
                attachmentDto(List.of(node.getId()), List.of(recipient)));

        awaitNotificationLogFailure(List.of(recipient));
        Notification notification = awaitNotification(owner.user().getId());
        assertEquals(NotificationEventType.EMAIL_SHARE_FAILED.getValue(), notification.getEventType());
        assertTrue(notification.getContent().contains(node.getName()), "通知内容应含文件名");
        assertTrue(notification.getContent().contains("失败原因"), "通知内容应含失败原因");
    }

    /**
     * 附件大小上限默认 50，保存后读回配置值。
     */
    @Test
    void shouldUseDefaultAttachmentMaxSizeAndPersistConfig() {
        assertEquals(SmtpConfigSupport.DEFAULT_ATTACHMENT_MAX_SIZE_MB, smtpConfigSupport.attachmentMaxSizeMb());

        SmtpConfigDto dto = new SmtpConfigDto();
        dto.setHost("127.0.0.1");
        dto.setPort(GREEN_MAIL.getSmtp().getPort());
        dto.setUsername("");
        dto.setEncryption(SmtpConfigSupport.ENCRYPTION_NONE);
        dto.setFromAddress("from@example.com");
        dto.setFromName("jcloud");
        dto.setAttachmentMaxSizeMb(8);
        smtpConfigSupport.save(dto);

        assertEquals(8, smtpConfigSupport.attachmentMaxSizeMb());
        SystemConfig stored = systemConfigMapper.selectOne(new LambdaQueryWrapper<SystemConfig>()
                .eq(SystemConfig::getConfigKey, SmtpConfigSupport.CONFIG_KEY_ATTACHMENT_MAX_SIZE));
        assertEquals("8", stored.getConfigValue());
    }

    private UserWithSpace prepareUser() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        createdUserIds.add(userWithSpace.user().getId());
        createdSpaceIds.add(userWithSpace.space().getId());
        return userWithSpace;
    }

    private void configureSmtp(int port) {
        configureSmtp(port, null);
    }

    private void configureSmtp(int port, Integer attachmentMaxSizeMb) {
        SmtpConfigDto dto = new SmtpConfigDto();
        dto.setHost("127.0.0.1");
        dto.setPort(port);
        dto.setUsername("");
        dto.setEncryption(SmtpConfigSupport.ENCRYPTION_NONE);
        dto.setFromAddress("from@example.com");
        dto.setFromName("jcloud");
        dto.setAttachmentMaxSizeMb(attachmentMaxSizeMb);
        smtpConfigSupport.save(dto);
    }

    private int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private FileNode insertLocalFile(UserWithSpace owner, String name, String content) {
        try {
            Path physical = resolvePhysicalPath(owner, name);
            Files.createDirectories(physical.getParent());
            Files.writeString(physical, content);
        } catch (Exception e) {
            throw new IllegalStateException("写入测试物理文件失败", e);
        }
        FileNode node = new FileNode();
        node.setId(IdUtil.nextId());
        node.setUserId(owner.user().getId());
        node.setParentId(FileNodeConstants.ROOT_ID);
        node.setPath(FileNodeConstants.ROOT_ID);
        node.setName(name);
        node.setType(FileNodeConstants.TYPE_FILE);
        node.setSize((long) content.getBytes().length);
        node.setStorageSpaceId(owner.space().getId());
        node.setSourceType(FileNodeConstants.SOURCE_LOCAL);
        node.setStatus(1);
        fileMapper.insert(node);
        createdNodeIds.add(node.getId());
        return node;
    }

    private FileNode insertFolder(UserWithSpace owner) {
        FileNode node = new FileNode();
        node.setId(IdUtil.nextId());
        node.setUserId(owner.user().getId());
        node.setParentId(FileNodeConstants.ROOT_ID);
        node.setPath(FileNodeConstants.ROOT_ID);
        node.setName("folder-" + tag);
        node.setType(FileNodeConstants.TYPE_FOLDER);
        node.setSize(0L);
        node.setStorageSpaceId(owner.space().getId());
        node.setSourceType(FileNodeConstants.SOURCE_LOCAL);
        node.setStatus(1);
        fileMapper.insert(node);
        createdNodeIds.add(node.getId());
        return node;
    }

    private EmailShareAttachmentDto attachmentDto(List<String> fileNodeIds, List<String> recipients) {
        EmailShareAttachmentDto dto = new EmailShareAttachmentDto();
        dto.setFileNodeIds(fileNodeIds);
        dto.setRecipients(recipients);
        usedRecipients.addAll(recipients);
        return dto;
    }

    private String attachmentName(MimeMessage message) throws Exception {
        Object content = message.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String fileName = multipart.getBodyPart(i).getFileName();
                if (fileName != null) {
                    return fileName;
                }
            }
        }
        return null;
    }

    private String attachmentContent(MimeMessage message) throws Exception {
        Object content = message.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                if (multipart.getBodyPart(i).getFileName() != null) {
                    return String.valueOf(multipart.getBodyPart(i).getContent());
                }
            }
        }
        return "";
    }

    private void awaitNotificationLogSuccess(List<String> recipients) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (countLogs(recipients, NotificationLogSupport.SUCCESS) == 0) {
            if (System.currentTimeMillis() > deadline) {
                fail("成功发送记录未在超时内落库: recipients=" + recipients);
            }
            sleepQuietly();
        }
    }

    private void awaitNotificationLogFailure(List<String> recipients) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (countLogs(recipients, NotificationLogSupport.FAILURE) == 0) {
            if (System.currentTimeMillis() > deadline) {
                fail("失败发送记录未在超时内落库: recipients=" + recipients);
            }
            sleepQuietly();
        }
    }

    private long countLogs(List<String> recipients, int success) {
        return notificationLogMapper.selectCount(new LambdaQueryWrapper<NotificationLog>()
                .eq(NotificationLog::getEventType, EmailShareSender.EVENT_TYPE)
                .eq(NotificationLog::getSuccess, success)
                .in(NotificationLog::getRecipient, recipients));
    }

    private long countNotifications(String userId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId));
    }

    private Notification awaitNotification(String userId) {
        long deadline = System.currentTimeMillis() + 10_000;
        Notification notification = null;
        while (notification == null) {
            List<Notification> list = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                    .eq(Notification::getUserId, userId)
                    .eq(Notification::getEventType, NotificationEventType.EMAIL_SHARE_FAILED.getValue()));
            if (!list.isEmpty()) {
                notification = list.get(0);
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                fail("站内通知未在超时内落库: userId=" + userId);
            }
            sleepQuietly();
        }
        return notification;
    }

    private void awaitRecentRecipients(String userId, String recipient) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!recipientSupport.list(userId).contains(recipient)) {
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
