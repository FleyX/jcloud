package com.fleyx.jcloud.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.service.SystemConfigService;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * TMDB 元数据服务实现测试。
 */
class TmdbServiceImplTest {

    private final TmdbServiceImpl tmdbService = new TmdbServiceImpl(
            mock(SystemConfigService.class),
            mock(SystemStorageSpaceProvider.class),
            mock(MediaMetadataMapper.class),
            new ObjectMapper());

    /**
     * backdrop 背景图应使用 w1280 分辨率下载。
     */
    @Test
    void shouldUseW1280ForBackdrop() {
        assertEquals("w1280", tmdbService.resolveImageWidth("backdrop"));
    }

    /**
     * poster 海报图应保持 w500 分辨率下载。
     */
    @Test
    void shouldUseW500ForPoster() {
        assertEquals("w500", tmdbService.resolveImageWidth("poster"));
    }

    /**
     * 其它类型图片默认使用 w500。
     */
    @Test
    void shouldUseW500ForOtherKinds() {
        assertEquals("w500", tmdbService.resolveImageWidth("still"));
        assertEquals("w500", tmdbService.resolveImageWidth("unknown"));
    }
}
