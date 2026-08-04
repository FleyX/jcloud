package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.dto.MediaDirectorySaveDto;
import com.fleyx.jcloud.model.dto.MediaDirectoryUpdateDto;
import com.fleyx.jcloud.model.dto.MediaFavoriteQueryDto;
import com.fleyx.jcloud.model.dto.MediaFavoriteToggleDto;
import com.fleyx.jcloud.model.dto.MediaMatchUpdateDto;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.dto.MediaProgressUpdateDto;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.vo.MediaDirectoryVo;
import com.fleyx.jcloud.model.vo.MediaFavoriteVo;
import com.fleyx.jcloud.model.vo.MediaGenreVo;
import com.fleyx.jcloud.model.vo.MediaHomeVo;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaPlaybackInfoVo;
import com.fleyx.jcloud.model.vo.MediaSearchResultVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;
import com.fleyx.jcloud.model.vo.TmdbSearchResultVo;
import com.fleyx.jcloud.service.MediaDirectoryService;
import com.fleyx.jcloud.service.MediaFavoriteService;
import com.fleyx.jcloud.service.MediaHomeService;
import com.fleyx.jcloud.service.MediaItemService;
import com.fleyx.jcloud.service.MediaPlaybackService;
import com.fleyx.jcloud.service.MediaScanService;
import com.fleyx.jcloud.service.MediaScrapeService;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaArtworkPersistSupport;
import com.fleyx.jcloud.service.support.MediaMetadataCompleteSupport;
import com.fleyx.jcloud.service.support.TranscodeSession;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频媒体库控制器。
 */
@RestController
@RequestMapping(CommonConstant.API + "/media")
@Validated
@RequiredArgsConstructor
public class MediaController {

    private final MediaDirectoryService mediaDirectoryService;
    private final MediaFavoriteService mediaFavoriteService;
    private final MediaScanService mediaScanService;
    private final MediaScrapeService mediaScrapeService;
    private final MediaItemService mediaItemService;
    private final MediaHomeService mediaHomeService;
    private final MediaPlaybackService mediaPlaybackService;
    private final TmdbService tmdbService;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final FileMapper fileMapper;
    private final MediaArtworkPersistSupport mediaArtworkPersistSupport;
    private final MediaMetadataCompleteSupport metadataCompleteSupport;
    private final TranscodeSessionManager transcodeSessionManager;

    // ---------- 目录管理 ----------

    @GetMapping("/directories")
    public R<List<MediaDirectoryVo>> listDirectories() {
        return R.ok(mediaDirectoryService.list(UserContext.get().id()));
    }

    @PostMapping("/directories")
    public R<MediaDirectoryVo> createDirectory(@Valid @RequestBody MediaDirectorySaveDto dto) {
        return R.ok(mediaDirectoryService.save(dto, UserContext.get().id()));
    }

    @PutMapping("/directories/{id}")
    public R<MediaDirectoryVo> updateDirectory(@PathVariable String id,
                                               @Valid @RequestBody MediaDirectoryUpdateDto dto) {
        dto.setId(id);
        return R.ok(mediaDirectoryService.update(dto, UserContext.get().id()));
    }

    @DeleteMapping("/directories/{id}")
    public R<Void> deleteDirectory(@PathVariable String id) {
        mediaDirectoryService.delete(id, UserContext.get().id());
        return R.ok();
    }

    @PostMapping("/directories/{id}/scan")
    public R<Void> scanDirectory(@PathVariable String id) {
        mediaScanService.submitScan(id, UserContext.get().id());
        return R.ok();
    }

    @PostMapping("/directories/{id}/scrape")
    public R<Void> scrapeDirectory(@PathVariable String id,
                                   @RequestParam(defaultValue = "false") boolean force) {
        mediaScrapeService.submitScrape(id, UserContext.get().id(), force);
        return R.ok();
    }

    // ---------- 海报墙 ----------

    @GetMapping("/home")
    public R<MediaHomeVo> home() {
        return R.ok(mediaHomeService.getHome(UserContext.get().id()));
    }

    /**
     * 聚合媒体库类型列表（类型页）。
     */
    @GetMapping("/libraries/{id}/genres")
    public R<List<MediaGenreVo>> listGenres(@PathVariable String id) {
        return R.ok(mediaItemService.listGenres(UserContext.get().id(), id));
    }

    @GetMapping("/items/movies")
    public R<IPage<MediaItemVo>> listMovies(MediaPageQueryDto query) {
        return R.ok(mediaItemService.listMovies(UserContext.get().id(), query));
    }

    @GetMapping("/items/series")
    public R<IPage<MediaSeriesVo>> listSeries(MediaPageQueryDto query) {
        return R.ok(mediaItemService.listSeries(UserContext.get().id(), query));
    }

    @GetMapping("/items/series/{seriesId}/episodes")
    public R<List<MediaItemVo>> listEpisodes(@PathVariable String seriesId) {
        return R.ok(mediaItemService.listEpisodes(seriesId, UserContext.get().id()));
    }

    @GetMapping("/items/others")
    public R<IPage<MediaItemVo>> listOthers(MediaPageQueryDto query) {
        return R.ok(mediaItemService.listOthers(UserContext.get().id(), query));
    }

    /**
     * 全局搜索：跨该用户全部媒体库搜索，按电影/剧集/其他分组返回。
     */
    @GetMapping("/search")
    public R<MediaSearchResultVo> search(@RequestParam @NotBlank String keyword,
                                         @RequestParam(required = false) Integer size) {
        return R.ok(mediaItemService.search(UserContext.get().id(), keyword.trim(), size == null ? 8 : size));
    }

    @GetMapping("/items/by-file-node/{fileNodeId}")
    public R<String> getItemIdByFileNodeId(@PathVariable String fileNodeId) {
        return R.ok(mediaItemService.getItemIdByFileNodeId(fileNodeId, UserContext.get().id()));
    }

    @GetMapping("/items/{id}/detail")
    public R<MediaItemDetailVo> itemDetail(@PathVariable String id) {
        return R.ok(mediaItemService.getItemDetail(id, UserContext.get().id()));
    }

    @GetMapping("/series/{id}/detail")
    public R<MediaSeriesDetailVo> seriesDetail(@PathVariable String id) {
        return R.ok(mediaItemService.getSeriesDetail(id, UserContext.get().id()));
    }

    @GetMapping("/series/{id}/seasons/{seasonId}/episodes")
    public R<List<MediaItemVo>> listSeasonEpisodes(@PathVariable String id, @PathVariable String seasonId) {
        return R.ok(mediaItemService.listSeasonEpisodes(id, seasonId, UserContext.get().id()));
    }

    @PutMapping("/items/{id}/match")
    public R<MediaItemVo> updateMatch(@PathVariable String id, @Valid @RequestBody MediaMatchUpdateDto dto) {
        return R.ok(mediaItemService.updateMatch(id, dto, UserContext.get().id()));
    }

    @PutMapping("/items/{id}/progress")
    public R<Void> updateProgress(@PathVariable String id, @Valid @RequestBody MediaProgressUpdateDto dto) {
        mediaItemService.updateProgress(id, dto, UserContext.get().id());
        return R.ok();
    }

    // ---------- 收藏 ----------

    @PostMapping("/favorites/toggle")
    public R<Boolean> toggleFavorite(@Valid @RequestBody MediaFavoriteToggleDto dto) {
        return R.ok(mediaFavoriteService.toggle(UserContext.get().id(), dto.getOwnerType(), dto.getOwnerId()));
    }

    @GetMapping("/favorites")
    public R<IPage<MediaFavoriteVo>> pageFavorites(@Valid MediaFavoriteQueryDto query) {
        return R.ok(mediaFavoriteService.pageFavorites(UserContext.get().id(), query));
    }

    // ---------- 播放 ----------

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
                                                  @RequestParam(required = false) Long targetBitrateKbps,
                                                  @RequestParam(required = false) Integer maxHeight,
                                                  @RequestParam(defaultValue = "false") boolean forceVideoTranscode,
                                                  @RequestParam(required = false) String versionId) {
        TranscodeSession session = mediaPlaybackService.createTranscodeSession(
                id, startMs, audioIndex, targetBitrateKbps, maxHeight, forceVideoTranscode,
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
            byte[] content = TranscodeSessionManager.appendTokenToPlaylist(
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

    // ---------- 元数据 ----------

    @GetMapping("/metadata/{id}/poster")
    public ResponseEntity<InputStreamResource> poster(@PathVariable String id) {
        MediaMetadata metadata = mediaMetadataMapper.selectById(id);
        if (metadata == null || !UserContext.get().id().equals(metadata.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "海报不存在");
        }
        return artworkResponse(metadata.getPosterFileNodeId(), "海报");
    }

    @GetMapping("/tmdb/search")
    public R<List<TmdbSearchResultVo>> searchTmdb(@RequestParam String mediaType,
                                                  @RequestParam String query,
                                                  @RequestParam(required = false) Integer year) {
        return R.ok(tmdbService.search(mediaType, query, year));
    }

    @GetMapping("/metadata/{id}/backdrop")
    public ResponseEntity<InputStreamResource> backdrop(@PathVariable String id) {
        MediaMetadata metadata = mediaMetadataMapper.selectById(id);
        if (metadata == null || !UserContext.get().id().equals(metadata.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "背景图不存在");
        }
        return artworkResponse(metadata.getBackdropFileNodeId(), "背景图");
    }

    /**
     * 按图片文件节点实时读取图片（本地直读，远程经适配器下载），加缓存头缓解重复读取。
     */
    private ResponseEntity<InputStreamResource> artworkResponse(String fileNodeId, String label) {
        if (fileNodeId == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, label + "不存在");
        }
        FileNode node = fileMapper.selectById(fileNodeId);
        if (node == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, label + "文件已丢失");
        }
        byte[] bytes = mediaArtworkPersistSupport.readFileBytes(node);
        if (bytes == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, label + "读取失败");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                .contentType(MediaType.IMAGE_JPEG)
                .contentLength(bytes.length)
                .body(new InputStreamResource(new ByteArrayInputStream(bytes)));
    }

    @PostMapping("/metadata/{id}/refresh")
    public R<Void> refreshMetadata(@PathVariable String id) {
        MediaMetadata metadata = mediaMetadataMapper.selectById(id);
        if (metadata == null || !UserContext.get().id().equals(metadata.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "元数据不存在");
        }
        MediaMetadata refreshed = tmdbService.refreshV2(metadata);
        if (refreshed != null) {
            mediaMetadataMapper.updateById(refreshed);
        }
        // 每次刷新结束后重算 owner 的元数据完整性（剧集为聚合语义）；owner_type 未知编码时跳过（of 空安全）
        MediaMetadataOwnerType ownerType = MediaMetadataOwnerType.of(metadata.getOwnerType());
        if (ownerType != null) {
            metadataCompleteSupport.refreshOwnerComplete(ownerType, metadata.getOwnerId());
        }
        return R.ok();
    }
}
