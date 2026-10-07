package com.fleyx.jcloud.model.bo;

/**
 * 邮件附件业务对象：文件名与字节内容，仅内部使用。
 *
 * @param fileName 附件文件名
 * @param content  附件字节内容
 */
public record MailAttachment(String fileName, byte[] content) {
}
