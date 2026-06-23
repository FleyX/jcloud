package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 统一响应码枚举。
 */
@Getter
public enum ResultCode {

    /**
     * 成功。
     */
    SUCCESS(200, "操作成功"),

    /**
     * 参数校验失败。
     */
    PARAM_ERROR(400, "请求参数错误"),

    /**
     * 未授权。
     */
    UNAUTHORIZED(401, "未授权"),

    /**
     * 禁止访问。
     */
    FORBIDDEN(403, "禁止访问"),

    /**
     * 资源不存在。
     */
    NOT_FOUND(404, "资源不存在"),

    /**
     * 业务异常。
     */
    BUSINESS_ERROR(500, "业务处理失败"),

    /**
     * 系统内部错误。
     */
    SYSTEM_ERROR(500, "系统内部错误");

    private final int code;
    private final String msg;

    ResultCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
