package com.fleyx.jcloud.filter;

import com.fleyx.jcloud.common.constant.CommonConstant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TraceIdFilter} 单元测试：验证 traceId 生成/透传、request attribute 与 MDC 一致性及 finally 清理。
 */
@ExtendWith(MockitoExtension.class)
class TraceIdFilterTest {

    private static final String UUID_PATTERN = "[0-9a-f]{32}";

    private final TraceIdFilter filter = new TraceIdFilter();

    private final Map<String, Object> requestAttributes = new HashMap<>();

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain chain;

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void shouldGenerateTraceIdWhenHeaderAbsent() throws Exception {
        stubAttributeStorage();
        when(request.getAttribute(CommonConstant.TRACE_ID_MDC_KEY))
                .thenAnswer(invocation -> requestAttributes.get(invocation.getArgument(0)));
        String[] mdcInChain = new String[1];
        String[] attrInChain = new String[1];
        doAnswer(invocation -> {
            mdcInChain[0] = MDC.get(CommonConstant.TRACE_ID_MDC_KEY);
            attrInChain[0] = (String) request.getAttribute(CommonConstant.TRACE_ID_MDC_KEY);
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        assertNotNull(mdcInChain[0]);
        assertTrue(mdcInChain[0].matches(UUID_PATTERN), "应生成 32 位十六进制 UUID，实际: " + mdcInChain[0]);
        assertEquals(mdcInChain[0], attrInChain[0]);
        assertNull(MDC.get(CommonConstant.TRACE_ID_MDC_KEY), "doFilter 返回后 MDC 应被清理");
    }

    @Test
    void shouldPassThroughTraceIdFromHeader() throws Exception {
        when(request.getHeader(CommonConstant.TRACE_ID_HEADER)).thenReturn("req-trace-123456");
        stubAttributeStorage();
        when(request.getAttribute(CommonConstant.TRACE_ID_MDC_KEY))
                .thenAnswer(invocation -> requestAttributes.get(invocation.getArgument(0)));
        String[] mdcInChain = new String[1];
        String[] attrInChain = new String[1];
        doAnswer(invocation -> {
            mdcInChain[0] = MDC.get(CommonConstant.TRACE_ID_MDC_KEY);
            attrInChain[0] = (String) request.getAttribute(CommonConstant.TRACE_ID_MDC_KEY);
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        assertEquals("req-trace-123456", mdcInChain[0], "应透传请求头中的 traceId");
        assertEquals("req-trace-123456", attrInChain[0]);
        assertNull(MDC.get(CommonConstant.TRACE_ID_MDC_KEY));
    }

    @Test
    void shouldGenerateNewTraceIdWhenHeaderBlank() throws Exception {
        when(request.getHeader(CommonConstant.TRACE_ID_HEADER)).thenReturn("   ");
        String[] mdcInChain = new String[1];
        doAnswer(invocation -> {
            mdcInChain[0] = MDC.get(CommonConstant.TRACE_ID_MDC_KEY);
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        assertTrue(mdcInChain[0].matches(UUID_PATTERN), "空白头应触发新 UUID，实际: " + mdcInChain[0]);
        assertNull(MDC.get(CommonConstant.TRACE_ID_MDC_KEY));
    }

    @Test
    void shouldPropagateChainExceptionAndCleanMdc() throws Exception {
        when(request.getHeader(CommonConstant.TRACE_ID_HEADER)).thenReturn(null);
        doThrow(new ServletException("chain failure")).when(chain).doFilter(request, response);

        assertThrows(ServletException.class, () -> filter.doFilter(request, response, chain));

        assertNull(MDC.get(CommonConstant.TRACE_ID_MDC_KEY), "异常时 finally 也应清理 MDC");
    }

    @Test
    void shouldGenerateTraceIdForNonHttpRequest() throws Exception {
        ServletRequest plainRequest = mock(ServletRequest.class);
        String[] mdcInChain = new String[1];
        doAnswer(invocation -> {
            mdcInChain[0] = MDC.get(CommonConstant.TRACE_ID_MDC_KEY);
            return null;
        }).when(chain).doFilter(plainRequest, response);

        filter.doFilter(plainRequest, response, chain);

        assertNotNull(mdcInChain[0]);
        assertTrue(mdcInChain[0].matches(UUID_PATTERN), "非 HTTP 请求应生成新 UUID，实际: " + mdcInChain[0]);
        assertNull(MDC.get(CommonConstant.TRACE_ID_MDC_KEY));
    }

    /**
     * 让 request.setAttribute 具备存储语义，供 chain 内通过 getAttribute 回读。
     */
    private void stubAttributeStorage() {
        doAnswer(invocation -> {
            requestAttributes.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(request).setAttribute(anyString(), any());
    }
}
