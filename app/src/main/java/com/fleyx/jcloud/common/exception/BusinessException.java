package com.fleyx.jcloud.common.exception;

import com.fleyx.jcloud.common.enums.ResultCode;
import lombok.Getter;

/**
 * 业务异常。
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 响应码，默认业务异常。
     */
    private final ResultCode resultCode;

    public BusinessException(String message) {
        super(message);
        this.resultCode = ResultCode.BUSINESS_ERROR;
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMsg());
        this.resultCode = resultCode;
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public BusinessException(ResultCode resultCode, Throwable cause) {
        super(resultCode.getMsg(), cause);
        this.resultCode = resultCode;
    }
}
