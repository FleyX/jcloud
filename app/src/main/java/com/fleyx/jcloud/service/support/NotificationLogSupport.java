package com.fleyx.jcloud.service.support;

import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.mapper.NotificationLogMapper;
import com.fleyx.jcloud.model.po.NotificationLog;
import com.fleyx.jcloud.util.IdUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 邮件发送记录支撑类：所有邮件发送（含后续邮件分享）复用同一落库方法。
 */
@Component
@RequiredArgsConstructor
public class NotificationLogSupport {

    /**
     * 发送结果：成功。
     */
    public static final int SUCCESS = 1;

    /**
     * 发送结果：失败。
     */
    public static final int FAILURE = 0;

    /**
     * 失败原因列长度上限 {@code error_message varchar(1024)}。
     */
    public static final int ERROR_MAX_LENGTH = 1024;

    private final NotificationLogMapper notificationLogMapper;

    /**
     * 记录一次邮件发送结果。
     *
     * @param eventType 事件类型（含 email_share 邮件分享）
     * @param recipient 收件邮箱地址
     * @param subject   邮件主题
     * @param success   是否发送成功
     * @param error     失败原因，成功时可为空
     */
    public void record(String eventType, String recipient, String subject, boolean success, String error) {
        NotificationLog log = new NotificationLog();
        log.setId(IdUtil.nextId());
        log.setEventType(eventType);
        log.setRecipient(recipient);
        log.setSubject(subject);
        log.setSuccess(success ? SUCCESS : FAILURE);
        log.setErrorMessage(success ? null : StrUtil.subPre(error, ERROR_MAX_LENGTH));
        notificationLogMapper.insert(log);
    }
}
