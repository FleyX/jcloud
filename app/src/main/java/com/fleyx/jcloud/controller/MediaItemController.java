package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.model.dto.MediaMatchUpdateDto;
import com.fleyx.jcloud.model.dto.MediaPageQueryDto;
import com.fleyx.jcloud.model.dto.MediaProgressUpdateDto;
import com.fleyx.jcloud.model.dto.MediaWatchedUpdateDto;
import com.fleyx.jcloud.model.vo.MediaGenreVo;
import com.fleyx.jcloud.model.vo.MediaItemDetailVo;
import com.fleyx.jcloud.model.vo.MediaItemVo;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import com.fleyx.jcloud.model.vo.MediaSeriesVo;
import com.fleyx.jcloud.service.MediaItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 海报墙与媒体条目详情查询控制器。
 */
@RestController
@RequestMapping(CommonConstant.API + "/media")
@Validated
@RequiredArgsConstructor
public class MediaItemController {

    private final MediaItemService mediaItemService;

    // ---------- 海报墙 ----------

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

    @PutMapping("/items/{id}/watched")
    public R<Void> updateWatched(@PathVariable String id, @Valid @RequestBody MediaWatchedUpdateDto dto) {
        mediaItemService.updateWatched(id, dto, UserContext.get().id());
        return R.ok();
    }
}
