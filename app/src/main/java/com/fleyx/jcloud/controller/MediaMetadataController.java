package com.fleyx.jcloud.controller;

import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.MediaRefreshMode;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.vo.TmdbSearchResultVo;
import com.fleyx.jcloud.service.MediaScrapeService;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaArtworkPersistSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * 元数据图片与刷新控制器（海报/背景图、TMDB 搜索）。
 */
@RestController
@RequestMapping(CommonConstant.API + "/media")
@Validated
@RequiredArgsConstructor
public class MediaMetadataController {

    private final MediaMetadataMapper mediaMetadataMapper;
    private final FileMapper fileMapper;
    private final MediaArtworkPersistSupport mediaArtworkPersistSupport;
    private final MediaScrapeService mediaScrapeService;
    private final TmdbService tmdbService;

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

    /**
     * 单条刷新元数据（两模式，工单 06/07）：missing 补齐缺失文本字段并校验图片/NFO 产物缺失则重建
     * （已匹配字段不动，manual 行只补产物不改字段）；force 重新拉取 TMDB 全量覆盖字段并全量替换
     * 图片/NFO 产物（manual 行豁免字段覆盖，复用既有元数据按 force 语义全量替换产物）。
     * mode 缺省 missing，保持旧前端兼容；非法 mode 值抛参数错误（工单 08 枚举化）。
     */
    @PostMapping("/metadata/{id}/refresh")
    public R<Void> refreshMetadata(@PathVariable String id,
                                   @RequestParam(required = false) String mode) {
        MediaRefreshMode refreshMode = MediaRefreshMode.of(StrUtil.blankToDefault(mode, "missing"));
        if (refreshMode == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "刷新模式不合法");
        }
        mediaScrapeService.refreshItem(id, UserContext.get().id(), refreshMode);
        return R.ok();
    }
}
