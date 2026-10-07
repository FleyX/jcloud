package com.fleyx.jcloud.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ClientIpUtil 纯单元测试：XFF 单值/多值取最左、缺失与空值回退直连 IP。
 */
class ClientIpUtilTest {

    private MockHttpServletRequest request(String remoteAddr, String xForwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (xForwardedFor != null) {
            request.addHeader("X-Forwarded-For", xForwardedFor);
        }
        return request;
    }

    @Test
    void shouldReturnSingleXffValue() {
        MockHttpServletRequest request = request("10.0.0.1", "203.0.113.1");
        assertEquals("203.0.113.1", ClientIpUtil.resolve(request));
    }

    @Test
    void shouldReturnLeftmostXffValue() {
        MockHttpServletRequest request = request("10.0.0.1", "203.0.113.1, 10.1.1.1, 10.2.2.2");
        assertEquals("203.0.113.1", ClientIpUtil.resolve(request));
    }

    @Test
    void shouldTrimLeftmostXffValue() {
        MockHttpServletRequest request = request("10.0.0.1", "  198.51.100.7 , 10.1.1.1");
        assertEquals("198.51.100.7", ClientIpUtil.resolve(request));
    }

    @Test
    void shouldSkipBlankLeadingXffSegments() {
        MockHttpServletRequest request = request("10.0.0.1", " , ,192.0.2.3");
        assertEquals("192.0.2.3", ClientIpUtil.resolve(request));
    }

    @Test
    void shouldFallbackToRemoteAddrWhenXffMissing() {
        MockHttpServletRequest request = request("172.16.0.9", null);
        assertEquals("172.16.0.9", ClientIpUtil.resolve(request));
    }

    @Test
    void shouldFallbackToRemoteAddrWhenXffBlank() {
        MockHttpServletRequest request = request("172.16.0.9", "   ");
        assertEquals("172.16.0.9", ClientIpUtil.resolve(request));
    }

    @Test
    void shouldFallbackToRemoteAddrWhenXffAllSegmentsBlank() {
        MockHttpServletRequest request = request("172.16.0.9", " , , ");
        assertEquals("172.16.0.9", ClientIpUtil.resolve(request));
    }
}
