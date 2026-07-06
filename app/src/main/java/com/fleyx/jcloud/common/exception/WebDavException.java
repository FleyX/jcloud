package com.fleyx.jcloud.common.exception;

import lombok.Getter;

/**
 * WebDAV 处理异常，携带 HTTP 状态码。
 */
@Getter
public class WebDavException extends RuntimeException {

    private final int statusCode;

    public WebDavException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }
}
