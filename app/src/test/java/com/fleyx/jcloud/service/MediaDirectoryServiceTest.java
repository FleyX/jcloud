package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.MediaDirectorySaveDto;
import com.fleyx.jcloud.model.dto.MediaDirectoryUpdateDto;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.MediaDirectoryVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.util.IdUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 媒体库管理服务测试。
 */
@Transactional
class MediaDirectoryServiceTest extends MediaScanTestBase {

    @Autowired
    private MediaDirectoryService mediaDirectoryService;

    @Autowired
    private MediaSeriesMapper mediaSeriesMapper;

    @Autowired
    private MediaSeasonMapper mediaSeasonMapper;

    @Autowired
    private MediaEpisodeMapper mediaEpisodeMapper;

    @MockitoBean
    private MediaScanService mediaScanService;

    /**
     * 创建媒体库：来源目录落库并在视图中返回（含文件夹名称与来源类型），提交后触发首次扫描。
     */
    @Test
    void shouldCreateLibraryWithSources() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folderA = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影A");
        FileNodeVo folderB = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影B");

        MediaDirectoryVo vo = mediaDirectoryService.save(buildSaveDto(List.of(folderA.getId(), folderB.getId())), user.getId());

        assertEquals(2, vo.getSources().size());
        assertEquals(folderA.getId(), vo.getSources().get(0).getFileNodeId());
        assertEquals("电影A", vo.getSources().get(0).getFolderName());
        assertEquals(FileNodeConstants.SOURCE_LOCAL, vo.getSources().get(0).getSourceType());
        assertEquals("电影B", vo.getSources().get(1).getFolderName());
        triggerAfterCommit();
        verify(mediaScanService).submitScan(vo.getId(), user.getId());
    }

    /**
     * 显示名为空时取第一个来源目录文件夹名。
     */
    @Test
    void shouldDefaultNameToFirstSourceFolder() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "我的电影");
        MediaDirectorySaveDto dto = buildSaveDto(List.of(folder.getId()));
        dto.setName(null);

        MediaDirectoryVo vo = mediaDirectoryService.save(dto, user.getId());

        assertEquals("我的电影", vo.getName());
    }

    /**
     * 同一媒体库内来源目录不允许重复。
     */
    @Test
    void shouldThrowWhenSourceDuplicatedInRequest() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        MediaDirectorySaveDto dto = buildSaveDto(List.of(folder.getId(), folder.getId()));

        assertThrows(BusinessException.class, () -> mediaDirectoryService.save(dto, user.getId()));
    }

    /**
     * 来源目录必须是当前用户的文件夹。
     */
    @Test
    void shouldThrowWhenSourceNotFolder() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo file = fileService.upload(
                new MockMultipartFile("file", "a.mp4", "video/mp4", "video".getBytes()),
                user.getId(), FileNodeConstants.ROOT_ID, null);
        MediaDirectorySaveDto dto = buildSaveDto(List.of(file.getId()));

        assertThrows(BusinessException.class, () -> mediaDirectoryService.save(dto, user.getId()));
    }

    /**
     * 同一文件夹不能属于该用户的其他媒体库（跨库查重）。
     */
    @Test
    void shouldThrowWhenSourceUsedByOtherLibrary() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        mediaDirectoryService.save(buildSaveDto(List.of(folder.getId())), user.getId());

        assertThrows(BusinessException.class,
                () -> mediaDirectoryService.save(buildSaveDto(List.of(folder.getId())), user.getId()));
    }

    /**
     * 同一媒体库内来源目录之间不允许祖先/后代重叠。
     */
    @Test
    void shouldThrowWhenSourcesOverlap() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo parent = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "影视");
        FileNodeVo child = createFolder(user.getId(), parent.getId(), "电影");

        assertThrows(BusinessException.class,
                () -> mediaDirectoryService.save(buildSaveDto(List.of(parent.getId(), child.getId())), user.getId()));
    }

    /**
     * 媒体类型创建后不可修改：不一致抛业务异常，不传或传相同值放行。
     */
    @Test
    void shouldThrowWhenMediaTypeChangedOnUpdate() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        MediaDirectoryVo vo = mediaDirectoryService.save(buildSaveDto(List.of(folder.getId())), user.getId());

        MediaDirectoryUpdateDto changed = buildUpdateDto(vo.getId(), List.of(folder.getId()));
        changed.setMediaType("tv");
        assertThrows(BusinessException.class, () -> mediaDirectoryService.update(changed, user.getId()));

        MediaDirectoryUpdateDto same = buildUpdateDto(vo.getId(), List.of(folder.getId()));
        mediaDirectoryService.update(same, user.getId());
        MediaDirectoryUpdateDto absent = buildUpdateDto(vo.getId(), List.of(folder.getId()));
        absent.setMediaType(null);
        mediaDirectoryService.update(absent, user.getId());
        assertEquals("movie", mediaDirectoryService.list(user.getId()).getFirst().getMediaType());
    }

    /**
     * 增删来源目录：中断当前任务并强制全量重扫；被移除来源目录下的剧（含集与播放进度）级联删除，
     * 保留来源的剧与其集保留（新模型，issue #17/#21）。
     */
    @Test
    void shouldRescanAndCleanItemsWhenSourcesChanged() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folderA = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视A");
        FileNodeVo folderB = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视B");
        MediaDirectorySaveDto saveDto = buildSaveDto(List.of(folderA.getId(), folderB.getId()));
        saveDto.setMediaType("tv");
        MediaDirectoryVo vo = mediaDirectoryService.save(saveDto, user.getId());
        String sourceIdA = vo.getSources().get(0).getId();
        String sourceIdB = vo.getSources().get(1).getId();

        MediaSeries seriesA = insertSeries(user.getId(), vo.getId(), sourceIdA, "保留剧");
        insertEpisode(seriesA.getId(), 100L);
        MediaSeries seriesB = insertSeries(user.getId(), vo.getId(), sourceIdB, "移除剧");

        MediaDirectoryUpdateDto updateDto = buildUpdateDto(vo.getId(), List.of(folderA.getId()));
        updateDto.setMediaType("tv");
        MediaDirectoryVo updated = mediaDirectoryService.update(updateDto, user.getId());

        // 中断当前任务 + 强制全量重扫（事务提交后）
        verify(mediaScanService).requestCancel(vo.getId());
        triggerAfterCommit();
        verify(mediaScanService).submitScan(vo.getId(), user.getId(), true);

        // 被移除来源目录下的剧级联删除，保留来源的剧与其集（含播放进度）保留
        assertNull(mediaSeriesMapper.selectById(seriesB.getId()));
        MediaSeries kept = mediaSeriesMapper.selectById(seriesA.getId());
        assertEquals(sourceIdA, kept.getSourceId());
        assertEquals(1, mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeriesId, seriesA.getId())).size());
        assertEquals(100L, mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeriesId, seriesA.getId())).getFirst().getProgressMs());
        // 视图只保留一个来源目录
        assertEquals(1, updated.getSources().size());
        assertEquals(folderA.getId(), updated.getSources().getFirst().getFileNodeId());
    }

    /**
     * 仅修改名称/cron 不触发中断与重扫。
     */
    @Test
    void shouldNotRescanWhenOnlyNameChanged() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电影");
        MediaDirectoryVo vo = mediaDirectoryService.save(buildSaveDto(List.of(folder.getId())), user.getId());
        int syncCountBefore = TransactionSynchronizationManager.getSynchronizations().size();

        MediaDirectoryUpdateDto dto = buildUpdateDto(vo.getId(), List.of(folder.getId()));
        dto.setName("新名称");
        mediaDirectoryService.update(dto, user.getId());

        verify(mediaScanService, never()).requestCancel(anyString());
        // 未注册新的事务提交后动作（不触发重扫）
        assertEquals(syncCountBefore, TransactionSynchronizationManager.getSynchronizations().size());
        assertEquals("新名称", mediaDirectoryService.list(user.getId()).getFirst().getName());
    }

    /**
     * 删除媒体库：级联删除来源目录与库内全部剧/集（新模型，issue #17/#21）。
     */
    @Test
    void shouldDeleteLibraryCascade() {
        UserVo user = prepareUserWithStorageSpace().user();
        FileNodeVo folder = createFolder(user.getId(), FileNodeConstants.ROOT_ID, "电视");
        MediaDirectorySaveDto saveDto = buildSaveDto(List.of(folder.getId()));
        saveDto.setMediaType("tv");
        MediaDirectoryVo vo = mediaDirectoryService.save(saveDto, user.getId());
        MediaSeries series = insertSeries(user.getId(), vo.getId(), vo.getSources().getFirst().getId(), "测试剧");
        insertEpisode(series.getId(), 0L);

        mediaDirectoryService.delete(vo.getId(), user.getId());

        verify(mediaScanService).requestCancel(vo.getId());
        assertEquals(0, mediaEpisodeMapper.selectCount(new LambdaQueryWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeriesId, series.getId())));
        assertNull(mediaSeriesMapper.selectById(series.getId()));
        assertEquals(0, mediaDirectoryService.list(user.getId()).size());
    }

    /**
     * 手动触发事务提交后的回调（测试事务不会真正提交）。
     */
    private void triggerAfterCommit() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
    }

    private MediaSeries insertSeries(String userId, String directoryId, String sourceId, String seriesName) {
        MediaSeries series = new MediaSeries();
        series.setUserId(userId);
        series.setDirectoryId(directoryId);
        series.setSourceId(sourceId);
        series.setFolderNodeId(IdUtil.nextId());
        series.setSeriesName(seriesName);
        series.setMatchStatus("unmatched");
        mediaSeriesMapper.insert(series);
        return series;
    }

    private void insertEpisode(String seriesId, long progressMs) {
        MediaSeason season = new MediaSeason();
        season.setSeriesId(seriesId);
        season.setFolderNodeId(IdUtil.nextId());
        season.setSeasonNo(1);
        mediaSeasonMapper.insert(season);
        MediaEpisode episode = new MediaEpisode();
        episode.setSeriesId(seriesId);
        episode.setSeasonId(season.getId());
        episode.setEpisodeNo(1);
        episode.setProgressMs(progressMs);
        mediaEpisodeMapper.insert(episode);
    }

    private MediaDirectorySaveDto buildSaveDto(List<String> sourceFileNodeIds) {
        MediaDirectorySaveDto dto = new MediaDirectorySaveDto();
        dto.setSourceFileNodeIds(sourceFileNodeIds);
        dto.setName("测试媒体库");
        dto.setMediaType("movie");
        return dto;
    }

    private MediaDirectoryUpdateDto buildUpdateDto(String id, List<String> sourceFileNodeIds) {
        MediaDirectoryUpdateDto dto = new MediaDirectoryUpdateDto();
        dto.setId(id);
        dto.setSourceFileNodeIds(sourceFileNodeIds);
        dto.setName("测试媒体库");
        dto.setMediaType("movie");
        return dto;
    }

}
