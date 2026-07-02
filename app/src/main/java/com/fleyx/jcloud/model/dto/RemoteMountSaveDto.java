package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 创建远程挂载 DTO。
 */
@Data
public class RemoteMountSaveDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "挂载名称不能为空")
    @Size(max = 255, message = "挂载名称长度不能超过 255")
    private String name;

    @NotBlank(message = "协议类型不能为空")
    @Pattern(regexp = "webdav|s3|nfs", message = "协议类型只能是 webdav、s3 或 nfs")
    private String type;

    @NotBlank(message = "URL 不能为空")
    @Size(max = 512, message = "URL 长度不能超过 512")
    private String url;

    @Size(max = 128, message = "用户名长度不能超过 128")
    private String username;

    @Size(max = 256, message = "密码长度不能超过 256")
    private String password;

    @Size(max = 512, message = "根路径长度不能超过 512")
    private String rootPath;

    @Size(max = 128, message = "cron 表达式长度不能超过 128")
    private String cronExpr;

    @NotNull(message = "启用状态不能为空")
    private Integer enabled;
}
