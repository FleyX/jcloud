package com.fleyx.jcloud.common;

import com.fleyx.jcloud.common.enums.ResultCode;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应体。
 *
 * @param <T> 数据类型
 */
@Data
public class R<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 响应码。
     */
    private Integer code;

    /**
     * 响应消息。
     */
    private String msg;

    /**
     * 响应数据。
     */
    private T data;

    /**
     * 请求链路追踪 ID，便于日志排查。
     */
    private String traceId;

    public R() {
    }

    public R(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    /**
     * 成功响应，无数据。
     */
    public static <T> R<T> ok() {
        return ok(null);
    }

    /**
     * 成功响应，带数据。
     */
    public static <T> R<T> ok(T data) {
        return new R<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), data);
    }

    /**
     * 失败响应，使用默认业务异常消息。
     */
    public static <T> R<T> fail() {
        return fail(ResultCode.BUSINESS_ERROR.getMsg());
    }

    /**
     * 失败响应，自定义消息。
     */
    public static <T> R<T> fail(String msg) {
        return new R<>(ResultCode.BUSINESS_ERROR.getCode(), msg, null);
    }

    /**
     * 失败响应，自定义响应码与消息。
     */
    public static <T> R<T> fail(ResultCode resultCode) {
        return new R<>(resultCode.getCode(), resultCode.getMsg(), null);
    }

    /**
     * 失败响应，自定义响应码与消息。
     */
    public static <T> R<T> fail(ResultCode resultCode, String msg) {
        return new R<>(resultCode.getCode(), msg, null);
    }

    /**
     * 判断当前响应是否成功。
     */
    public boolean isSuccess() {
        return ResultCode.SUCCESS.getCode() == this.code;
    }
}
