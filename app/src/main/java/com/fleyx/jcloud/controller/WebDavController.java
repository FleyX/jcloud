package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.service.WebDavService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WebDAV 服务端点控制器。
 * <p>
 * 捕获 /dav/{userCode}/** 下的所有 HTTP 方法，统一交给 WebDavService 处理。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WebDavController {

    private final WebDavService webDavService;

    /**
     * 处理所有 WebDAV 请求。
     *
     * @param userCode 用户编码
     * @param request  HTTP 请求
     * @param response HTTP 响应
     */
    @RequestMapping(value = "/dav/{userCode}/**")
    public void handle(@PathVariable String userCode, HttpServletRequest request, HttpServletResponse response) {
        webDavService.handle(userCode, request, response);
    }
}
