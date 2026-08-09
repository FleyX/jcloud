package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.model.dto.MediaFavoriteQueryDto;
import com.fleyx.jcloud.model.dto.MediaFavoriteToggleDto;
import com.fleyx.jcloud.model.vo.MediaFavoriteVo;
import com.fleyx.jcloud.model.vo.MediaHomeVo;
import com.fleyx.jcloud.model.vo.MediaSearchResultVo;
import com.fleyx.jcloud.service.MediaFavoriteService;
import com.fleyx.jcloud.service.MediaHomeService;
import com.fleyx.jcloud.service.MediaItemService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页聚合、搜索与收藏控制器（首页/我的收藏是顶栏影视菜单的同级 Tab）。
 */
@RestController
@RequestMapping(CommonConstant.API + "/media")
@Validated
@RequiredArgsConstructor
public class MediaHomeController {

    private final MediaHomeService mediaHomeService;
    private final MediaItemService mediaItemService;
    private final MediaFavoriteService mediaFavoriteService;

    // ---------- 首页与搜索 ----------

    @GetMapping("/home")
    public R<MediaHomeVo> home() {
        return R.ok(mediaHomeService.getHome(UserContext.get().id()));
    }

    /**
     * 全局搜索：跨该用户全部媒体库搜索，按电影/剧集/其他分组返回。
     */
    @GetMapping("/search")
    public R<MediaSearchResultVo> search(@RequestParam @NotBlank String keyword,
                                         @RequestParam(required = false) Integer size) {
        return R.ok(mediaItemService.search(UserContext.get().id(), keyword.trim(), size == null ? 8 : size));
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
}
