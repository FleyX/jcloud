package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户 WebDAV 访问开关 DTO。
 */
@Data
public class UserWebDavToggleDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否启用 WebDAV 访问。
     */
    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;
}
