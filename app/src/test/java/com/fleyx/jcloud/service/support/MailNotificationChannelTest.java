package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.enums.NotificationEventType;
import com.fleyx.jcloud.common.enums.NotificationTargetType;
import com.fleyx.jcloud.common.event.NotificationEvent;
import com.fleyx.jcloud.mapper.NotificationLogMapper;
import com.fleyx.jcloud.mapper.NotificationMapper;
import com.fleyx.jcloud.mapper.RoleMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.SystemConfigMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.dto.SmtpConfigDto;
import com.fleyx.jcloud.model.po.Notification;
import com.fleyx.jcloud.model.po.NotificationLog;
import com.fleyx.jcloud.model.po.Role;
import com.fleyx.jcloud.model.po.SystemConfig;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.po.UserRole;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 邮件通知渠道集成测试（工单 05）：GreenMail 本地回路，不真实外发。
 * <p>
 * 事件监听为异步执行，正向用例轮询落库结果（100ms 间隔、10s 超时）；负向用例在正向信号出现或固定等待后断言。
 * 不使用事务回滚（异步线程看不到未提交数据），测试数据按唯一用户隔离并在用例结束后物理清理。
 */
class MailNotificationChannelTest extends IntegrationTestBase {

    private static final String USER_EMAIL = "notice-user@example.com";

    private static final String ADMIN_EMAIL_A = "notice-admin-a@example.com";

    private static final String ADMIN_EMAIL_B = "notice-admin-b@example.com";

    /**
     * 本地 SMTP 仿真服务，动态端口避免冲突。
     */
    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL =
            new GreenMailExtension(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));

    @Autowired
    private SmtpConfigSupport smtpConfigSupport;

    @Autowired
    private NotificationSwitchSupport notificationSwitchSupport;

    @Autowired
    private NotificationRecipientSupport notificationRecipientSupport;

    @Autowired
    private MailNotificationChannel mailNotificationChannel;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private NotificationLogMapper notificationLogMapper;

    @Autowired
    private SystemConfigMapper systemConfigMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private final List<String> createdUserIds = new ArrayList<>();

    private final List<String> createdSpaceIds = new ArrayList<>();

    @BeforeEach
    void cleanMutableState() {
        systemConfigMapper.delete(new LambdaQueryWrapper<SystemConfig>()
                .likeRight(SystemConfig::getConfigKey, "notification.smtp."));
        systemConfigMapper.delete(new LambdaQueryWrapper<SystemConfig>()
                .likeRight(SystemConfig::getConfigKey, NotificationSwitchSupport.CONFIG_KEY_PREFIX));
        notificationLogMapper.delete(null);
    }

    @AfterEach
    void cleanup() {
        List<String> notificationUserIds = new ArrayList<>(createdUserIds);
        notificationUserIds.addAll(superAdminUserIds());
        for (String userId : notificationUserIds) {
            notificationMapper.delete(new LambdaQueryWrapper<Notification>().eq(Notification::getUserId, userId));
        }
        notificationLogMapper.delete(null);
        for (String userId : createdUserIds) {
            userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
            userMapper.deleteById(userId);
        }
        createdSpaceIds.forEach(storageSpaceMapper::deleteById);
        createdUserIds.clear();
        createdSpaceIds.clear();
    }

    /**
     * 事件启用且 SMTP 已配置：站内通知落行、邮件送达 GreenMail、发送记录成功行。
     */
    @Test
    void shouldDeliverInAppAndEmailWhenEventEnabled() throws Exception {
        configureSmtpToGreenMail();
        String userId = prepareUser(USER_EMAIL).user().getId();

        publishUserEvent(userId, "跨来源传输完成", "复制任务完成：成功 3 个，失败 0 个");

        awaitInAppCount(userId, 1);
        awaitLogCount(1);
        NotificationLog log = notificationLogMapper.selectList(null).get(0);
        assertEquals(NotificationEventType.TRANSFER_COMPLETED.getValue(), log.getEventType());
        assertEquals(USER_EMAIL, log.getRecipient());
        assertEquals("跨来源传输完成", log.getSubject());
        assertEquals(NotificationLogSupport.SUCCESS, log.getSuccess());
        assertNull(log.getErrorMessage());

        assertTrue(GREEN_MAIL.waitForIncomingEmail(5000, 1));
        MimeMessage message = GREEN_MAIL.getReceivedMessages()[0];
        assertEquals(USER_EMAIL, message.getAllRecipients()[0].toString());
        assertEquals("跨来源传输完成", message.getSubject());
        assertTrue(String.valueOf(message.getContent()).contains("成功 3 个，失败 0 个"));
    }

    /**
     * 收件用户无邮箱：仅站内通知落行，不产生发送记录，也不发信。
     */
    @Test
    void shouldSkipEmailAndRecordNothingWhenRecipientHasNoEmail() {
        configureSmtpToGreenMail();
        String userId = prepareUser(null).user().getId();

        publishUserEvent(userId, "跨来源传输完成", "复制任务完成");

        awaitInAppCount(userId, 1);
        sleepQuietly();
        assertEquals(0L, notificationLogMapper.selectCount(null), "无邮箱不应产生发送记录");
        assertFalse(GREEN_MAIL.waitForIncomingEmail(500, 1), "无邮箱不应发信");
    }

    /**
     * 未配置 SMTP：邮件渠道静默跳过，站内通知不受影响且无发送记录。
     */
    @Test
    void shouldSkipEmailChannelSilentlyWhenSmtpNotConfigured() {
        String userId = prepareUser(USER_EMAIL).user().getId();

        publishUserEvent(userId, "跨来源传输失败", "复制任务失败");

        awaitInAppCount(userId, 1);
        sleepQuietly();
        assertEquals(0L, notificationLogMapper.selectCount(null), "未配置 SMTP 不应产生发送记录");
        assertFalse(GREEN_MAIL.waitForIncomingEmail(500, 1), "未配置 SMTP 不应发信");
    }

    /**
     * 管理员类事件广播给全部持有超管角色且启用的用户：有邮箱者各收一封，无邮箱者跳过。
     */
    @Test
    void shouldBroadcastToAllSuperAdminsWithEmail() throws Exception {
        configureSmtpToGreenMail();
        String adminA = prepareUser(ADMIN_EMAIL_A).user().getId();
        String adminB = prepareUser(ADMIN_EMAIL_B).user().getId();
        String adminWithoutEmail = prepareUser(null).user().getId();
        bindSuperAdmin(adminA);
        bindSuperAdmin(adminB);
        bindSuperAdmin(adminWithoutEmail);

        eventPublisher.publishEvent(new NotificationEvent(this, NotificationEventType.STORAGE_CAPACITY_ALERT,
                NotificationTargetType.ADMINS, null, "存储空间容量告警", "存储空间剩余容量不足 10%"));

        awaitInAppCount(adminA, 1);
        awaitInAppCount(adminB, 1);
        awaitInAppCount(adminWithoutEmail, 1);
        awaitLogCount(2);

        Set<String> recipients = notificationLogMapper.selectList(null).stream()
                .map(NotificationLog::getRecipient)
                .collect(Collectors.toSet());
        assertEquals(Set.of(ADMIN_EMAIL_A, ADMIN_EMAIL_B), recipients);
        assertTrue(GREEN_MAIL.waitForIncomingEmail(5000, 2));
        assertEquals(2, GREEN_MAIL.getReceivedMessages().length);
    }

    /**
     * 事件被禁用：站内与邮件均不触发，也无发送记录。
     */
    @Test
    void shouldSkipAllChannelsWhenEventDisabled() {
        configureSmtpToGreenMail();
        String userId = prepareUser(USER_EMAIL).user().getId();
        notificationSwitchSupport.set(NotificationEventType.TRANSFER_COMPLETED, false);
        assertFalse(notificationSwitchSupport.isEnabled(NotificationEventType.TRANSFER_COMPLETED));

        publishUserEvent(userId, "跨来源传输完成", "复制任务完成");
        sleepQuietly();

        assertEquals(0L, inAppCount(userId), "事件禁用不应产生站内通知");
        assertEquals(0L, notificationLogMapper.selectCount(null), "事件禁用不应产生发送记录");
        assertFalse(GREEN_MAIL.waitForIncomingEmail(500, 1), "事件禁用不应发信");
    }

    /**
     * 发送失败（SMTP 不可达）：记录失败行并携带失败原因。
     */
    @Test
    void shouldRecordFailureLogWithReasonWhenSendFails() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            smtpConfigSupport.save(newSmtpConfig("127.0.0.1", socket.getLocalPort()));
        }
        String userId = prepareUser(USER_EMAIL).user().getId();

        publishUserEvent(userId, "跨来源传输失败", "复制任务失败");

        awaitLogCount(1);
        NotificationLog log = notificationLogMapper.selectList(null).get(0);
        assertEquals(NotificationLogSupport.FAILURE, log.getSuccess());
        assertEquals(USER_EMAIL, log.getRecipient());
        assertNotNull(log.getErrorMessage());
        assertTrue(log.getErrorMessage().contains("邮件发送失败"));
    }

    /**
     * 仅站内渠道事件（邮件分享失败）：即使事件开关被禁用仍恒定落站内通知，且不发邮件、无发送记录。
     */
    @Test
    void shouldDeliverInAppOnlyForEmailShareFailedRegardlessOfSwitch() {
        configureSmtpToGreenMail();
        String userId = prepareUser(USER_EMAIL).user().getId();
        notificationSwitchSupport.set(NotificationEventType.EMAIL_SHARE_FAILED, false);

        eventPublisher.publishEvent(new NotificationEvent(this, NotificationEventType.EMAIL_SHARE_FAILED,
                userId, "邮件分享失败", "文件：a.mobi\n失败原因：SMTP 不可达"));

        awaitInAppCount(userId, 1);
        sleepQuietly();
        assertEquals(0L, notificationLogMapper.selectCount(null), "仅站内渠道事件不应产生发送记录");
        assertFalse(GREEN_MAIL.waitForIncomingEmail(500, 1), "仅站内渠道事件不应发信");
    }

    /**
     * 收件人解析：用户级事件返回该用户，管理员广播返回持超管角色且启用的用户。
     */
    @Test
    void shouldResolveRecipientsByTargetType() {
        String userId = prepareUser(USER_EMAIL).user().getId();
        List<User> userRecipients = notificationRecipientSupport.resolve(
                new NotificationEvent(this, NotificationEventType.QUOTA_ALERT, userId, "配额告警", null));
        assertEquals(1, userRecipients.size());
        assertEquals(userId, userRecipients.get(0).getId());

        List<User> adminRecipients = notificationRecipientSupport.resolve(
                new NotificationEvent(this, NotificationEventType.USER_REGISTERED,
                        NotificationTargetType.ADMINS, null, "新用户注册", null));
        assertTrue(adminRecipients.stream().allMatch(user -> Integer.valueOf(1).equals(user.getStatus())));
    }

    // ---------- 工具方法 ----------

    private void configureSmtpToGreenMail() {
        smtpConfigSupport.save(newSmtpConfig("127.0.0.1", GREEN_MAIL.getSmtp().getPort()));
    }

    private SmtpConfigDto newSmtpConfig(String host, int port) {
        SmtpConfigDto dto = new SmtpConfigDto();
        dto.setHost(host);
        dto.setPort(port);
        dto.setUsername("");
        dto.setEncryption(SmtpConfigSupport.ENCRYPTION_NONE);
        dto.setFromAddress("from@example.com");
        dto.setFromName("jcloud");
        return dto;
    }

    private UserWithSpace prepareUser(String email) {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        createdUserIds.add(userWithSpace.user().getId());
        createdSpaceIds.add(userWithSpace.space().getId());
        if (email != null) {
            User update = new User();
            update.setId(userWithSpace.user().getId());
            update.setEmail(email);
            userMapper.updateById(update);
        }
        return userWithSpace;
    }

    private void bindSuperAdmin(String userId) {
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getCode, UserVoEnrichSupport.SUPER_ADMIN_ROLE_CODE)
                .last("LIMIT 1"));
        UserRole relation = new UserRole();
        relation.setUserId(userId);
        relation.setRoleId(role.getId());
        userRoleMapper.insert(relation);
    }

    private List<String> superAdminUserIds() {
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getCode, UserVoEnrichSupport.SUPER_ADMIN_ROLE_CODE)
                .last("LIMIT 1"));
        return role == null ? List.of() : userRoleMapper.selectUserIdsByRoleId(role.getId());
    }

    private void publishUserEvent(String userId, String title, String content) {
        eventPublisher.publishEvent(new NotificationEvent(this, NotificationEventType.TRANSFER_COMPLETED,
                userId, title, content));
    }

    private long inAppCount(String userId) {
        Long count = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId));
        return count == null ? 0L : count;
    }

    private void awaitInAppCount(String userId, long expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (inAppCount(userId) < expected) {
            if (System.currentTimeMillis() > deadline) {
                fail("站内通知未在超时内落库: userId=" + userId);
            }
            sleepQuietly();
        }
    }

    private void awaitLogCount(long expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (notificationLogMapper.selectCount(null) < expected) {
            if (System.currentTimeMillis() > deadline) {
                fail("发送记录未在超时内落库");
            }
            sleepQuietly();
        }
    }

    private void sleepQuietly() {
        sleepQuietly(100);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }
}
