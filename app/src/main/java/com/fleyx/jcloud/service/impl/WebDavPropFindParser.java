package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.model.bo.RemoteFileEntry;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * WebDAV PROPFIND 响应解析器。
 * <p>
 * 将 Multi-Status XML 响应解析为远程文件条目，并处理 href 到远端相对路径的转换。
 * 非线程安全（{@link DocumentBuilder} 限制），随适配器实例一次性使用。
 */
@Slf4j
class WebDavPropFindParser {

    private static final String DAV_NS = "DAV:";

    private final String serverBasePath;
    private final DocumentBuilder documentBuilder;

    WebDavPropFindParser(String serverBasePath) {
        this.serverBasePath = serverBasePath;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            this.documentBuilder = factory.newDocumentBuilder();
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "初始化 WebDAV XML 解析器失败", e);
        }
    }

    /**
     * 解析 PROPFIND 响应体。
     *
     * @param body             响应体流
     * @param parentRemotePath 被列举的父目录远端路径
     * @return 子条目列表（不含父目录自身）
     * @throws Exception XML 解析失败时抛出
     */
    List<RemoteFileEntry> parse(InputStream body, String parentRemotePath) throws Exception {
        Document document = documentBuilder.parse(body);
        return parsePropFind(document, parentRemotePath);
    }

    private List<RemoteFileEntry> parsePropFind(Document document, String parentRemotePath) {
        String normalizedParent = normalizeRemotePath(parentRemotePath);
        NodeList responses = document.getElementsByTagNameNS(DAV_NS, "response");
        List<RemoteFileEntry> entries = new ArrayList<>();
        for (int i = 0; i < responses.getLength(); i++) {
            Element response = (Element) responses.item(i);
            String href = getHref(response);
            if (href == null) {
                continue;
            }
            String remotePath = toRemotePath(href);
            if (isSamePath(remotePath, normalizedParent)) {
                continue;
            }
            RemoteFileEntry entry = new RemoteFileEntry();
            entry.setRemotePath(remotePath);
            entry.setName(extractName(remotePath));
            Element propStat = getPropStat(response);
            if (propStat != null) {
                fillProperties(entry, propStat);
            }
            entries.add(entry);
        }
        return entries;
    }

    String toRemotePath(String href) {
        String path = normalizeRemotePath(decodePath(href));
        return normalizeRemotePath(stripServerBasePath(path));
    }

    private String stripServerBasePath(String path) {
        if (serverBasePath == null || serverBasePath.isEmpty() || "/".equals(serverBasePath)) {
            return path;
        }
        String normalizedBase = normalizeRemotePath(serverBasePath);
        String normalizedPath = normalizeRemotePath(path);
        if (normalizedPath.equals(normalizedBase)) {
            return "/";
        }
        if (normalizedPath.length() > normalizedBase.length()
                && normalizedPath.startsWith(normalizedBase)
                && normalizedPath.charAt(normalizedBase.length()) == '/') {
            return normalizedPath.substring(normalizedBase.length());
        }
        return path;
    }

    private String getHref(Element response) {
        NodeList hrefNodes = response.getElementsByTagNameNS(DAV_NS, "href");
        if (hrefNodes.getLength() == 0) {
            return null;
        }
        return hrefNodes.item(0).getTextContent();
    }

    private Element getPropStat(Element response) {
        NodeList propStats = response.getElementsByTagNameNS(DAV_NS, "propstat");
        for (int i = 0; i < propStats.getLength(); i++) {
            Element propStat = (Element) propStats.item(i);
            NodeList statuses = propStat.getElementsByTagNameNS(DAV_NS, "status");
            if (statuses.getLength() > 0) {
                String status = statuses.item(0).getTextContent();
                if (status.contains("200")) {
                    return propStat;
                }
            }
        }
        return propStats.getLength() > 0 ? (Element) propStats.item(0) : null;
    }

    private void fillProperties(RemoteFileEntry entry, Element propStat) {
        NodeList props = propStat.getElementsByTagNameNS(DAV_NS, "prop");
        if (props.getLength() == 0) {
            return;
        }
        Element prop = (Element) props.item(0);
        entry.setFolder(hasChild(prop, "resourcetype", "collection"));
        String contentLength = getTextContent(prop, "getcontentlength");
        if (contentLength != null && !contentLength.isBlank()) {
            try {
                entry.setSize(Long.parseLong(contentLength.trim()));
            } catch (NumberFormatException e) {
                log.warn("解析 WebDAV 文件大小失败: {}", contentLength);
            }
        }
        String lastModified = getTextContent(prop, "getlastmodified");
        if (lastModified != null && !lastModified.isBlank()) {
            entry.setLastModified(parseHttpDate(lastModified.trim()));
        }
        String etag = getTextContent(prop, "getetag");
        if (etag != null) {
            entry.setEtag(stripQuotes(etag));
        }
    }

    private boolean hasChild(Element parent, String parentTag, String childTag) {
        NodeList parents = parent.getElementsByTagNameNS(DAV_NS, parentTag);
        if (parents.getLength() == 0) {
            return false;
        }
        NodeList children = ((Element) parents.item(0)).getElementsByTagNameNS(DAV_NS, childTag);
        return children.getLength() > 0;
    }

    private String getTextContent(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagNameNS(DAV_NS, tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return nodes.item(0).getTextContent();
    }

    private long parseHttpDate(String date) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneId.of("GMT"));
            return Instant.from(formatter.parse(date)).toEpochMilli();
        } catch (Exception e) {
            log.warn("解析 WebDAV 日期失败: {}", date);
            return 0L;
        }
    }

    private String stripQuotes(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String normalizeRemotePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.replace('\\', '/');
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isSamePath(String path1, String path2) {
        return normalizeRemotePath(path1).equals(normalizeRemotePath(path2));
    }

    private String extractName(String remotePath) {
        String normalized = normalizeRemotePath(remotePath);
        int idx = normalized.lastIndexOf('/');
        if (idx <= 0) {
            return normalized.substring(1);
        }
        return normalized.substring(idx + 1);
    }

    private String decodePath(String href) {
        String path = href;
        try {
            URI uri = new URI(href);
            String uriPath = uri.getPath();
            if (uriPath != null) {
                path = uriPath;
            }
        } catch (Exception e) {
            log.debug("解码 WebDAV href 失败: {}", href);
        }
        String decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
        if (!decoded.startsWith("/")) {
            decoded = "/" + decoded;
        }
        return decoded;
    }
}
