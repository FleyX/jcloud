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
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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

    @Test
    void handleBusinessExceptionShouldReturnMappedCodeAndMsg() {
        BusinessException ex = new BusinessException(ResultCode.FORBIDDEN);

        R<Void> result = handler.handleBusinessException(ex, request);

        assertNotNull(result);
        assertEquals(ResultCode.FORBIDDEN.getCode(), result.getCode());
        assertEquals(ResultCode.FORBIDDEN.getMsg(), result.getMsg());
        assertEquals("test-trace-id", result.getTraceId());
    }

    @Test
    void handleBusinessExceptionWithoutResultCodeShouldReturnBusinessError() {
        BusinessException ex = new BusinessException("文件重名");

        R<Void> result = handler.handleBusinessException(ex, request);

        assertNotNull(result);
        assertEquals(ResultCode.BUSINESS_ERROR.getCode(), result.getCode());
        assertEquals("文件重名", result.getMsg());
    }

    @Test
    void handleBusinessExceptionWithCauseShouldReturnMappedCodeAndMsg() {
        BusinessException ex = new BusinessException(ResultCode.SYSTEM_ERROR, new IllegalStateException("root cause"));

        R<Void> result = handler.handleBusinessException(ex, request);

        assertNotNull(result);
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), result.getCode());
        assertEquals(ResultCode.SYSTEM_ERROR.getMsg(), result.getMsg());
        assertEquals("test-trace-id", result.getTraceId());
        assertNotNull(ex.getCause());
    }

    @Test
    void handleAsyncRequestNotUsableExceptionShouldNotWriteResponseBody() {
        AsyncRequestNotUsableException ex = new AsyncRequestNotUsableException(
                "ServletOutputStream failed to write", new java.io.IOException("连接被对方重置"));

        // 客户端已断连，处理器只记录日志、返回 void，不应再尝试写统一响应体
        assertDoesNotThrow(() -> handler.handleAsyncRequestNotUsableException(ex, request));
    }

    @Test
    void handleExceptionShouldReturnSystemErrorForUnknownException() {
        Exception ex = new IllegalStateException("unexpected");

        R<Void> result = handler.handleException(ex, request);

        assertNotNull(result);
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), result.getCode());
        assertEquals(ResultCode.SYSTEM_ERROR.getMsg(), result.getMsg());
        assertEquals("test-trace-id", result.getTraceId());
    }

    @Test
    void handleSystemExceptionShouldFallbackToSystemError() {
        SystemException ex = new SystemException(
                ResultCode.SYSTEM_ERROR, "数据库连接失败", new IllegalStateException("connection refused"));

        R<Void> result = handler.handleException(ex, request);

        assertNotNull(result);
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), result.getCode());
        assertEquals(ResultCode.SYSTEM_ERROR.getMsg(), result.getMsg());
        assertEquals("test-trace-id", result.getTraceId());
        assertNotNull(ex.getCause());
    }
}
