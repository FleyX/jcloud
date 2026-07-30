package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.enums.MediaItemType;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.MediaSeriesDetailVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 媒体条目查询服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaItemServiceTest {

    @Autowired
    private MediaItemService mediaItemService;

    @Autowired
    private MediaSeriesMapper mediaSeriesMapper;

    @Autowired
    private MediaItemMapper mediaItemMapper;

    @Test
    void shouldReturnSeriesDetailById() {
        MediaSeries series = insertSeries("user-1", "测试剧");
        insertEpisode("user-1", series, 1, 2);
        insertEpisode("user-1", series, 1, 1);

        MediaSeriesDetailVo detail = mediaItemService.getSeriesDetail(series.getId(), "user-1");

        assertEquals("测试剧", detail.getSeriesName());
        assertEquals(MediaMatchStatus.MATCHED.getCode(), detail.getMatchStatus());
        assertEquals(2, detail.getEpisodes().size());
        // 剧集按季集号排序
        assertEquals(1, detail.getEpisodes().get(0).getEpisodeNo());
        assertEquals(2, detail.getEpisodes().get(1).getEpisodeNo());
    }

    @Test
    void shouldThrowWhenSeriesNotFound() {
        assertThrows(BusinessException.class,
                () -> mediaItemService.getSeriesDetail("not-exists", "user-1"));
    }

    @Test
    void shouldThrowWhenSeriesNotOwned() {
        MediaSeries series = insertSeries("user-1", "测试剧");

        assertThrows(BusinessException.class,
                () -> mediaItemService.getSeriesDetail(series.getId(), "user-2"));
    }

    private MediaSeries insertSeries(String userId, String seriesName) {
        MediaSeries series = new MediaSeries();
        series.setUserId(userId);
        series.setSeriesName(seriesName);
        series.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        mediaSeriesMapper.insert(series);
        return series;
    }

    private void insertEpisode(String userId, MediaSeries series, int seasonNo, int episodeNo) {
        MediaItem item = new MediaItem();
        item.setUserId(userId);
        item.setDirectoryId("dir-1");
        item.setFileNodeId("file-" + seasonNo + "-" + episodeNo);
        item.setItemType(MediaItemType.EPISODE.getCode());
        item.setSeriesName(series.getSeriesName());
        item.setSeriesId(series.getId());
        item.setSeasonNo(seasonNo);
        item.setEpisodeNo(episodeNo);
        item.setMatchStatus(MediaMatchStatus.MATCHED.getCode());
        mediaItemMapper.insert(item);
    }
}
