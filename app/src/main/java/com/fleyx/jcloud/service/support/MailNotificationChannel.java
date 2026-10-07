package com.fleyx.jcloud.service.support;

import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.common.event.NotificationEvent;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 邮件通知渠道（ADR 0039）：逐收件人发送 HTML 邮件并写入发送记录。
 * <p>
 * 未配置发件邮箱时整体静默跳过；收件人无邮箱时跳过且不写失败记录（站内渠道兜底）；
 * 仅站内渠道的兜底事件（如邮件分享失败）不走本渠道。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailNotificationChannel implements NotificationChannel {

    private final SmtpConfigSupport smtpConfigSupport;
    private final MailService mailService;
    private final NotificationRecipientSupport recipientSupport;
    private final MailSendSupport mailSendSupport;
    private final MailTemplateSupport mailTemplateSupport;

    @Override
    public void deliver(NotificationEvent event) {
        if (event.getEventType().isInAppOnly()) {
            log.debug("事件仅走站内渠道，跳过邮件通知：eventType={}", event.getEventType());
            return;
        }
        if (!smtpConfigSupport.isConfigured()) {
            log.debug("未配置发件邮箱，跳过邮件通知渠道：eventType={}", event.getEventType());
            return;
        }
        String html = mailTemplateSupport.buildHtml(event.getTitle(), event.getContent(), null, null);
        for (User user : recipientSupport.resolve(event)) {
            String email = user.getEmail();
            if (StrUtil.isBlank(email)) {
                continue;
            }
            sendAndRecord(event, email.trim(), html);
        }
    }

    private void sendAndRecord(NotificationEvent event, String email, String html) {
        String eventType = event.getEventType().getValue();
        String subject = event.getTitle();
        mailSendSupport.sendAndRecord(eventType, email, subject,
                recipient -> mailService.send(recipient, subject, html));
    }
}
