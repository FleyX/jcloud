package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.MediaScanStatus;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 电视媒体库删除与级联测试：删除集文件/季文件夹/剧文件夹后重扫，
 * 集/季行即时清理、剧行批次级联删除（连带 t_media_metadata_v2），无孤儿行。
 */
@Transactional
class MediaTvScanDeleteTest extends MediaTvScanTestBase {

    /**
     * 删除一集文件后重扫：该集与其集文件明细即时删除，季与剧保留。
     */
    @Test
    void shouldDeleteEpisodeImmediatelyWhenFileRemoved() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        upload(user.getId(), seasonFolder.getId(), "火星生活.S01E01.mkv");
        FileNodeVo e02 = upload(user.getId(), seasonFolder.getId(), "火星生活.S01E02.mkv");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        fileMapper.deleteById(e02.getId());
        mediaScanService.scan(directory.getId());

        MediaSeries series = querySingleSeries(directory.getId());
        List<MediaEpisode> episodes = episodesOfSeries(series.getId());
        assertEquals(1, episodes.size());
        assertEquals(1, episodes.getFirst().getEpisodeNo());
        assertEquals(1, mediaEpisodeFileMapper.selectCount(null));
        assertEquals(1, seasonsOfSeries(series.getId()).size());
        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
    }

    /**
     * 删除季文件夹后重扫：该季及其集/集文件被即时删除（连带 metadata_v2 行），同剧其他季完好，无孤儿。
     */
    @Test
    void shouldDeleteSeasonImmediatelyWhenSeasonFolderRemoved() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo season1Folder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        upload(user.getId(), season1Folder.getId(), "火星生活.S01E01.mkv");
        FileNodeVo season2Folder = createFolder(user.getId(), seriesFolder.getId(), "Season 2");
        upload(user.getId(), season2Folder.getId(), "火星生活.S02E01.mkv");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());

        MediaSeries series = querySingleSeries(directory.getId());
        assertEquals(2, seasonsOfSeries(series.getId()).size());
        MediaSeason season2 = seasonsOfSeries(series.getId()).stream()
                .filter(s -> s.getSeasonNo() == 2).findFirst().orElseThrow();
        MediaEpisode season2Episode = episodesOfSeries(series.getId()).stream()
                .filter(e -> e.getSeasonId().equals(season2.getId())).findFirst().orElseThrow();
        seedMetadata(user.getId(), "season", season2.getId());
        seedMetadata(user.getId(), "episode", season2Episode.getId());

        purgeSubtree(user.getId(), season2Folder.getId());
        mediaScanService.scan(directory.getId());

        List<MediaSeason> seasons = seasonsOfSeries(series.getId());
        assertEquals(1, seasons.size());
        assertEquals(1, seasons.getFirst().getSeasonNo());
        assertNull(mediaSeasonMapper.selectById(season2.getId()));
        assertNull(mediaEpisodeMapper.selectById(season2Episode.getId()));
        assertEquals(0, mediaEpisodeFileMapper.selectCount(new LambdaQueryWrapper<MediaEpisodeFile>()
                .eq(MediaEpisodeFile::getEpisodeId, season2Episode.getId())));
        assertEquals(0, mediaMetadataMapper.selectCount(null));
        // 同剧第一季完好
        assertEquals(1, episodesOfSeries(series.getId()).size());
        assertEquals(1, mediaEpisodeFileMapper.selectCount(null));
        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
    }

    /**
     * 删除剧文件夹后重扫：批次清理级联删除剧/季/集/集文件，连带 metadata_v2 行，无孤儿。
     */
    @Test
    void shouldCascadeDeleteWhenSeriesFolderRemoved() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        upload(user.getId(), seasonFolder.getId(), "火星生活.S01E01.mkv");
        FileNodeVo otherSeriesFolder = createFolder(user.getId(), tvFolder.getId(), "亮剑");
        FileNodeVo otherSeasonFolder = createFolder(user.getId(), otherSeriesFolder.getId(), "Season 1");
        upload(user.getId(), otherSeasonFolder.getId(), "亮剑.S01E01.mkv");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());
        assertEquals(2, mediaSeriesMapper.selectCount(null));

        MediaSeries removed = mediaSeriesMapper.selectOne(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getFolderNodeId, seriesFolder.getId()));
        MediaSeason removedSeason = seasonsOfSeries(removed.getId()).getFirst();
        MediaEpisode removedEpisode = episodesOfSeries(removed.getId()).getFirst();
        // 模拟削刮写入的元数据（owner 反向指针），验证级联连带删除
        seedMetadata(user.getId(), "series", removed.getId());
        seedMetadata(user.getId(), "season", removedSeason.getId());
        seedMetadata(user.getId(), "episode", removedEpisode.getId());

        purgeSubtree(user.getId(), seriesFolder.getId());
        mediaScanService.scan(directory.getId());

        assertNull(mediaSeriesMapper.selectById(removed.getId()));
        assertNull(mediaSeasonMapper.selectById(removedSeason.getId()));
        assertNull(mediaEpisodeMapper.selectById(removedEpisode.getId()));
        assertEquals(0, mediaEpisodeFileMapper.selectCount(new LambdaQueryWrapper<MediaEpisodeFile>()
                .eq(MediaEpisodeFile::getEpisodeId, removedEpisode.getId())));
        assertEquals(0, mediaMetadataMapper.selectCount(null));
        // 另一部剧完好
        assertEquals(1, mediaSeriesMapper.selectCount(null));
        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
    }
}
