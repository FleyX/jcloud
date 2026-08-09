package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.model.dto.MediaDirectorySaveDto;
import com.fleyx.jcloud.model.dto.MediaDirectoryUpdateDto;
import com.fleyx.jcloud.model.vo.MediaDirectoryVo;
import com.fleyx.jcloud.service.MediaDirectoryService;
import com.fleyx.jcloud.service.MediaScanService;
import com.fleyx.jcloud.service.MediaScrapeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 媒体库目录管理控制器（媒体库 CRUD/来源目录/扫描/削刮触发）。
 */
@RestController
@RequestMapping(CommonConstant.API + "/media")
@Validated
@RequiredArgsConstructor
public class MediaDirectoryController {

    private final MediaDirectoryService mediaDirectoryService;
    private final MediaScanService mediaScanService;
    private final MediaScrapeService mediaScrapeService;

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
}
