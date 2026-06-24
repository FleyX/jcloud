package com.fleyx.jcloud.common.exception;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.enums.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全局异常处理器单元测试。
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = mock(HttpServletRequest.class);
        when(request.getAttribute("traceId")).thenReturn("test-trace-id");
    }

    @Test
    void handleHttpMessageNotReadableExceptionShouldReturnParamError() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "Required request body is missing", (HttpInputMessage) null);

        R<Void> result = handler.handleHttpMessageNotReadableException(ex, request);

        assertNotNull(result);
        assertEquals(ResultCode.PARAM_ERROR.getCode(), result.getCode());
        assertEquals("请求体格式错误", result.getMsg());
        assertEquals("test-trace-id", result.getTraceId());
    }

    @Test
    void handleHttpMediaTypeNotSupportedExceptionShouldReturnParamError() {
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException("text/plain");

        R<Void> result = handler.handleHttpMediaTypeNotSupportedException(ex, request);

        assertNotNull(result);
        assertEquals(ResultCode.PARAM_ERROR.getCode(), result.getCode());
        assertEquals("请求内容类型不支持", result.getMsg());
    }

    @Test
    void handleHttpRequestMethodNotSupportedExceptionShouldReturnParamError() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("GET");

        R<Void> result = handler.handleHttpRequestMethodNotSupportedException(ex, request);

        assertNotNull(result);
        assertEquals(ResultCode.PARAM_ERROR.getCode(), result.getCode());
        assertEquals("请求方法不支持", result.getMsg());
    }

    @Test
    void handleMissingServletRequestParameterExceptionShouldReturnParamError() {
        MissingServletRequestParameterException ex = new MissingServletRequestParameterException("username", "String");

        R<Void> result = handler.handleMissingServletRequestParameterException(ex, request);

        assertNotNull(result);
        assertEquals(ResultCode.PARAM_ERROR.getCode(), result.getCode());
        assertEquals("缺少请求参数：username", result.getMsg());
    }

    @Test
    void handleMethodArgumentTypeMismatchExceptionShouldReturnParamError() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "id", null, new NumberFormatException("For input string: \"abc\""));

        R<Void> result = handler.handleMethodArgumentTypeMismatchException(ex, request);

        assertNotNull(result);
        assertEquals(ResultCode.PARAM_ERROR.getCode(), result.getCode());
        assertEquals("参数类型不匹配：id", result.getMsg());
    }
}
