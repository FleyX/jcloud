package com.fleyx.jcloud.filter;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.common.constant.CommonConstant;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

import java.io.IOException;

/**
 * 为每次请求生成或透传 traceId，用于全链路日志排查。
 */
@WebFilter(urlPatterns = "/*")
public class TraceIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            String traceId = extractTraceId(request);
            MDC.put(CommonConstant.TRACE_ID_MDC_KEY, traceId);
            request.setAttribute(CommonConstant.TRACE_ID_MDC_KEY, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CommonConstant.TRACE_ID_MDC_KEY);
        }
    }

    private String extractTraceId(ServletRequest request) {
        if (request instanceof HttpServletRequest httpRequest) {
            String headerTraceId = httpRequest.getHeader(CommonConstant.TRACE_ID_HEADER);
            if (StrUtil.isNotBlank(headerTraceId)) {
                return headerTraceId;
            }
        }
        return IdUtil.fastSimpleUUID();
    }
}
