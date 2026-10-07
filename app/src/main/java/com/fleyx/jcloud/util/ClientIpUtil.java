package com.fleyx.jcloud.util;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 真实客户端 IP 解析工具（全项目唯一入口）。
 * <p>
 * 部署拓扑为外部代理卸载 TLS → 内部 Caddy → 后端（两级反代），
 * 信任 {@code X-Forwarded-For} 最左端非空地址；头缺失或全空时回退直连地址。
 */
public final class ClientIpUtil {

    /**
     * 代理链头部。
     */
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private ClientIpUtil() {
    }

    /**
     * 解析请求的真实客户端 IP。
     *
     * @param request HTTP 请求
     * @return 最左端非空的 X-Forwarded-For 值；缺失或全空时回退 {@code request.getRemoteAddr()}
     */
    public static String resolve(HttpServletRequest request) {
        String forwardedFor = request.getHeader(X_FORWARDED_FOR);
        if (StrUtil.isNotBlank(forwardedFor)) {
            for (String segment : forwardedFor.split(",")) {
                String candidate = segment.trim();
                if (StrUtil.isNotBlank(candidate)) {
                    return candidate;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
