package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分享访问密码校验 DTO。
 */
@Data
public class ShareAccessDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 访问密码。
     */
    @NotBlank(message = "访问密码不能为空")
    private String password;
}
