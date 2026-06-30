package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 更新分享 DTO。
 */
@Data
public class ShareUpdateDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分享名称。
     */
    @NotBlank(message = "分享名称不能为空")
    @Size(max = 128, message = "分享名称不能超过 128 个字符")
    private String name;

    /**
     * 分享描述。
     */
    @Size(max = 512, message = "分享描述不能超过 512 个字符")
    private String description;

    /**
     * 文件节点 ID 列表。
     */
    @NotEmpty(message = "至少选择一个文件或文件夹")
    private List<String> fileNodeIds;

    /**
     * 访问密码，为空表示无密码；为 null 表示不修改密码。
     */
    @Size(max = 32, message = "访问密码不能超过 32 个字符")
    private String password;

    /**
     * 过期时间，为空表示永久有效。
     */
    private LocalDateTime expireAt;

    /**
     * 最大访问次数，为空表示无限制。
     */
    private Long maxViews;

    /**
     * 状态：1 启用，0 停用。
     */
    private Integer status;
}
