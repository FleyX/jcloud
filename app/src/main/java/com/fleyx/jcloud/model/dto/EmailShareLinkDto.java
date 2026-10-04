package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 邮件分享（链接分享模式）发送 DTO。
 * <p>
 * 分享链接由前端组装（后端不知道站点 origin），收件人为 1~20 个邮箱地址。
 */
@Data
public class EmailShareLinkDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分享短码。
     */
    @NotBlank(message = "分享编码不能为空")
    @Size(max = 64, message = "分享编码不合法")
    private String shareCode;

    /**
     * 完整分享链接。
     */
    @NotBlank(message = "分享链接不能为空")
    @Size(max = 1024, message = "分享链接过长")
    private String shareUrl;

    /**
     * 分享名称，用于邮件正文。
     */
    @NotBlank(message = "分享名称不能为空")
    @Size(max = 128, message = "分享名称不能超过 128 个字符")
    private String shareName;

    /**
     * 访问密码明文，为空表示分享无密码；仅用于写入邮件正文。
     */
    @Size(max = 32, message = "访问密码不能超过 32 个字符")
    private String password;

    /**
     * 收件邮箱列表。
     */
    @NotEmpty(message = "收件邮箱不能为空")
    @Size(max = 20, message = "收件人最多 20 个")
    private List<String> recipients;
}
