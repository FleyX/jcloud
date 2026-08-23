package com.fleyx.jcloud.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 令牌对视图。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenPairVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 访问令牌。
     */
    private String token;

    /**
     * 刷新令牌。
     */
    private String refreshToken;

    /**
     * 访问令牌过期时间，epoch 毫秒。
     */
    private Long accessExpiresAt;
}
