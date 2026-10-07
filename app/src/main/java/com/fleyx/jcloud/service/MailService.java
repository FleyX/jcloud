package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.bo.MailAttachment;

import java.util.List;

/**
 * 邮件发送业务接口。
 */
public interface MailService {

    /**
     * 发送 HTML 邮件。
     *
     * @param to       收件人地址
     * @param subject  邮件主题
     * @param htmlBody HTML 正文
     */
    void send(String to, String subject, String htmlBody);

    /**
     * 发送带附件的 HTML 邮件。
     *
     * @param to          收件人地址
     * @param subject     邮件主题
     * @param htmlBody    HTML 正文
     * @param attachments 附件列表
     */
    void sendWithAttachments(String to, String subject, String htmlBody, List<MailAttachment> attachments);

    /**
     * 发送测试邮件（固定主题与正文）。
     *
     * @param to 收件人地址
     */
    void sendTest(String to);
}
