package com.fleyx.jcloud.common.exception;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.enums.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常。
     */
    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常：traceId={}, msg={}", request.getAttribute(CommonConstant.TRACE_ID_MDC_KEY), e.getMessage());
        R<Void> result = R.fail(e.getResultCode(), e.getMessage());
        result.setTraceId(String.valueOf(request.getAttribute(CommonConstant.TRACE_ID_MDC_KEY)));
        return result;
    }

    /**
     * 处理参数校验异常（@RequestBody）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(","));
        return buildFailResult(ResultCode.PARAM_ERROR, msg, request);
    }

    /**
     * 处理参数绑定异常（@ModelAttribute）。
     */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e, HttpServletRequest request) {
        String msg = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(","));
        return buildFailResult(ResultCode.PARAM_ERROR, msg, request);
    }

    /**
     * 处理 @Validated 单参数校验异常。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public R<Void> handleConstraintViolationException(ConstraintViolationException e, HttpServletRequest request) {
        String msg = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ":" + v.getMessage())
                .collect(Collectors.joining(","));
        return buildFailResult(ResultCode.PARAM_ERROR, msg, request);
    }

    /**
     * 处理请求体缺失或无法解析异常。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleHttpMessageNotReadableException(HttpMessageNotReadableException e, HttpServletRequest request) {
        log.warn("请求体解析失败：traceId={}, msg={}", request.getAttribute(CommonConstant.TRACE_ID_MDC_KEY), e.getMessage());
        return buildFailResult(ResultCode.PARAM_ERROR, "请求体格式错误", request);
    }

    /**
     * 处理请求内容类型不支持异常。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public R<Void> handleHttpMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        log.warn("请求内容类型不支持：traceId={}, msg={}", request.getAttribute(CommonConstant.TRACE_ID_MDC_KEY), e.getMessage());
        return buildFailResult(ResultCode.PARAM_ERROR, "请求内容类型不支持", request);
    }

    /**
     * 处理请求方法不支持异常。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public R<Void> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        log.warn("请求方法不支持：traceId={}, msg={}", request.getAttribute(CommonConstant.TRACE_ID_MDC_KEY), e.getMessage());
        return buildFailResult(ResultCode.PARAM_ERROR, "请求方法不支持", request);
    }

    /**
     * 处理缺少请求参数异常。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<Void> handleMissingServletRequestParameterException(MissingServletRequestParameterException e, HttpServletRequest request) {
        log.warn("缺少请求参数：traceId={}, msg={}", request.getAttribute(CommonConstant.TRACE_ID_MDC_KEY), e.getMessage());
        return buildFailResult(ResultCode.PARAM_ERROR, "缺少请求参数：" + e.getParameterName(), request);
    }

    /**
     * 处理参数类型不匹配异常。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public R<Void> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        log.warn("参数类型不匹配：traceId={}, msg={}", request.getAttribute(CommonConstant.TRACE_ID_MDC_KEY), e.getMessage());
        return buildFailResult(ResultCode.PARAM_ERROR, "参数类型不匹配：" + e.getName(), request);
    }

    /**
     * 处理客户端主动断开连接异常（视频流拖拽进度、关闭页面等场景）。
     * 连接已断开，响应不可再写，仅降级记录 WARN，不写统一响应体。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException e, HttpServletRequest request) {
        log.warn("客户端连接中断：traceId={}, msg={}", request.getAttribute(CommonConstant.TRACE_ID_MDC_KEY), e.getMessage());
    }

    /**
     * 处理其他所有未知异常。
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常：traceId={}", request.getAttribute(CommonConstant.TRACE_ID_MDC_KEY), e);
        return buildFailResult(ResultCode.SYSTEM_ERROR, ResultCode.SYSTEM_ERROR.getMsg(), request);
    }

    private R<Void> buildFailResult(ResultCode resultCode, String msg, HttpServletRequest request) {
        R<Void> result = R.fail(resultCode, msg);
        result.setTraceId(String.valueOf(request.getAttribute(CommonConstant.TRACE_ID_MDC_KEY)));
        return result;
    }
}
