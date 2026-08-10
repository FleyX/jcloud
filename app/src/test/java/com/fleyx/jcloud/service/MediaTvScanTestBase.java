package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaDirectorySourceMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeFileMapper;
import com.fleyx.jcloud.mapper.MediaEpisodeMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaSeasonMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.bo.MediaProbeResult;
import com.fleyx.jcloud.model.dto.FileRenameDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaEpisode;
import com.fleyx.jcloud.model.po.MediaEpisodeFile;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaSeason;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.service.support.MediaProbeSupport;
import com.fleyx.jcloud.util.FilePathUtil;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * 电视媒体库扫描测试共享基类：在 {@link MediaScanTestBase} 之上集中电视媒体库扫描用例共用的
 * 探测桩（PROBE + lenient stub）、mapper、服务与本地样板方法（电视目录/来源、重命名、查询与清理辅助）。
 */
abstract class MediaTvScanTestBase extends MediaScanTestBase {

    private static final MediaProbeResult PROBE = new MediaProbeResult(
            3_600_000L, "matroska", "h264", "aac", 1920, 1080, null, List.of(), List.of());

    @Autowired
    protected MediaScanService mediaScanService;

    @Autowired
    protected MediaItemService mediaItemService;

    @Autowired
    protected MediaDirectoryMapper mediaDirectoryMapper;

    @Autowired
    protected MediaDirectorySourceMapper mediaDirectorySourceMapper;

    @Autowired
    protected MediaSeriesMapper mediaSeriesMapper;

    @Autowired
    protected MediaSeasonMapper mediaSeasonMapper;

    @Autowired
    protected MediaEpisodeMapper mediaEpisodeMapper;

    @Autowired
    protected MediaEpisodeFileMapper mediaEpisodeFileMapper;

    @Autowired
    protected MediaMetadataMapper mediaMetadataMapper;

    @Autowired
    protected FileMapper fileMapper;

    @Autowired
    protected UserMapper userMapper;

    @MockitoBean
    protected MediaScrapeService mediaScrapeService;

    @MockitoBean
    protected MediaProbeSupport mediaProbeSupport;

    @BeforeEach
    void stubProbe() {
        lenient().when(mediaProbeSupport.probe(any(Path.class))).thenReturn(PROBE);
        lenient().when(mediaProbeSupport.probe(any(InputStream.class))).thenReturn(PROBE);
    }

    /**
     * 种入完整元数据（5 项齐备 + poster/still 指向真实 FileNode），并链接剧/季/集行与手动匹配状态。
     */
    protected void seedCompleteSeries(String userId, MediaSeries series, MediaSeason season, MediaEpisode episode,
                                      String seriesPosterNodeId, String seasonPosterNodeId, String episodeStillNodeId) {
        MediaMetadata seriesMeta = fullMetadata(userId, "series");
        seriesMeta.setOwnerId(series.getId());
        seriesMeta.setPosterFileNodeId(seriesPosterNodeId);
        mediaMetadataMapper.insert(seriesMeta);
        MediaMetadata seasonMeta = fullMetadata(userId, "season");
        seasonMeta.setOwnerId(season.getId());
        seasonMeta.setPosterFileNodeId(seasonPosterNodeId);
        mediaMetadataMapper.insert(seasonMeta);
        MediaMetadata episodeMeta = fullMetadata(userId, "episode");
        episodeMeta.setOwnerId(episode.getId());
        episodeMeta.setPosterFileNodeId(episodeStillNodeId);
        mediaMetadataMapper.insert(episodeMeta);
        series.setMetadataId(seriesMeta.getId());
        series.setMatchStatus(MediaMatchStatus.MANUAL.getCode());
        series.setMetadataComplete(true);
        mediaSeriesMapper.updateById(series);
        season.setMetadataId(seasonMeta.getId());
        mediaSeasonMapper.updateById(season);
        episode.setMetadataId(episodeMeta.getId());
        mediaEpisodeMapper.updateById(episode);
    }

    /**
     * 5 项校验齐备的完整元数据（posterFileNodeId 由调用方指定）。
     */
    protected MediaMetadata fullMetadata(String userId, String ownerType) {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setUserId(userId);
        metadata.setOwnerType(ownerType);
        metadata.setSource("tmdb");
        metadata.setTitle("火星生活");
        metadata.setOverview("平行时空的警探故事");
        metadata.setReleaseDate("2018-01-01");
        metadata.setVoteAverage(8.5);
        return metadata;
    }

    protected void seedMetadata(String userId, String ownerType, String ownerId) {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setUserId(userId);
        metadata.setOwnerType(ownerType);
        metadata.setOwnerId(ownerId);
        metadata.setSource("tmdb");
        metadata.setTitle("测试元数据");
        mediaMetadataMapper.insert(metadata);
    }

    protected MediaSeries querySingleSeries(String directoryId) {
        return mediaSeriesMapper.selectOne(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getDirectoryId, directoryId));
    }

    protected List<MediaSeason> seasonsOfSeries(String seriesId) {
        return mediaSeasonMapper.selectList(new LambdaQueryWrapper<MediaSeason>()
                .eq(MediaSeason::getSeriesId, seriesId));
    }

    protected List<MediaEpisode> episodesOfSeries(String seriesId) {
        return mediaEpisodeMapper.selectList(new LambdaQueryWrapper<MediaEpisode>()
                .eq(MediaEpisode::getSeriesId, seriesId));
    }

    protected List<MediaEpisodeFile> filesOfEpisode(String episodeId) {
        return mediaEpisodeFileMapper.selectList(new LambdaQueryWrapper<MediaEpisodeFile>()
                .eq(MediaEpisodeFile::getEpisodeId, episodeId));
    }

    /**
     * 物理删除文件夹及其整个子树节点（模拟文件夹被删除或远程挂载掉线）。
     */
    protected void purgeSubtree(String userId, String folderNodeId) {
        FileNode folder = fileMapper.selectById(folderNodeId);
        if (folder == null) {
            return;
        }
        List<FileNode> descendants = fileMapper.selectByIdPathPrefix(userId, folder.getPath(), folder.getId());
        if (!descendants.isEmpty()) {
            fileMapper.deleteBatchIds(descendants.stream().map(FileNode::getId).toList());
        }
        fileMapper.deleteById(folderNodeId);
    }

    /**
     * 直接改文件节点的父节点与物化路径，模拟跨季/跨剧移动（绕过两阶段冲突解决流程）。
     */
    protected void moveFileNode(String userId, String fileNodeId, String targetFolderNodeId) {
        FileNode file = fileMapper.selectById(fileNodeId);
        FileNode targetFolder = fileMapper.selectById(targetFolderNodeId);
        FileNode update = new FileNode();
        update.setId(file.getId());
        update.setParentId(targetFolder.getId());
        update.setPath(FilePathUtil.fullIdPath(targetFolder));
        fileMapper.updateById(update);
    }

    /**
     * 直接改文件夹节点的父节点与物化路径，并同步子树所有节点的物化路径前缀（模拟跨剧移动季文件夹）。
     */
    protected void moveFolderNode(String userId, String folderNodeId, String targetParentNodeId) {
        FileNode folder = fileMapper.selectById(folderNodeId);
        FileNode targetParent = fileMapper.selectById(targetParentNodeId);
        String oldPath = folder.getPath();
        String newPath = FilePathUtil.fullIdPath(targetParent);
        List<FileNode> subtree = fileMapper.selectByIdPathPrefix(userId, oldPath, folderNodeId);
        FileNode update = new FileNode();
        update.setId(folder.getId());
        update.setParentId(targetParent.getId());
        update.setPath(newPath);
        fileMapper.updateById(update);
        String oldPrefix = oldPath + FileNodeConstants.PATH_SEPARATOR;
        String newPrefix = newPath + FileNodeConstants.PATH_SEPARATOR;
        for (FileNode node : subtree) {
            if (node.getId().equals(folder.getId())) {
                continue;
            }
            FileNode descendant = new FileNode();
            descendant.setId(node.getId());
            descendant.setPath(newPrefix + node.getPath().substring(oldPrefix.length()));
            fileMapper.updateById(descendant);
        }
    }

    protected MediaSeries querySeriesByFolder(String folderNodeId) {
        return mediaSeriesMapper.selectOne(new LambdaQueryWrapper<MediaSeries>()
                .eq(MediaSeries::getFolderNodeId, folderNodeId));
    }

    protected void rename(String userId, String nodeId, String newName) {
        FileRenameDto dto = new FileRenameDto();
        dto.setId(nodeId);
        dto.setNewName(newName);
        fileOperationService.rename(dto, userId);
    }

    protected MediaDirectory createTvDirectory(String userId, String folderNodeId) {
        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setName("测试电视库");
        directory.setMediaType("tv");
        mediaDirectoryMapper.insert(directory);
        addSource(directory.getId(), folderNodeId);
        return directory;
    }

    protected void addSource(String directoryId, String folderNodeId) {
        MediaDirectorySource source = new MediaDirectorySource();
        source.setDirectoryId(directoryId);
        source.setFileNodeId(folderNodeId);
        mediaDirectorySourceMapper.insert(source);
    }
}
