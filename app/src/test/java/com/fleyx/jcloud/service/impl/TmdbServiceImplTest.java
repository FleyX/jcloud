package com.fleyx.jcloud.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleyx.jcloud.common.enums.MediaCompleteStatus;
import com.fleyx.jcloud.common.enums.MediaMetadataSource;
import com.fleyx.jcloud.common.enums.MediaPersistStatus;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.service.SystemConfigService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * TMDB 元数据服务实现测试。
 */
class TmdbServiceImplTest {

    private final TmdbServiceImpl tmdbService = new TmdbServiceImpl(
            mock(SystemConfigService.class),
            mock(MediaMetadataMapper.class),
            new ObjectMapper());

    /**
     * 新建 TMDB 元数据应写入用户隔离维度与类型。
     */
    @Test
    void shouldInitUserIdAndMediaType() {
        MediaMetadata metadata = tmdbService.newTmdbMetadata("user-1", 1000L, "movie");

        assertEquals("user-1", metadata.getUserId());
        assertEquals(1000L, metadata.getTmdbId());
        assertEquals("movie", metadata.getMediaType());
    }

    /**
     * 新建 TMDB 元数据来源固定为 tmdb、完整性为 complete。
     */
    @Test
    void shouldInitTmdbSourceAndCompleteStatus() {
        MediaMetadata metadata = tmdbService.newTmdbMetadata("user-1", 1000L, "tv");

        assertEquals(MediaMetadataSource.TMDB.getCode(), metadata.getSource());
        assertEquals(MediaCompleteStatus.COMPLETE.getCode(), metadata.getCompleteStatus());
    }

    /**
     * 新建 TMDB 元数据落盘状态为 pending，图片文件节点留空（图片写回为后续阶段）。
     */
    @Test
    void shouldInitPendingPersistStatusWithoutImages() {
        MediaMetadata metadata = tmdbService.newTmdbMetadata("user-1", null, "season");

        assertEquals(MediaPersistStatus.PENDING.getCode(), metadata.getPersistStatus());
        assertNull(metadata.getPosterFileNodeId());
        assertNull(metadata.getBackdropFileNodeId());
    }
}
