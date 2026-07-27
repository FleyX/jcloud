package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.dto.TmdbConfigDto;
import com.fleyx.jcloud.model.dto.TranscodeConfigDto;
import com.fleyx.jcloud.service.SystemConfigService;
import com.fleyx.jcloud.service.impl.TmdbServiceImpl;
import com.fleyx.jcloud.service.support.TranscodeSessionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 媒体库管理端控制器（管理员）。
 */
@RestController
@RequestMapping(CommonConstant.API + "/admin/media")
@RequiredArgsConstructor
public class AdminMediaController {

    private final SystemConfigService systemConfigService;
    private final TranscodeSessionManager transcodeSessionManager;

    /**
     * 查询 TMDB 全局配置。
     */
    @GetMapping("/tmdb-config")
    public R<TmdbConfigDto> getTmdbConfig() {
        TmdbConfigDto dto = new TmdbConfigDto();
        dto.setApiKey(systemConfigService.getValue(TmdbServiceImpl.CONFIG_KEY_API_KEY, ""));
        dto.setProxy(systemConfigService.getValue(TmdbServiceImpl.CONFIG_KEY_PROXY, ""));
        return R.ok(dto);
    }

    /**
     * 更新 TMDB 全局配置。
     */
    @PutMapping("/tmdb-config")
    public R<Void> updateTmdbConfig(@RequestBody TmdbConfigDto dto) {
        systemConfigService.setValue(TmdbServiceImpl.CONFIG_KEY_API_KEY, dto.getApiKey() == null ? "" : dto.getApiKey());
        systemConfigService.setValue(TmdbServiceImpl.CONFIG_KEY_PROXY, dto.getProxy() == null ? "" : dto.getProxy());
        return R.ok();
    }

    /**
     * 查询转码全局配置。
     */
    @GetMapping("/transcode-config")
    public R<TranscodeConfigDto> getTranscodeConfig() {
        TranscodeConfigDto dto = new TranscodeConfigDto();
        dto.setHwaccel(systemConfigService.getValue(TranscodeSessionManager.CONFIG_KEY_HWACCEL, "auto"));
        dto.setDevice(systemConfigService.getValue(TranscodeSessionManager.CONFIG_KEY_DEVICE, ""));
        dto.setThreads(transcodeSessionManager.resolveThreads());
        return R.ok(dto);
    }

    /**
     * 更新转码全局配置。
     */
    @PutMapping("/transcode-config")
    public R<Void> updateTranscodeConfig(@RequestBody TranscodeConfigDto dto) {
        String hwaccel = dto.getHwaccel() == null ? "auto" : dto.getHwaccel().trim().toLowerCase();
        if (!Set.of("auto", "vaapi", "qsv", "nvenc", "none").contains(hwaccel)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "非法的硬解方式: " + hwaccel);
        }
        int threads = dto.getThreads() == null ? 0 : Math.max(0, dto.getThreads());
        systemConfigService.setValue(TranscodeSessionManager.CONFIG_KEY_HWACCEL, hwaccel);
        systemConfigService.setValue(TranscodeSessionManager.CONFIG_KEY_DEVICE,
                dto.getDevice() == null ? "" : dto.getDevice().trim());
        systemConfigService.setValue(TranscodeSessionManager.CONFIG_KEY_THREADS, String.valueOf(threads));
        return R.ok();
    }
}
