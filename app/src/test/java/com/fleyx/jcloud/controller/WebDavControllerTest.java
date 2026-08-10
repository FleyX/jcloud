package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.service.WebDavService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentCaptor.forClass;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebDAV 控制器单元测试（/dav/{userCode}/** 下任意 HTTP 方法统一委托 WebDavService）。
 */
class WebDavControllerTest {

    private final WebDavService webDavService = mock(WebDavService.class);

    private final WebDavController controller = new WebDavController(webDavService);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    /**
     * PROPFIND 请求：原请求对象（方法、URI、XML body）与响应对象原样委托给 service。
     */
    @Test
    void shouldDelegatePropfindToService() throws Exception {
        String body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getcontentlength/></d:prop></d:propfind>";

        mockMvc.perform(request(HttpMethod.valueOf("PROPFIND"), "/dav/alice/dir/file.txt")
                        .characterEncoding("UTF-8").content(body))
                .andExpect(status().isOk());

        var reqCaptor = forClass(HttpServletRequest.class);
        var resCaptor = forClass(HttpServletResponse.class);
        verify(webDavService).handle(eq("alice"), reqCaptor.capture(), resCaptor.capture());
        assertEquals("PROPFIND", reqCaptor.getValue().getMethod());
        assertEquals("/dav/alice/dir/file.txt", reqCaptor.getValue().getRequestURI());
        assertEquals(body, ((MockHttpServletRequest) reqCaptor.getValue()).getContentAsString());
        assertNotNull(resCaptor.getValue());
    }

    /**
     * LOCK 请求：委托 service，request body 与 userCode 原样透传。
     */
    @Test
    void shouldDelegateLockToService() throws Exception {
        String body = "<?xml version=\"1.0\"?><d:lockinfo xmlns:d=\"DAV:\"><d:lockscope><d:exclusive/></d:lockscope>"
                + "<d:locktype><d:write/></d:locktype></d:lockinfo>";

        mockMvc.perform(request(HttpMethod.valueOf("LOCK"), "/dav/alice/docs/report.docx")
                        .characterEncoding("UTF-8").content(body))
                .andExpect(status().isOk());

        var reqCaptor = forClass(HttpServletRequest.class);
        verify(webDavService).handle(eq("alice"), reqCaptor.capture(), any(HttpServletResponse.class));
        assertEquals("LOCK", reqCaptor.getValue().getMethod());
        assertEquals("/dav/alice/docs/report.docx", reqCaptor.getValue().getRequestURI());
        assertEquals(body, ((MockHttpServletRequest) reqCaptor.getValue()).getContentAsString());
    }

    /**
     * MKCOL 请求：委托 service，方法名与 URI 原样透传。
     */
    @Test
    void shouldDelegateMkcolToService() throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf("MKCOL"), "/dav/alice/newdir"))
                .andExpect(status().isOk());

        var reqCaptor = forClass(HttpServletRequest.class);
        verify(webDavService).handle(eq("alice"), reqCaptor.capture(), any(HttpServletResponse.class));
        assertEquals("MKCOL", reqCaptor.getValue().getMethod());
        assertEquals("/dav/alice/newdir", reqCaptor.getValue().getRequestURI());
    }

    /**
     * GET 请求：委托 service，userCode 按路径变量解析（此处验证不同用户编码）。
     */
    @Test
    void shouldDelegateGetToService() throws Exception {
        mockMvc.perform(get("/dav/bob/docs/a.txt"))
                .andExpect(status().isOk());

        var reqCaptor = forClass(HttpServletRequest.class);
        verify(webDavService).handle(eq("bob"), reqCaptor.capture(), any(HttpServletResponse.class));
        assertEquals("GET", reqCaptor.getValue().getMethod());
        assertEquals("/dav/bob/docs/a.txt", reqCaptor.getValue().getRequestURI());
    }
}
