package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.dto.EmailShareAttachmentDto;
import com.fleyx.jcloud.model.dto.EmailShareLinkDto;

import java.util.List;

/**
 * 邮件分享业务接口。
 */
public interface EmailShareService {

    /**
     * 校验并异步发送链接分享邮件（链接分享模式）。
     * <p>
     * 同步完成参数校验、分享归属校验与发件邮箱配置检查，随后异步逐收件人发送，立即返回。
     *
     * @param userId   当前用户 ID
     * @param username 当前用户名，用于邮件标题
     * @param dto      发送参数
     */
    void sendLink(String userId, String username, EmailShareLinkDto dto);

    /**
     * 校验并异步发送附件直发邮件（附件直发模式）。
     * <p>
     * 同步完成收件人、文件归属与大小上限校验，随后异步组装附件逐收件人发送，立即返回。
     * 发送失败时给操作者补一条站内通知。
     *
     * @param userId   当前用户 ID
     * @param username 当前用户名，用于邮件标题
     * @param dto      发送参数
     */
    void sendAttachment(String userId, String username, EmailShareAttachmentDto dto);

    /**
     * 查询当前用户最近 5 个收件邮箱（最新在前）。
     *
     * @param userId 当前用户 ID
     * @return 最近收件邮箱列表
     */
    List<String> recentRecipients(String userId);
}
