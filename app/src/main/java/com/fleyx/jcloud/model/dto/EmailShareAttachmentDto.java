package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 邮件分享（附件直发模式）发送 DTO。
 * <p>
 * 收件人为 1~20 个邮箱地址，文件节点为 1 个及以上，仅支持文件类型节点。
 */
@Data
public class EmailShareAttachmentDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 文件节点 ID 列表。
     */
    @NotEmpty(message = "请选择要发送的文件")
    private List<String> fileNodeIds;

    /**
     * 收件邮箱列表。
     */
    @NotEmpty(message = "收件邮箱不能为空")
    @Size(max = 20, message = "收件人最多 20 个")
    private List<String> recipients;
}
