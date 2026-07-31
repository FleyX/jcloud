package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.dto.MediaDirectorySaveDto;
import com.fleyx.jcloud.model.dto.MediaDirectoryUpdateDto;
import com.fleyx.jcloud.model.dto.MediaMatchUpdateDto;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.dto.MediaProgressUpdateDto;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.vo.MediaDirectoryVo;
import com.fleyx.jcloud.model.vo.MediaHomeVo;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaPlaybackInfoVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;
import com.fleyx.jcloud.model.vo.TmdbSearchResultVo;
import com.fleyx.jcloud.service.MediaDirectoryService;
import com.fleyx.jcloud.service.MediaHomeService;
import com.fleyx.jcloud.service.MediaItemService;
import com.fleyx.jcloud.service.MediaPlaybackService;
import com.fleyx.jcloud.service.MediaScanService;
import com.fleyx.jcloud.service.MediaScrapeService;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;
import jakarta.validation.Valid;
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
@RequiredArgsConstructor
public class MediaController {

    private final MediaDirectoryService mediaDirectoryService;
    private final MediaScanService mediaScanService;
    private final MediaScrapeService mediaScrapeService;
    private final MediaItemService mediaItemService;
    private final MediaHomeService mediaHomeService;
    private final MediaPlaybackService mediaPlaybackService;
    private final TmdbService tmdbService;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final SystemStorageSpaceProvider systemStorageSpaceProvider;
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

    @PutMapping("/items/series/match")
    public R<Void> updateSeriesMatch(@RequestParam String seriesName, @Valid @RequestBody MediaMatchUpdateDto dto) {
        mediaItemService.updateSeriesMatch(seriesName, dto, UserContext.get().id());
        return R.ok();
    }

    @PutMapping("/items/{id}/progress")
    public R<Void> updateProgress(@PathVariable String id, @Valid @RequestBody MediaProgressUpdateDto dto) {
        mediaItemService.updateProgress(id, dto, UserContext.get().id());
        return R.ok();
    }

    // ---------- 播放 ----------

    @GetMapping("/items/{id}/playback")
    public R<MediaPlaybackInfoVo> playbackInfo(@PathVariable String id) {
        return R.ok(mediaPlaybackService.getPlaybackInfo(id, UserContext.get().id()));
    }

    @GetMapping("/items/{id}/stream")
    public ResponseEntity<InputStreamResource> stream(@PathVariable String id,
                                                      @RequestHeader(value = "Range", required = false) String range) {
        MediaPlaybackService.MediaStreamResult result = mediaPlaybackService.stream(id, UserContext.get().id(), range);
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
    public ResponseEntity<InputStreamResource> subtitle(@PathVariable String id, @PathVariable int index)
            throws Exception {
        Path path = mediaPlaybackService.extractSubtitle(id, index, UserContext.get().id());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/vtt"))
                .contentLength(Files.size(path))
                .body(new InputStreamResource(Files.newInputStream(path)));
    }

    @GetMapping("/items/{id}/subtitles/external/{subtitleId}")
    public ResponseEntity<InputStreamResource> externalSubtitle(@PathVariable String id,
                                                                @PathVariable String subtitleId) throws Exception {
        Path path = mediaPlaybackService.extractExternalSubtitle(id, subtitleId, UserContext.get().id());
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
                                                  @RequestParam(defaultValue = "false") boolean forceVideoTranscode) {
        TranscodeSessionManager.TranscodeSession session = mediaPlaybackService.createTranscodeSession(
                id, startMs, audioIndex, targetBitrateKbps, maxHeight, forceVideoTranscode, UserContext.get().id());
        Map<String, String> result = new HashMap<>();
        result.put("sessionId", session.id());
        result.put("playlistUrl", "/jcloud/api/media/transcode/" + session.id() + "/index.m3u8");
        return R.ok(result);
    }

    @GetMapping("/transcode/{sessionId}/{fileName}")
    public ResponseEntity<InputStreamResource> transcodeFile(@PathVariable String sessionId,
                                                             @PathVariable String fileName,
                                                             @RequestParam(required = false) String token) throws Exception {
        String userId = UserContext.get().id();
        // 播放列表可能需要等待 ffmpeg 生成首个切片
        Path path = transcodeSessionManager.touchAndResolve(sessionId, userId, fileName);
        if (path == null && fileName.endsWith(".m3u8")) {
            for (int i = 0; i < 60 && path == null; i++) {
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

    // ---------- 元数据 ----------

    @GetMapping("/metadata/{id}/poster")
    public ResponseEntity<InputStreamResource> poster(@PathVariable String id) throws Exception {
        MediaMetadata metadata = mediaMetadataMapper.selectById(id);
        if (metadata == null || metadata.getPosterPath() == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "海报不存在");
        }
        StorageSpace space = systemStorageSpaceProvider.getSystemSpace();
        Path path = Path.of(space.getPath(), "system", metadata.getPosterPath());
        if (!Files.exists(path)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "海报文件已丢失");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .contentLength(Files.size(path))
                .body(new InputStreamResource(Files.newInputStream(path)));
    }

    @GetMapping("/tmdb/search")
    public R<List<TmdbSearchResultVo>> searchTmdb(@RequestParam String mediaType,
                                                  @RequestParam String query,
                                                  @RequestParam(required = false) Integer year) {
        return R.ok(tmdbService.search(mediaType, query, year));
    }

    @GetMapping("/metadata/{id}/backdrop")
    public ResponseEntity<InputStreamResource> backdrop(@PathVariable String id) throws Exception {
        MediaMetadata metadata = mediaMetadataMapper.selectById(id);
        if (metadata == null || metadata.getBackdropPath() == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "背景图不存在");
        }
        StorageSpace space = systemStorageSpaceProvider.getSystemSpace();
        Path path = Path.of(space.getPath(), "system", metadata.getBackdropPath());
        if (!Files.exists(path)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "背景图文件已丢失");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .contentLength(Files.size(path))
                .body(new InputStreamResource(Files.newInputStream(path)));
    }

    @PostMapping("/metadata/{id}/refresh")
    public R<Void> refreshMetadata(@PathVariable String id) {
        tmdbService.refresh(id);
        return R.ok();
    }
}
