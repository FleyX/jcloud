package com.fleyx.jcloud.util;

import com.fleyx.jcloud.model.po.FileNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * WebDAV PROPFIND 响应 XML 构建工具。
 */
@Slf4j
public final class WebDavPropFindBuilder {

    private static final String DAV_NS = "DAV:";
    private static final String NS_PREFIX = "D";
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            .withZone(ZoneId.of("GMT"));

    private WebDavPropFindBuilder() {
    }

    /**
     * 构建 PROPFIND 响应 XML。
     *
     * @param requestUrl  请求 URL（用于构建 href）
     * @param nodes       节点列表（第一个为当前资源，后续为子资源）
     * @param requestPath 请求路径前缀（解码后的路径）
     * @return XML 字符串
     */
    public static String build(String requestUrl, List<FileNode> nodes, String requestPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<").append(NS_PREFIX).append(":multistatus xmlns:")
                .append(NS_PREFIX).append("=\"").append(DAV_NS).append("\">\n");
        boolean first = true;
        for (FileNode node : nodes) {
            appendResponse(sb, requestUrl, requestPath, node, first);
            first = false;
        }
        sb.append("</").append(NS_PREFIX).append(":multistatus>\n");
        return sb.toString();
    }

    private static void appendResponse(StringBuilder sb, String requestUrl,
                                       String requestPath, FileNode node, boolean isSelf) {
        sb.append("  <").append(NS_PREFIX).append(":response>\n");
        sb.append("    <").append(NS_PREFIX).append(":href>");
        escapeXml(sb, buildHref(requestUrl, requestPath, node, isSelf));
        sb.append("</").append(NS_PREFIX).append(":href>\n");
        sb.append("    <").append(NS_PREFIX).append(":propstat>\n");
        sb.append("      <").append(NS_PREFIX).append(":prop>\n");
        appendProps(sb, node);
        sb.append("      </").append(NS_PREFIX).append(":prop>\n");
        sb.append("      <").append(NS_PREFIX).append(":status>HTTP/1.1 200 OK</")
                .append(NS_PREFIX).append(":status>\n");
        sb.append("    </").append(NS_PREFIX).append(":propstat>\n");
        sb.append("  </").append(NS_PREFIX).append(":response>\n");
    }

    private static void appendProps(StringBuilder sb, FileNode node) {
        sb.append("        <").append(NS_PREFIX).append(":displayname>");
        escapeXml(sb, node.getName());
        sb.append("</").append(NS_PREFIX).append(":displayname>\n");

        boolean isFolder = "folder".equals(node.getType());
        if (isFolder) {
            sb.append("        <").append(NS_PREFIX).append(":resourcetype><")
                    .append(NS_PREFIX).append(":collection/></")
                    .append(NS_PREFIX).append(":resourcetype>\n");
        } else {
            sb.append("        <").append(NS_PREFIX).append(":resourcetype/>\n");
            sb.append("        <").append(NS_PREFIX).append(":getcontentlength>");
            sb.append(node.getSize() == null ? 0 : node.getSize());
            sb.append("</").append(NS_PREFIX).append(":getcontentlength>\n");
            sb.append("        <").append(NS_PREFIX).append(":getcontenttype>");
            escapeXml(sb, contentType(node));
            sb.append("</").append(NS_PREFIX).append(":getcontenttype>\n");
            sb.append("        <").append(NS_PREFIX).append(":getetag>\"");
            sb.append(etag(node));
            sb.append("\"</").append(NS_PREFIX).append(":getetag>\n");
        }

        sb.append("        <").append(NS_PREFIX).append(":getlastmodified>");
        sb.append(formatHttpDate(node.getLastModified()));
        sb.append("</").append(NS_PREFIX).append(":getlastmodified>\n");

        sb.append("        <").append(NS_PREFIX).append(":creationdate>");
        sb.append(formatIsoDate(node.getCreateTime() == null ? null : node.getCreateTime().toInstant(ZoneId.systemDefault().getRules().getOffset(Instant.now()))));
        sb.append("</").append(NS_PREFIX).append(":creationdate>\n");

        sb.append("        <").append(NS_PREFIX).append(":supportedlock>\n");
        sb.append("          <").append(NS_PREFIX).append(":lockentry>\n");
        sb.append("            <").append(NS_PREFIX).append(":lockscope><")
                .append(NS_PREFIX).append(":exclusive/></")
                .append(NS_PREFIX).append(":lockscope>\n");
        sb.append("            <").append(NS_PREFIX).append(":locktype><")
                .append(NS_PREFIX).append(":write/></")
                .append(NS_PREFIX).append(":locktype>\n");
        sb.append("          </").append(NS_PREFIX).append(":lockentry>\n");
        sb.append("        </").append(NS_PREFIX).append(":supportedlock>\n");
    }

    private static String buildHref(String requestUrl, String requestPath, FileNode node, boolean isSelf) {
        StringBuilder href = new StringBuilder();
        href.append(requestUrl);
        if (!requestUrl.endsWith("/")) {
            href.append('/');
        }
        if (!isSelf && node.getName() != null) {
            escapeUri(href, node.getName());
        }
        if ("folder".equals(node.getType()) && href.charAt(href.length() - 1) != '/') {
            href.append('/');
        }
        return href.toString();
    }

    private static String contentType(FileNode node) {
        if (node.getMimeType() != null && !node.getMimeType().isBlank()) {
            return node.getMimeType();
        }
        try {
            String probe = Files.probeContentType(Path.of(node.getName() == null ? "" : node.getName()));
            if (probe != null) {
                return probe;
            }
        } catch (Exception e) {
            log.debug("探测 MIME 类型失败: {}", node.getName(), e);
        }
        return "application/octet-stream";
    }

    private static String etag(FileNode node) {
        String hash = node.getHash();
        Long lastModified = node.getLastModified();
        if (hash == null) {
            hash = "0";
        }
        if (lastModified == null) {
            return hash;
        }
        return hash + "-" + lastModified;
    }

    private static String formatHttpDate(Long millis) {
        if (millis == null) {
            return HTTP_DATE.format(Instant.EPOCH);
        }
        return HTTP_DATE.format(Instant.ofEpochMilli(millis));
    }

    private static String formatIsoDate(Instant instant) {
        if (instant == null) {
            return "1970-01-01T00:00:00Z";
        }
        return instant.toString();
    }

    private static void escapeXml(StringBuilder sb, String value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
    }

    private static void escapeUri(StringBuilder sb, String value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x80 || c == ' ' || c == '%' || c == '<' || c == '>' || c == '#' || c == '?') {
                byte[] bytes = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append(String.format("%%%02X", b & 0xff));
                }
            } else {
                sb.append(c);
            }
        }
    }
}
