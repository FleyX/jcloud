package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.service.TmdbService;
import com.fleyx.jcloud.service.support.MediaArtworkPersistSupport;
import com.fleyx.jcloud.service.support.MediaItemVoSupport;
import com.fleyx.jcloud.service.support.MediaSeriesSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 媒体条目查询服务实现测试。
 */
class MediaItemServiceImplTest {

    private final MediaItemMapper mediaItemMapper = mock(MediaItemMapper.class);
    private final MediaMetadataMapper mediaMetadataMapper = mock(MediaMetadataMapper.class);
    private final MediaSeriesMapper mediaSeriesMapper = mock(MediaSeriesMapper.class);
    private final MediaSeasonMapper mediaSeasonMapper = mock(MediaSeasonMapper.class);
    private final FileMapper fileMapper = mock(FileMapper.class);
    private final TmdbService tmdbService = mock(TmdbService.class);
    private final MediaSeriesSupport mediaSeriesSupport = mock(MediaSeriesSupport.class);
    private final MediaItemVoSupport mediaItemVoSupport = mock(MediaItemVoSupport.class);
    private final MediaArtworkPersistSupport mediaArtworkPersistSupport = mock(MediaArtworkPersistSupport.class);

    private final MediaItemServiceImpl mediaItemService = new MediaItemServiceImpl(
            mediaItemMapper, mediaMetadataMapper, mediaSeriesMapper, mediaSeasonMapper,
            fileMapper, tmdbService, mediaSeriesSupport, mediaItemVoSupport, mediaArtworkPersistSupport);

    /**
     * 按文件节点 ID 反查媒体条目 ID：命中时返回条目 ID。
     */
    @Test
    void shouldReturnItemIdByFileNodeId() {
        MediaItem item = new MediaItem();
        item.setId("item-1");
        item.setFileNodeId("fn-1");
        item.setUserId("user-1");
        when(mediaItemMapper.selectOne(argThat(wrapper -> wrapper instanceof LambdaQueryWrapper))).thenReturn(item);

        String itemId = mediaItemService.getItemIdByFileNodeId("fn-1", "user-1");

        assertEquals("item-1", itemId);
    }

    /**
     * 按文件节点 ID 反查媒体条目 ID：未命中时抛 NOT_FOUND 业务异常。
     */
    @Test
    void shouldThrowNotFoundWhenItemMissing() {
        when(mediaItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> mediaItemService.getItemIdByFileNodeId("fn-2", "user-1"));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
    }
}
