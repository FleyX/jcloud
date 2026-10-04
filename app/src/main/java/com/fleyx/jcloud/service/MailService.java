package com.fleyx.jcloud.service;

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
     * 发送测试邮件（固定主题与正文）。
     *
     * @param to 收件人地址
     */
    void sendTest(String to);
}
