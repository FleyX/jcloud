package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 刷新令牌入参。
 */
@Data
public class TokenRefreshDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "刷新令牌不能为空")
    private String refreshToken;
}
