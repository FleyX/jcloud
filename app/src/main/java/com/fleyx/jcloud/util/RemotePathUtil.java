package com.fleyx.jcloud.util;

import java.nio.charset.StandardCharsets;

/**
 * 远程路径工具。
 * <p>
 * 负责本地名称路径与远端存储路径之间的转换。
 */
public final class RemotePathUtil {

    /**
     * RFC 3986 中 path segment 允许出现的字符集合。
     * <p>
     * 包含 unreserved（{@code A-Za-z0-9-._~}）、sub-delims（{@code !$&'()*+,;=}
     * ）以及 {@code :} 和 {@code @}。
     */
    private static final String PATH_SEGMENT_ALLOWED =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@";

    private RemotePathUtil() {
    }

    /**
     * 对远端路径的每一段进行 RFC 3986 风格的 URL 编码，用于构造请求 URL 的 path 部分。
     * <p>
     * 与 {@link java.net.URLEncoder} 不同，该方法不会把空格编码为 {@code +}，
     * 也不会过度编码 path 中本可原样出现的字符（如 {@code ~}、{@code !}
     * 等），从而兼容标准 WebDAV 服务器对 path 的解析。
     *
     * @param path 原始路径
     * @return 编码后的路径
     */
    public static String encodePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "/";
        }
        String normalized = path.replace('\\', '/');
        String[] segments = normalized.split("/");
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            builder.append("/").append(encodeSegment(segment));
        }
        return builder.isEmpty() ? "/" : builder.toString();
    }

    private static String encodeSegment(String segment) {
        StringBuilder builder = new StringBuilder();
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int unsigned = b & 0xFF;
            char c = (char) unsigned;
            if (PATH_SEGMENT_ALLOWED.indexOf(c) >= 0) {
                builder.append(c);
            } else {
                builder.append(String.format("%%%02X", unsigned));
            }
        }
        return builder.toString();
    }

    /**
     * 规范化远端根路径。
     *
     * @param rootPath 原始根路径
     * @return 以 "/" 开头、不以 "/" 结尾的规范化路径；空路径返回空字符串
     */
    public static String normalizeRootPath(String rootPath) {
        if (rootPath == null || rootPath.isBlank()) {
            return "";
        }
        String normalized = rootPath.replace('\\', '/').trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 根据远端根路径和相对名称路径构建完整远端路径。
     *
     * @param rootPath         远端根路径
     * @param relativeNamePath 相对于挂载点的名称路径，如 "/docs/report.pdf"
     * @return 完整远端路径
     */
    public static String buildRemotePath(String rootPath, String relativeNamePath) {
        String root = normalizeRootPath(rootPath);
        String relative = FilePathUtil.stripLeadingSlash(relativeNamePath);
        if (root.isEmpty()) {
            return "/" + relative;
        }
        if (relative.isEmpty()) {
            return root;
        }
        return root + "/" + relative;
    }

    /**
     * 从完整名称路径中截取出相对于挂载点的名称路径。
     *
     * @param mountName    挂载点名称
     * @param fullNamePath 从虚拟根到节点的完整名称路径
     * @return 相对于挂载点的名称路径
     */
    public static String relativeNamePath(String mountName, String fullNamePath) {
        String prefix = FilePathUtil.buildPathName("/", mountName);
        if (fullNamePath == null || !fullNamePath.startsWith(prefix)) {
            return fullNamePath;
        }
        String relative = fullNamePath.substring(prefix.length());
        return relative.isEmpty() ? "/" : relative;
    }
}
