package com.fleyx.jcloud.common.exception;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.enums.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
