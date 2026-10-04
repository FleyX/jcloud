package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 邮件分享异步发送器：逐收件人发信、写发送记录，并把成功地址并入收件人记忆。
 * <p>
 * 异步执行不阻塞接口返回；单个收件人失败不影响其余收件人，仅记录失败原因。
 * 发送失败给操作者补站内通知由后续工单处理，本类不涉及。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailShareSender {

    /**
     * 发送记录中的事件类型标识（非通知事件枚举，展示时回退为该值）。
     */
    public static final String EVENT_TYPE = "email_share";

    private final MailService mailService;
    private final NotificationLogSupport notificationLogSupport;
    private final EmailShareRecipientSupport recipientSupport;

    /**
     * 异步逐收件人发送分享邮件。
     *
     * @param userId     操作者用户 ID，用于收件人记忆
     * @param subject    邮件主题
     * @param html       HTML 正文
     * @param recipients 收件邮箱列表
     */
    @Async
    public void sendLinkEmails(String userId, String subject, String html, List<String> recipients) {
        List<String> succeeded = new ArrayList<>();
        for (String recipient : recipients) {
            try {
                mailService.send(recipient, subject, html);
                notificationLogSupport.record(EVENT_TYPE, recipient, subject, true, null);
                succeeded.add(recipient);
            } catch (Exception e) {
                log.warn("邮件分享发送失败：to={}, subject={}", recipient, subject, e);
                notificationLogSupport.record(EVENT_TYPE, recipient, subject, false, e.getMessage());
            }
        }
        recipientSupport.record(userId, succeeded);
    }
}
