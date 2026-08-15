package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.vo.MediaPlaybackConfigVo;
import com.fleyx.jcloud.model.vo.MediaPlaybackInfoVo;
import com.fleyx.jcloud.service.MediaPlaybackService;
import com.fleyx.jcloud.service.support.TranscodePlaylistSupport;
import com.fleyx.jcloud.service.support.TranscodeSession;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 媒体播放与转码控制器（播放信息/直放流/字幕/转码会话）。
 */
@RestController
@RequestMapping(CommonConstant.API + "/media")
@Validated
@RequiredArgsConstructor
public class MediaPlaybackController {

    private final MediaPlaybackService mediaPlaybackService;
    private final TranscodeSessionManager transcodeSessionManager;

    // ---------- 播放 ----------

    /**
     * 全局播放配置（ADR 0024）：码率档位阶梯、直放/转封装白名单、看完阈值。
     * 后端常量为唯一事实源，前端进入播放流程时拉取一次并缓存，不再硬编码。
     */
    @GetMapping("/playback-config")
    public R<MediaPlaybackConfigVo> playbackConfig() {
        return R.ok(mediaPlaybackService.getPlaybackConfig());
    }

    @GetMapping("/items/{id}/playback")
    public R<MediaPlaybackInfoVo> playbackInfo(@PathVariable String id,
                                               @RequestParam(required = false) String versionId) {
        return R.ok(mediaPlaybackService.getPlaybackInfo(id, UserContext.get().id(), versionId));
    }

    @GetMapping("/items/{id}/stream")
    public ResponseEntity<InputStreamResource> stream(@PathVariable String id,
                                                      @RequestHeader(value = "Range", required = false) String range,
                                                      @RequestParam(required = false) String versionId) {
        MediaPlaybackService.MediaStreamResult result =
                mediaPlaybackService.stream(id, UserContext.get().id(), range, versionId);
        FileDownloadResult download = result.downloadResult();
        String encodedName = URLEncoder.encode(result.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        ResponseEntity.BodyBuilder builder;
        if (result.rangeStart() != null) {
            builder = ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.CONTENT_RANGE,
                            "bytes " + result.rangeStart() + "-" + result.rangeEnd() + "/" + result.totalSize())
                    .contentLength(result.rangeEnd() - result.rangeStart() + 1);
        } else {
            builder = ResponseEntity.ok().contentLength(result.totalSize());
        }
        return builder
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.parseMediaType(download.getContentType()))
                .body(new InputStreamResource(download.getInputStream()));
    }

    @GetMapping("/items/{id}/subtitles/{index}")
    public ResponseEntity<InputStreamResource> subtitle(@PathVariable String id, @PathVariable int index,
                                                        @RequestParam(defaultValue = "0") long offsetMs,
                                                        @RequestParam(required = false) String versionId)
            throws Exception {
        Path path = mediaPlaybackService.extractSubtitle(id, index, offsetMs, UserContext.get().id(), versionId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/vtt"))
                .contentLength(Files.size(path))
                .body(new InputStreamResource(Files.newInputStream(path)));
    }

    @GetMapping("/items/{id}/subtitles/external/{subtitleId}")
    public ResponseEntity<InputStreamResource> externalSubtitle(@PathVariable String id,
                                                                @PathVariable String subtitleId,
                                                                @RequestParam(defaultValue = "0") long offsetMs,
                                                                @RequestParam(required = false) String versionId)
            throws Exception {
        Path path = mediaPlaybackService.extractExternalSubtitle(
                id, subtitleId, offsetMs, UserContext.get().id(), versionId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/vtt"))
                .contentLength(Files.size(path))
                .body(new InputStreamResource(Files.newInputStream(path)));
    }

    @PostMapping("/items/{id}/transcode")
    public R<Map<String, String>> createTranscode(@PathVariable String id,
                                                  @RequestParam(defaultValue = "0") long startMs,
                                                  @RequestParam(required = false) Integer audioIndex,
                                                  @RequestParam(required = false) Integer subtitleIndex,
                                                  @RequestParam(required = false) Long targetBitrateKbps,
                                                  @RequestParam(required = false) Integer maxHeight,
                                                  @RequestParam(defaultValue = "false") boolean forceVideoTranscode,
                                                  @RequestParam(required = false) String versionId) {
        TranscodeSession session = mediaPlaybackService.createTranscodeSession(
                id, startMs, audioIndex, subtitleIndex, targetBitrateKbps, maxHeight, forceVideoTranscode,
                UserContext.get().id(), versionId);
        Map<String, String> result = new HashMap<>();
        result.put("sessionId", session.id());
        result.put("playlistUrl", "/jcloud/api/media/transcode/" + session.id() + "/index.m3u8");
        return R.ok(result);
    }

    /**
     * 播放页心跳：播放页打开期间每 5s 调用一次。超时未收到心跳的会话会被自动回收。
     */
    @PostMapping("/transcode/{sessionId}/heartbeat")
    public R<Void> transcodeHeartbeat(@PathVariable String sessionId) {
        transcodeSessionManager.heartbeat(sessionId, UserContext.get().id());
        return R.ok();
    }

    /**
     * 主动关闭转码会话：播放页正常退出时调用（含 sendBeacon 场景，token 走查询参数），即时回收。
     */
    @PostMapping("/transcode/{sessionId}/close")
    public R<Void> closeTranscode(@PathVariable String sessionId) {
        transcodeSessionManager.closeSession(sessionId, UserContext.get().id());
        return R.ok();
    }

    @GetMapping("/transcode/{sessionId}/{fileName}")
    public ResponseEntity<InputStreamResource> transcodeFile(@PathVariable String sessionId,
                                                             @PathVariable String fileName,
                                                             @RequestParam(required = false) String token) throws Exception {
        String userId = UserContext.get().id();
        // 播放列表、分片、初始化段都可能需要等待 ffmpeg 生成
        Path path = transcodeSessionManager.touchAndResolve(sessionId, userId, fileName);
        if (path == null && isWaitableTranscodeFile(fileName)) {
            int maxRetries = fileName.endsWith(".m3u8") ? 60 : 30;
            for (int i = 0; i < maxRetries && path == null; i++) {
                Thread.sleep(500);
                path = transcodeSessionManager.touchAndResolve(sessionId, userId, fileName);
            }
        }
        if (path == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "转码文件尚未生成");
        }
        MediaType contentType = fileName.endsWith(".m3u8")
                ? MediaType.parseMediaType("application/vnd.apple.mpegurl")
                : MediaType.parseMediaType("video/mp4");
        if (fileName.endsWith(".m3u8") && token != null && !token.isBlank()) {
            // 切片相对地址会丢失播放列表 URL 上的 token 查询参数，重写 m3u8 使切片请求携带凭证
            byte[] content = TranscodePlaylistSupport.appendTokenToPlaylist(
                    Files.readString(path, StandardCharsets.UTF_8), token).getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .contentType(contentType)
                    .contentLength(content.length)
                    .body(new InputStreamResource(new ByteArrayInputStream(content)));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(contentType)
                .contentLength(Files.size(path))
                .body(new InputStreamResource(Files.newInputStream(path)));
    }

    private boolean isWaitableTranscodeFile(String fileName) {
        return fileName.endsWith(".m3u8") || fileName.endsWith(".m4s") || "init.mp4".equals(fileName);
    }
}
