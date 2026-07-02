package com.fleyx.jcloud.util;

/**
 * 远程路径工具。
 * <p>
 * 负责本地名称路径与远端存储路径之间的转换。
 */
public final class RemotePathUtil {

    private RemotePathUtil() {
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
