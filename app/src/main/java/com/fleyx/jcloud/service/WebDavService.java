package com.fleyx.jcloud.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * WebDAV 请求处理服务。
 */
public interface WebDavService {

    /**
     * 处理 WebDAV 请求。
     *
     * @param userCode 请求路径中的用户编码
     * @param request  HTTP 请求
     * @param response HTTP 响应
     */
    void handle(String userCode, HttpServletRequest request, HttpServletResponse response);
}
