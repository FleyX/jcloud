package com.fleyx.jcloud.service.support;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * HLS 播放列表 token 重写支撑工具（纯静态无状态）。
 * <p>
 * 播放列表 URL 携带的 ?token= 在切片相对地址解析时会丢失，导致切片请求 401；
 * 服务端返回 m3u8 前重写其中的相对 URI（含 #EXT-X-MAP 的 URI 属性），
 * 使 hls.js 与 Safari 原生 HLS 的切片请求都能携带凭证。
 */
public final class TranscodePlaylistSupport {

    private TranscodePlaylistSupport() {
    }

    /**
     * 给 HLS 播放列表中的相对切片/初始化段 URI 追加 token 查询参数。
     *
     * @param content 播放列表文本
     * @param token   访问令牌，为空时原样返回
     * @return 重写后的播放列表文本
     */
    public static String appendTokenToPlaylist(String content, String token) {
        if (content == null || token == null || token.isBlank()) {
            return content;
        }
        String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = rewritePlaylistLine(lines[i], encoded);
        }
        return String.join("\n", lines);
    }

    private static String rewritePlaylistLine(String line, String encodedToken) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return line;
        }
        if (trimmed.startsWith("#")) {
            int uriStart = line.indexOf("URI=\"");
            if (!trimmed.startsWith("#EXT-X-MAP:") || uriStart < 0) {
                return line;
            }
            int start = uriStart + 5;
            int end = line.indexOf('"', start);
            if (end < 0) {
                return line;
            }
            String uri = line.substring(start, end);
            if (uri.contains("://") || uri.contains("?")) {
                return line;
            }
            return line.substring(0, start) + uri + "?token=" + encodedToken + line.substring(end);
        }
        if (trimmed.contains("://") || trimmed.contains("?")) {
            return line;
        }
        return line + "?token=" + encodedToken;
    }
}
