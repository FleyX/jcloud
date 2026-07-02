package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.model.bo.RemoteFileEntry;
import com.fleyx.jcloud.model.bo.WebDavConfig;
import com.fleyx.jcloud.service.RemoteProtocolAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * WebDAV 协议适配器实现。
 * <p>
 * 使用 JDK HttpClient 避免虚拟线程 pinning；首期仅支持 Basic 认证。
 */
@Slf4j
public class WebDavProtocolAdapter implements RemoteProtocolAdapter {

    private static final String DAV_NS = "DAV:";
    private static final String HEADER_DEPTH = "Depth";
    private static final String HEADER_DESTINATION = "Destination";
    private static final int STATUS_MULTI_STATUS = 207;
    private static final int STATUS_NOT_FOUND = 404;

    private final String baseUrl;
    private final String authorizationHeader;
    private final HttpClient httpClient;
    private final DocumentBuilder documentBuilder;

    public WebDavProtocolAdapter(WebDavConfig config) {
        this.baseUrl = normalizeBaseUrl(config.getUrl());
        this.authorizationHeader = buildAuthorization(config.getUsername(), config.getPassword());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            this.documentBuilder = factory.newDocumentBuilder();
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "初始化 WebDAV XML 解析器失败", e);
        }
    }

    @Override
    public List<RemoteFileEntry> listChildren(String remotePath) {
        HttpRequest request = newRequest(remotePath)
                .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
                .header(HEADER_DEPTH, "1")
                .header("Content-Type", "text/xml; charset=UTF-8")
                .build();
        HttpResponse<InputStream> response = send(request);
        if (response.statusCode() == STATUS_NOT_FOUND) {
            close(response.body());
            return List.of();
        }
        if (response.statusCode() != STATUS_MULTI_STATUS) {
            close(response.body());
            throw new SystemException(ResultCode.SYSTEM_ERROR, "WebDAV PROPFIND 失败: " + response.statusCode());
        }
        try (InputStream body = response.body()) {
            Document document = documentBuilder.parse(body);
            return parsePropFind(document, remotePath);
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "解析 WebDAV PROPFIND 响应失败", e);
        }
    }

    @Override
    public InputStream download(String remotePath) {
        HttpRequest request = newRequest(remotePath)
                .GET()
                .build();
        HttpResponse<InputStream> response = send(request);
        if (response.statusCode() == STATUS_NOT_FOUND) {
            close(response.body());
            throw new BusinessException(ResultCode.NOT_FOUND, "远程文件不存在");
        }
        if (response.statusCode() >= 300) {
            close(response.body());
            throw new SystemException(ResultCode.SYSTEM_ERROR, "WebDAV 下载失败: " + response.statusCode());
        }
        return response.body();
    }

    @Override
    public void upload(String remotePath, InputStream inputStream, long size, String mimeType) {
        HttpRequest.Builder builder = newRequest(remotePath)
                .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> inputStream));
        if (mimeType != null && !mimeType.isBlank()) {
            builder.header("Content-Type", mimeType);
        }
        HttpResponse<InputStream> response = send(builder.build());
        close(response.body());
        if (response.statusCode() >= 300) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "WebDAV 上传失败: " + response.statusCode());
        }
    }

    @Override
    public void delete(String remotePath) {
        HttpRequest request = newRequest(remotePath)
                .method("DELETE", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<InputStream> response = send(request);
        close(response.body());
        if (response.statusCode() >= 300 && response.statusCode() != STATUS_NOT_FOUND) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "WebDAV 删除失败: " + response.statusCode());
        }
    }

    @Override
    public void move(String oldRemotePath, String newRemotePath) {
        HttpRequest request = newRequest(oldRemotePath)
                .method("MOVE", HttpRequest.BodyPublishers.noBody())
                .header(HEADER_DESTINATION, buildFullUrl(newRemotePath))
                .build();
        HttpResponse<InputStream> response = send(request);
        close(response.body());
        if (response.statusCode() >= 300) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "WebDAV 移动失败: " + response.statusCode());
        }
    }

    @Override
    public void createFolder(String remotePath) {
        HttpRequest request = newRequest(remotePath)
                .method("MKCOL", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<InputStream> response = send(request);
        close(response.body());
        if (response.statusCode() >= 300) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "WebDAV 创建文件夹失败: " + response.statusCode());
        }
    }

    @Override
    public boolean exists(String remotePath) {
        HttpRequest request = newRequest(remotePath)
                .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
                .header(HEADER_DEPTH, "0")
                .build();
        HttpResponse<InputStream> response = send(request);
        close(response.body());
        return response.statusCode() == STATUS_MULTI_STATUS;
    }

    private HttpRequest.Builder newRequest(String remotePath) {
        return HttpRequest.newBuilder()
                .uri(URI.create(buildFullUrl(remotePath)))
                .header("Authorization", authorizationHeader);
    }

    private String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "WebDAV URL 不能为空");
        }
        String normalized = url.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String buildFullUrl(String remotePath) {
        String encodedPath = encodePath(remotePath);
        return baseUrl + encodedPath;
    }

    private String encodePath(String path) {
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
            builder.append("/").append(URLEncoder.encode(segment, StandardCharsets.UTF_8));
        }
        return builder.isEmpty() ? "/" : builder.toString();
    }

    private String buildAuthorization(String username, String password) {
        String credentials = (username == null ? "" : username) + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<InputStream> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "WebDAV 请求失败", e);
        }
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
            String path = decodePath(href);
            if (isSamePath(path, normalizedParent)) {
                continue;
            }
            RemoteFileEntry entry = new RemoteFileEntry();
            entry.setRemotePath(path);
            entry.setName(extractName(path));
            Element propStat = getPropStat(response);
            if (propStat != null) {
                fillProperties(entry, propStat);
            }
            entries.add(entry);
        }
        return entries;
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
            java.net.URI uri = new URI(href);
            path = uri.getPath();
            if (path == null) {
                path = href;
            }
        } catch (Exception e) {
            log.debug("解码 WebDAV href 失败: {}", href);
        }
        return java.net.URLDecoder.decode(path, StandardCharsets.UTF_8);
    }

    private void close(InputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (Exception e) {
            log.debug("关闭 WebDAV 响应流失败", e);
        }
    }
}
