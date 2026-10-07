package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.NotificationLogMapper;
import com.fleyx.jcloud.mapper.SystemConfigMapper;
import com.fleyx.jcloud.model.bo.SmtpConfig;
import com.fleyx.jcloud.model.dto.SmtpConfigDto;
import com.fleyx.jcloud.model.po.NotificationLog;
import com.fleyx.jcloud.model.po.SystemConfig;
import com.fleyx.jcloud.service.MailService;
import com.fleyx.jcloud.service.SystemConfigService;
import com.fleyx.jcloud.service.support.NotificationLogSupport;
import com.fleyx.jcloud.service.support.SmtpConfigSupport;
import com.fleyx.jcloud.util.RemoteConfigCrypto;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件发送集成测试：SMTP 配置读写（加密/解密）与真实 SMTP 回路（GreenMail 本地仿真）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MailServiceImplTest {

    /**
     * 本地 SMTP 仿真服务，动态端口避免冲突。
     */
    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL =
            new GreenMailExtension(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));

    @Autowired
    private MailServiceImpl mailServiceImpl;

    @Autowired
    private MailService mailService;

    @Autowired
    private SmtpConfigSupport smtpConfigSupport;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private RemoteConfigCrypto remoteConfigCrypto;

    @Autowired
    private SystemConfigMapper systemConfigMapper;

    @Autowired
    private NotificationLogMapper notificationLogMapper;

    @BeforeEach
    void cleanSmtpConfig() {
        systemConfigMapper.delete(new LambdaQueryWrapper<SystemConfig>()
                .likeRight(SystemConfig::getConfigKey, "notification.smtp."));
    }

    /**
     * 保存后 DB 中密码为密文，load() 解密回明文，其余字段原样回环。
     */
    @Test
    void shouldEncryptPasswordAndReloadConfig() {
        smtpConfigSupport.save(newConfigDto("smtp.example.com", 465, "user@example.com",
                "secret-pass", SmtpConfigSupport.ENCRYPTION_SSL, "from@example.com", "发件人"));

        String stored = systemConfigService.getValue(SmtpConfigSupport.CONFIG_KEY_PASSWORD, "");
        assertNotEquals("secret-pass", stored);
        assertEquals("secret-pass", remoteConfigCrypto.decrypt(stored));

        SmtpConfig config = smtpConfigSupport.load();
        assertEquals("smtp.example.com", config.getHost());
        assertEquals(465, config.getPort());
        assertEquals("user@example.com", config.getUsername());
        assertEquals("secret-pass", config.getPassword());
        assertEquals(SmtpConfigSupport.ENCRYPTION_SSL, config.getEncryption());
        assertEquals("from@example.com", config.getFromAddress());
        assertEquals("发件人", config.getFromName());
        assertTrue(config.isConfigured());
        assertTrue(smtpConfigSupport.isConfigured());
    }

    /**
     * 密码为空/空白时保留原密码，密文不变。
     */
    @Test
    void shouldKeepExistingPasswordWhenBlank() {
        smtpConfigSupport.save(newConfigDto("smtp.example.com", 465, "user@example.com",
                "secret-pass", SmtpConfigSupport.ENCRYPTION_SSL, "from@example.com", null));
        String storedBefore = systemConfigService.getValue(SmtpConfigSupport.CONFIG_KEY_PASSWORD, "");

        SmtpConfigDto blankPassword = newConfigDto("smtp.example.com", 465, "user@example.com",
                null, SmtpConfigSupport.ENCRYPTION_SSL, "from@example.com", null);
        blankPassword.setPassword("   ");
        smtpConfigSupport.save(blankPassword);

        assertEquals(storedBefore, systemConfigService.getValue(SmtpConfigSupport.CONFIG_KEY_PASSWORD, ""));
        assertEquals("secret-pass", smtpConfigSupport.load().getPassword());
    }

    /**
     * 未配置发件邮箱时 send/sendTest 抛 BusinessException；sendTest 失败仍记入发送记录。
     */
    @Test
    void shouldThrowBusinessExceptionWhenNotConfigured() {
        assertFalse(smtpConfigSupport.isConfigured());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> mailService.send("to@example.com", "主题", "<p>正文</p>"));
        assertTrue(exception.getMessage().contains("未配置发件邮箱"));

        assertThrows(BusinessException.class, () -> mailService.sendTest("to@example.com"));

        NotificationLog log = singleTestLog("to@example.com");
        assertEquals(NotificationLogSupport.FAILURE, log.getSuccess());
        assertNotNull(log.getErrorMessage());
    }

    /**
     * 指向本地 SMTP 仿真服务发送测试邮件：GreenMail 收到邮件、主题/收件人/HTML 正文正确，
     * 且写入一条成功发送记录（事件类型 smtp_test）。
     */
    @Test
    void shouldSendTestMailThroughSmtpServer() throws Exception {
        int port = GREEN_MAIL.getSmtp().getPort();
        smtpConfigSupport.save(newConfigDto("127.0.0.1", port, "", null,
                SmtpConfigSupport.ENCRYPTION_NONE, "from@example.com", "jcloud"));

        mailService.sendTest("to@example.com");

        NotificationLog log = singleTestLog("to@example.com");
        assertEquals(NotificationLogSupport.SUCCESS, log.getSuccess());
        assertEquals("jcloud 测试邮件", log.getSubject());
        assertNull(log.getErrorMessage());

        assertTrue(GREEN_MAIL.waitForIncomingEmail(5000, 1));
        MimeMessage[] messages = GREEN_MAIL.getReceivedMessages();
        assertEquals(1, messages.length);
        assertEquals("to@example.com", messages[0].getAllRecipients()[0].toString());
        assertEquals("jcloud 测试邮件", messages[0].getSubject());
        assertTrue(messages[0].isMimeType("text/html"));
        String body = String.valueOf(messages[0].getContent());
        assertTrue(body.contains("测试邮件"));
    }

    /**
     * JavaMailSender 属性组装：none 均关闭且不认证；ssl 开 ssl.enable；starttls 开 starttls.enable；
     * 账号非空才启用认证并写入用户名密码。
     */
    @Test
    void shouldAssembleSenderPropertiesByEncryption() {
        JavaMailSenderImpl none = mailServiceImpl.buildSender(
                newSmtpConfig("smtp.example.com", 587, "", null, SmtpConfigSupport.ENCRYPTION_NONE, "from@example.com"));
        assertEquals("smtp.example.com", none.getHost());
        assertEquals(587, none.getPort());
        assertEquals("false", none.getJavaMailProperties().getProperty("mail.smtp.ssl.enable"));
        assertEquals("false", none.getJavaMailProperties().getProperty("mail.smtp.starttls.enable"));
        assertEquals("false", none.getJavaMailProperties().getProperty("mail.smtp.auth"));
        assertNull(none.getUsername());

        JavaMailSenderImpl ssl = mailServiceImpl.buildSender(
                newSmtpConfig("smtp.example.com", 465, "user", "pwd", SmtpConfigSupport.ENCRYPTION_SSL, "from@example.com"));
        assertEquals("true", ssl.getJavaMailProperties().getProperty("mail.smtp.ssl.enable"));
        assertEquals("false", ssl.getJavaMailProperties().getProperty("mail.smtp.starttls.enable"));
        assertEquals("true", ssl.getJavaMailProperties().getProperty("mail.smtp.auth"));
        assertEquals("user", ssl.getUsername());
        assertEquals("pwd", ssl.getPassword());

        JavaMailSenderImpl starttls = mailServiceImpl.buildSender(
                newSmtpConfig("smtp.example.com", 587, "user", "pwd",
                        SmtpConfigSupport.ENCRYPTION_STARTTLS, "from@example.com"));
        assertEquals("false", starttls.getJavaMailProperties().getProperty("mail.smtp.ssl.enable"));
        assertEquals("true", starttls.getJavaMailProperties().getProperty("mail.smtp.starttls.enable"));
    }

    /**
     * SMTP 不可达时发送失败抛 SystemException 并携带原异常，同时写入失败发送记录。
     */
    @Test
    void shouldThrowSystemExceptionWhenSmtpUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        smtpConfigSupport.save(newConfigDto("127.0.0.1", closedPort, "", null,
                SmtpConfigSupport.ENCRYPTION_NONE, "from@example.com", null));

        SystemException exception = assertThrows(SystemException.class,
                () -> mailService.sendTest("to@example.com"));
        assertTrue(exception.getMessage().contains("邮件发送失败"));
        assertNotNull(exception.getCause());

        NotificationLog log = singleTestLog("to@example.com");
        assertEquals(NotificationLogSupport.FAILURE, log.getSuccess());
        assertNotNull(log.getErrorMessage());
    }

    private NotificationLog singleTestLog(String recipient) {
        List<NotificationLog> logs = notificationLogMapper.selectList(new LambdaQueryWrapper<NotificationLog>()
                .eq(NotificationLog::getEventType, MailServiceImpl.TEST_EVENT_TYPE)
                .eq(NotificationLog::getRecipient, recipient));
        assertEquals(1, logs.size(), "测试邮件应恰好写入一条发送记录");
        return logs.get(0);
    }

    private SmtpConfigDto newConfigDto(String host, int port, String username, String password,
                                       String encryption, String fromAddress, String fromName) {
        SmtpConfigDto dto = new SmtpConfigDto();
        dto.setHost(host);
        dto.setPort(port);
        dto.setUsername(username);
        dto.setPassword(password);
        dto.setEncryption(encryption);
        dto.setFromAddress(fromAddress);
        dto.setFromName(fromName);
        return dto;
    }

    private SmtpConfig newSmtpConfig(String host, int port, String username, String password,
                                     String encryption, String fromAddress) {
        SmtpConfig config = new SmtpConfig();
        config.setHost(host);
        config.setPort(port);
        config.setUsername(username);
        config.setPassword(password);
        config.setEncryption(encryption);
        config.setFromAddress(fromAddress);
        return config;
    }
}
