package com.fleyx.jcloud.model.bo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 刷新令牌结果。
 */
@Data
@AllArgsConstructor
public class RefreshResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 新访问令牌。
     */
    private String accessToken;

    /**
     * 新刷新令牌。
     */
    private String refreshToken;
}
