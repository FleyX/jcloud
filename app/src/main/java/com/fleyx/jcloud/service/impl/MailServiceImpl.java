package com.fleyx.jcloud.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.model.bo.MailAttachment;
import com.fleyx.jcloud.model.bo.SmtpConfig;
import com.fleyx.jcloud.service.MailService;
import com.fleyx.jcloud.service.support.SmtpConfigSupport;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/**
 * 邮件发送业务实现：按系统配置表中当前 SMTP 配置现场构建 JavaMailSender 发送，
 * 不使用 Spring 自动配置的 JavaMailSender（配置存于 DB 且运行时可改）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailServiceImpl implements MailService {

    private static final String TEST_SUBJECT = "jcloud 测试邮件";

    private static final String TEST_BODY = "<p>这是一封来自 jcloud 的测试邮件。</p>"
            + "<p>收到本邮件说明发件邮箱配置正确，通知功能可用。</p>";

    private final SmtpConfigSupport smtpConfigSupport;

    @Override
    public void send(String to, String subject, String htmlBody) {
        doSend(to, subject, htmlBody, List.of());
    }

    @Override
    public void sendWithAttachments(String to, String subject, String htmlBody, List<MailAttachment> attachments) {
        doSend(to, subject, htmlBody, attachments == null ? List.of() : attachments);
    }

    private void doSend(String to, String subject, String htmlBody, List<MailAttachment> attachments) {
        SmtpConfig config = smtpConfigSupport.load();
        if (!config.isConfigured()) {
            throw new BusinessException("未配置发件邮箱，通知功能不可用");
        }
        boolean multipart = !attachments.isEmpty();
        try {
            JavaMailSenderImpl sender = buildSender(config);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, multipart, StandardCharsets.UTF_8.name());
            helper.setFrom(config.getFromAddress(), config.getFromName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            for (MailAttachment attachment : attachments) {
                helper.addAttachment(attachment.fileName(), new ByteArrayResource(attachment.content()));
            }
            sender.send(message);
            log.info("邮件发送成功：to={}, subject={}, attachments={}", to, subject, attachments.size());
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "邮件发送失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void sendTest(String to) {
        send(to, TEST_SUBJECT, TEST_BODY);
    }

    /**
     * 按当前配置组装 JavaMailSender：ssl → mail.smtp.ssl.enable，starttls → mail.smtp.starttls.enable，
     * none → 两者均关闭；账号非空才启用认证。
     */
    JavaMailSenderImpl buildSender(SmtpConfig config) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.getHost());
        sender.setPort(config.getPort() == null ? SmtpConfigSupport.DEFAULT_PORT : config.getPort());
        boolean auth = StrUtil.isNotBlank(config.getUsername());
        if (auth) {
            sender.setUsername(config.getUsername());
            sender.setPassword(config.getPassword());
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(auth));
        props.put("mail.smtp.ssl.enable", String.valueOf(SmtpConfigSupport.ENCRYPTION_SSL.equals(config.getEncryption())));
        props.put("mail.smtp.starttls.enable",
                String.valueOf(SmtpConfigSupport.ENCRYPTION_STARTTLS.equals(config.getEncryption())));
        return sender;
    }
}
