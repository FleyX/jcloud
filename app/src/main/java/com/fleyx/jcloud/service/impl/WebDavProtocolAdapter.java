package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.model.bo.RemoteFileEntry;
import com.fleyx.jcloud.model.bo.WebDavConfig;
import com.fleyx.jcloud.service.RemoteProtocolAdapter;
import com.fleyx.jcloud.util.RemotePathUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * WebDAV 协议适配器实现。
 * <p>
 * 使用 JDK HttpClient 避免虚拟线程 pinning；首期仅支持 Basic 认证。
 * PROPFIND 响应解析委托给 {@link WebDavPropFindParser}。
 */
@Slf4j
public class WebDavProtocolAdapter implements RemoteProtocolAdapter {

    private static final String HEADER_DEPTH = "Depth";
    private static final String HEADER_DESTINATION = "Destination";
    private static final int STATUS_MULTI_STATUS = 207;
    private static final int STATUS_NOT_FOUND = 404;

    private final String baseUrl;
    private final String authorizationHeader;
    private final HttpClient httpClient;
    private final WebDavPropFindParser propFindParser;

    public WebDavProtocolAdapter(WebDavConfig config) {
        this.baseUrl = normalizeBaseUrl(config.getUrl());
        this.authorizationHeader = buildAuthorization(config.getUsername(), config.getPassword());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        this.propFindParser = new WebDavPropFindParser(extractServerBasePath(this.baseUrl));
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
            return propFindParser.parse(body, remotePath);
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
        HttpRequest.BodyPublisher bodyPublisher = buildUploadBodyPublisher(inputStream, size);
        HttpRequest.Builder builder = newRequest(remotePath).PUT(bodyPublisher);
        if (mimeType != null && !mimeType.isBlank()) {
            builder.header("Content-Type", mimeType);
        }
        HttpResponse<InputStream> response = send(builder.build());
        close(response.body());
        if (response.statusCode() >= 300) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "WebDAV 上传失败: " + response.statusCode());
        }
    }

    private HttpRequest.BodyPublisher buildUploadBodyPublisher(InputStream inputStream, long size) {
        HttpRequest.BodyPublisher streamPublisher = HttpRequest.BodyPublishers.ofInputStream(() -> inputStream);
        if (size >= 0) {
            return HttpRequest.BodyPublishers.fromPublisher(streamPublisher, size);
        }
        return streamPublisher;
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
        String encodedPath = RemotePathUtil.encodePath(remotePath);
        return baseUrl + encodedPath;
    }

    private String extractServerBasePath(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null ? "" : path;
        } catch (Exception e) {
            log.warn("解析 WebDAV URL 路径失败: {}", url);
            return "";
        }
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
