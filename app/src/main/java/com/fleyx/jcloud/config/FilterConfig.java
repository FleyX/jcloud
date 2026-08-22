package com.fleyx.jcloud.config;

import tools.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.cache.UserPermissionCache;
import com.fleyx.jcloud.common.permission.PermissionRegistry;
import com.fleyx.jcloud.common.permission.PermissionResolver;
import com.fleyx.jcloud.filter.AuthTokenFilter;
import com.fleyx.jcloud.filter.TraceIdFilter;
import com.fleyx.jcloud.filter.WebDavAuthFilter;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.service.support.AuthBlacklistSupport;
import com.fleyx.jcloud.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 过滤器配置。
 */
@Configuration
@RequiredArgsConstructor
public class FilterConfig {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final PermissionRegistry permissionRegistry;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserPermissionCache userPermissionCache;
    private final PermissionResolver permissionResolver;
    private final AuthBlacklistSupport authBlacklistSupport;

    /**
     * 注册链路追踪 ID 过滤器。
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TraceIdFilter());
        registration.addUrlPatterns("/*");
        registration.setName("traceIdFilter");
        registration.setOrder(1);
        return registration;
    }

    /**
     * 注册 Token 认证过滤器。
     */
    @Bean
    public FilterRegistrationBean<AuthTokenFilter> authTokenFilterRegistration() {
        FilterRegistrationBean<AuthTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AuthTokenFilter(jwtUtil, objectMapper, permissionRegistry,
                userMapper, userRoleMapper, userPermissionCache, permissionResolver,
                authBlacklistSupport));
        registration.addUrlPatterns("/jcloud/api/*");
        registration.setName("authTokenFilter");
        registration.setOrder(2);
        return registration;
    }

    /**
     * 注册 WebDAV 认证过滤器。
     */
    @Bean
    public FilterRegistrationBean<WebDavAuthFilter> webDavAuthFilterRegistration() {
        FilterRegistrationBean<WebDavAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new WebDavAuthFilter(userMapper));
        registration.addUrlPatterns("/dav/*");
        registration.setName("webDavAuthFilter");
        registration.setOrder(3);
        return registration;
    }
}
