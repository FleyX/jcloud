package com.fleyx.jcloud.service.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐收件人「发送并记录」支撑类：统一邮件通知渠道与邮件分享的发送循环。
 * <p>
 * 单个收件人失败不影响其余收件人，且每个收件人各写一条发送记录（成功/失败）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailSendSupport {

    private final NotificationLogSupport notificationLogSupport;

    /**
     * 单个收件人的发送动作，允许抛出受检异常。
     */
    @FunctionalInterface
    public interface MailSender {

        /**
         * 向指定收件人发送邮件。
         *
         * @param recipient 收件邮箱地址
         * @throws Exception 发送失败
         */
        void send(String recipient) throws Exception;
    }

    /**
     * 单个收件人的发送结果。
     *
     * @param recipient 收件邮箱地址
     * @param success   是否发送成功
     * @param error     失败原因，成功时为 null
     */
    public record SendOutcome(String recipient, boolean success, String error) {
    }

    /**
     * 向单个收件人发送并写一条发送记录，异常不向外抛出（由调用方按结果处理）。
     *
     * @param eventType 发送记录中的事件类型
     * @param recipient 收件邮箱地址
     * @param subject   邮件主题
     * @param sender    发送动作
     * @return 发送结果
     */
    public SendOutcome sendAndRecord(String eventType, String recipient, String subject, MailSender sender) {
        try {
            sender.send(recipient);
            notificationLogSupport.record(eventType, recipient, subject, true, null);
            return new SendOutcome(recipient, true, null);
        } catch (Exception e) {
            log.warn("邮件发送失败：to={}, eventType={}", recipient, eventType, e);
            notificationLogSupport.record(eventType, recipient, subject, false, e.getMessage());
            return new SendOutcome(recipient, false, e.getMessage());
        }
    }

    /**
     * 逐收件人发送并记录：任一收件人失败不影响其余收件人，每收件人各一条记录。
     *
     * @param eventType  发送记录中的事件类型
     * @param recipients 收件邮箱列表
     * @param subject    邮件主题
     * @param sender     发送动作
     * @return 按收件人顺序的发送结果列表
     */
    public List<SendOutcome> sendEachAndRecord(String eventType, List<String> recipients,
                                               String subject, MailSender sender) {
        List<SendOutcome> outcomes = new ArrayList<>(recipients.size());
        for (String recipient : recipients) {
            outcomes.add(sendAndRecord(eventType, recipient, subject, sender));
        }
        return outcomes;
    }
}
