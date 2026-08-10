package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.MediaScanStatus;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 电视媒体库批次清理三道闸测试：仅 COMPLETED 执行清理（FAILED/PARTIAL 不结算）、
 * 库内隔离（同名剧跨库各建行互不影响）、scan_time 早于批次时间才清理。
 */
@Transactional
class MediaTvScanCleanupTest extends MediaTvScanTestBase {

    /**
     * 来源目录不可达（远程挂载掉线）时扫描记为 PARTIAL，且其下条目不被批次清理；
     * 恢复完整扫描后，合格来源下消失的剧才被清理，不合格来源（已出库）的剧不结算。
     */
    @Test
    void shouldNotCleanupWhenSourceUnreachableOrPartial() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolderA = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视A");
        FileNodeVo seriesFolderA = createFolder(user.getId(), tvFolderA.getId(), "火星生活");
        FileNodeVo seasonFolderA = createFolder(user.getId(), seriesFolderA.getId(), "Season 1");
        upload(user.getId(), seasonFolderA.getId(), "火星生活.S01E01.mkv");
        FileNodeVo tvFolderB = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视B");
        FileNodeVo seriesFolderB = createFolder(user.getId(), tvFolderB.getId(), "亮剑");
        FileNodeVo seasonFolderB = createFolder(user.getId(), seriesFolderB.getId(), "Season 1");
        upload(user.getId(), seasonFolderB.getId(), "亮剑.S01E01.mkv");

        MediaDirectory directory = createTvDirectory(user.getId(), tvFolderA.getId());
        addSource(directory.getId(), tvFolderB.getId());
        mediaScanService.scan(directory.getId());
        assertEquals(2, mediaSeriesMapper.selectCount(null));

        // 来源 A 掉线 + 来源 B 下的剧被删除：本轮扫描 PARTIAL，两道闸均不清理
        purgeSubtree(user.getId(), tvFolderA.getId());
        purgeSubtree(user.getId(), seriesFolderB.getId());
        mediaScanService.scan(directory.getId());

        assertEquals(MediaScanStatus.PARTIAL.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
        assertEquals(2, mediaSeriesMapper.selectCount(null));

        // 来源 A 出库后完整重扫：合格来源 B 下消失的剧被批次清理；来源 A 不结算（由目录管理负责清空）
        mediaDirectorySourceMapper.delete(new LambdaQueryWrapper<MediaDirectorySource>()
                .eq(MediaDirectorySource::getDirectoryId, directory.getId())
                .eq(MediaDirectorySource::getFileNodeId, tvFolderA.getId()));
        mediaScanService.scan(directory.getId());

        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
        List<MediaSeries> remaining = mediaSeriesMapper.selectList(null);
        assertEquals(1, remaining.size());
        assertEquals("火星生活", remaining.getFirst().getSeriesName());
    }

    /**
     * 多库隔离：同名剧跨库各建一行（库级归属）；一个库的扫描与清理不影响另一个库。
     */
    @Test
    void shouldIsolateCleanupWithinDirectory() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder1 = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视一");
        FileNodeVo seriesFolder1 = createFolder(user.getId(), tvFolder1.getId(), "火星生活");
        FileNodeVo seasonFolder1 = createFolder(user.getId(), seriesFolder1.getId(), "Season 1");
        upload(user.getId(), seasonFolder1.getId(), "火星生活.S01E01.mkv");
        FileNodeVo tvFolder2 = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视二");
        FileNodeVo seriesFolder2 = createFolder(user.getId(), tvFolder2.getId(), "火星生活");
        FileNodeVo seasonFolder2 = createFolder(user.getId(), seriesFolder2.getId(), "Season 1");
        upload(user.getId(), seasonFolder2.getId(), "火星生活.S01E01.mkv");

        MediaDirectory directory1 = createTvDirectory(user.getId(), tvFolder1.getId());
        MediaDirectory directory2 = createTvDirectory(user.getId(), tvFolder2.getId());
        mediaScanService.scan(directory1.getId());
        mediaScanService.scan(directory2.getId());
        // 同名剧跨库各建一行
        assertEquals(2, mediaSeriesMapper.selectCount(null));

        // 库 1 的剧文件夹删除后重扫库 1：仅库 1 数据被清理
        purgeSubtree(user.getId(), seriesFolder1.getId());
        mediaScanService.scan(directory1.getId());

        assertEquals(0, mediaSeriesMapper.selectCount(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getDirectoryId, directory1.getId())));
        assertEquals(1, mediaSeriesMapper.selectCount(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getDirectoryId, directory2.getId())));
        assertEquals(1, mediaEpisodeMapper.selectCount(null));
    }

    /**
     * 批次清理闸③（scan_time 早于批次时间才清理）：剧文件夹已删除，但其行 scan_time 晚于本批批次时间
     * （模拟本批扫描开始后该行被更新），本批不清理该行；目录记为 COMPLETED。
     */
    @Test
    void shouldSkipBatchCleanupWhenScanTimeIsNotStale() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo tvFolder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        FileNodeVo seriesFolder = createFolder(user.getId(), tvFolder.getId(), "火星生活");
        FileNodeVo seasonFolder = createFolder(user.getId(), seriesFolder.getId(), "Season 1");
        upload(user.getId(), seasonFolder.getId(), "火星生活.S01E01.mkv");
        MediaDirectory directory = createTvDirectory(user.getId(), tvFolder.getId());
        mediaScanService.scan(directory.getId());
        MediaSeries series = querySingleSeries(directory.getId());

        // 模拟该行在本批扫描开始后已被更新（scan_time 晚于批次时间）：文件夹虽已删除，本批仍不清理
        series.setScanTime(LocalDateTime.now().plusHours(1));
        mediaSeriesMapper.updateById(series);
        purgeSubtree(user.getId(), seriesFolder.getId());
        mediaScanService.scan(directory.getId());

        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
        assertNotNull(mediaSeriesMapper.selectById(series.getId()));
    }

    /**
     * 批次清理闸①（仅 COMPLETED 执行）：扫描在起点抛异常（用户行被删除导致用户名解析失败）整轮记为
     * FAILED，批次清理不执行——过期剧行保留。
     */
    @Test
    void shouldNotCleanupWhenScanFailed() {
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

        // 亮剑剧文件夹删除：其剧行成为过期行（scan_time 早于批次时间），本应被批次清理
        purgeSubtree(user.getId(), otherSeriesFolder.getId());
        // 用户行删除使扫描在起点抛异常（用户名解析 NPE）→ 整轮记为 FAILED，批次清理不执行
        userMapper.deleteById(user.getId());
        mediaScanService.scan(directory.getId());

        assertEquals(MediaScanStatus.FAILED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
        assertEquals(2, mediaSeriesMapper.selectCount(null));
    }
}
