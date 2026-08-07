package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaMetadataMapper;
import com.fleyx.jcloud.mapper.MediaMovieMapper;
import com.fleyx.jcloud.mapper.MediaOtherMapper;
import com.fleyx.jcloud.mapper.MediaSeriesMapper;
import com.fleyx.jcloud.model.dto.MediaDirectorySaveDto;
import com.fleyx.jcloud.model.dto.MediaDirectoryUpdateDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaMetadata;
import com.fleyx.jcloud.model.po.MediaMovie;
import com.fleyx.jcloud.model.po.MediaOther;
import com.fleyx.jcloud.model.po.MediaSeries;
import com.fleyx.jcloud.model.vo.MediaDirectoryVo;
import com.fleyx.jcloud.service.MediaDirectoryService;
import com.fleyx.jcloud.service.MediaScanService;
import com.fleyx.jcloud.service.support.MediaDirectorySourceSupport;
import com.fleyx.jcloud.service.support.MediaItemVoSupport;
import com.fleyx.jcloud.service.support.MediaMovieCascadeSupport;
import com.fleyx.jcloud.service.support.MediaOtherCascadeSupport;
import com.fleyx.jcloud.service.support.MediaTvCascadeSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 媒体库管理服务实现。
 * <p>
 * 媒体库 = 名称 + 媒体类型 + N 个来源目录；媒体类型创建后不可修改；
 * 增删来源目录会中断当前扫描/削刮任务并强制全量重扫，被移除来源目录下的条目（含播放进度）一并删除。
 * 条目数与封面均按新模型三表统计（issue #21：电影 t_media_movie / 剧集 t_media_series / 其他 t_media_other），
 * 旧表统计路径已随旧四表弃用删除。
 */
@Service
@RequiredArgsConstructor
public class MediaDirectoryServiceImpl implements MediaDirectoryService {

    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final MediaMovieMapper mediaMovieMapper;
    private final MediaSeriesMapper mediaSeriesMapper;
    private final MediaOtherMapper mediaOtherMapper;
    private final MediaMetadataMapper mediaMetadataMapper;
    private final FileMapper fileMapper;
    private final MediaScanService mediaScanService;
    private final MediaDirectorySourceSupport sourceSupport;
    private final MediaItemVoSupport mediaItemVoSupport;
    private final MediaTvCascadeSupport mediaTvCascadeSupport;
    private final MediaMovieCascadeSupport mediaMovieCascadeSupport;
    private final MediaOtherCascadeSupport mediaOtherCascadeSupport;

    @Override
    public List<MediaDirectoryVo> list(String userId) {
        List<MediaDirectory> directories = mediaDirectoryMapper.selectList(
                new LambdaQueryWrapper<MediaDirectory>()
                        .eq(MediaDirectory::getUserId, userId)
                        .orderByAsc(MediaDirectory::getCreateTime));
        Map<String, Long> countMap = countByDirectory(directories.stream()
                .map(MediaDirectory::getId).toList());
        Map<String, List<MediaDirectorySource>> sourceMap = sourceSupport.mapByDirectoryIds(
                directories.stream().map(MediaDirectory::getId).toList());
        return directories.stream()
                .map(d -> toVo(d, countMap.getOrDefault(d.getId(), 0L),
                        sourceMap.getOrDefault(d.getId(), List.of())))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaDirectoryVo save(MediaDirectorySaveDto dto, String userId) {
        List<FileNode> folders = sourceSupport.validateSources(dto.getSourceFileNodeIds(), userId, null);
        validateCron(dto.getScanCron());

        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setName(dto.getName() == null || dto.getName().isBlank()
                ? folders.getFirst().getName() : dto.getName());
        directory.setMediaType(dto.getMediaType());
        directory.setScanCron(dto.getScanCron());
        directory.setNextScanTime(computeNextScanTime(dto.getScanCron()));
        mediaDirectoryMapper.insert(directory);
        for (String fileNodeId : dto.getSourceFileNodeIds()) {
            sourceSupport.insertSource(directory.getId(), fileNodeId);
        }

        submitScanAfterCommit(directory.getId(), userId);
        return toVo(directory, 0L, sourceSupport.listByDirectoryId(directory.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaDirectoryVo update(MediaDirectoryUpdateDto dto, String userId) {
        MediaDirectory directory = requireOwned(dto.getId(), userId);
        if (dto.getMediaType() != null && !directory.getMediaType().equals(dto.getMediaType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "媒体类型创建后不可修改");
        }
        validateCron(dto.getScanCron());
        sourceSupport.validateSources(dto.getSourceFileNodeIds(), userId, directory.getId());

        List<MediaDirectorySource> currentSources = sourceSupport.listByDirectoryId(directory.getId());
        boolean sourcesChanged = !currentSources.stream().map(MediaDirectorySource::getFileNodeId)
                .collect(Collectors.toSet())
                .equals(new java.util.HashSet<>(dto.getSourceFileNodeIds()));
        List<MediaDirectorySource> removed =
                sourceSupport.applySourceChanges(directory.getId(), currentSources, dto.getSourceFileNodeIds());

        directory.setName(dto.getName());
        directory.setScanCron(dto.getScanCron());
        directory.setNextScanTime(computeNextScanTime(dto.getScanCron()));
        mediaDirectoryMapper.updateById(directory);

        if (sourcesChanged) {
            // 中断正在进行的扫描/削刮
            mediaScanService.requestCancel(directory.getId());
            if (!removed.isEmpty()) {
                // 被移除来源目录下的条目（含播放进度）按媒体类型级联删除
                if (MediaType.TV.getCode().equals(directory.getMediaType())) {
                    mediaTvCascadeSupport.deleteByDirectoryAndSourceIds(directory.getId(),
                            removed.stream().map(MediaDirectorySource::getId).toList());
                } else if (MediaType.MOVIE.getCode().equals(directory.getMediaType())) {
                    mediaMovieCascadeSupport.deleteByDirectoryAndSourceIds(directory.getId(),
                            removed.stream().map(MediaDirectorySource::getId).toList());
                } else if (MediaType.OTHER.getCode().equals(directory.getMediaType())) {
                    mediaOtherCascadeSupport.deleteByDirectoryAndSourceIds(directory.getId(),
                            removed.stream().map(MediaDirectorySource::getId).toList());
                }
            }
            submitForceScanAfterCommit(directory.getId(), userId);
        }
        return toVo(directory, countByDirectoryId(directory.getId()),
                sourceSupport.listByDirectoryId(directory.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id, String userId) {
        requireOwned(id, userId);
        mediaScanService.requestCancel(id);
        sourceSupport.deleteByDirectoryId(id);
        // 电视库新模型：库内剧集全部级联删除（issue #17）
        mediaTvCascadeSupport.deleteByDirectoryId(id);
        // 电影库新模型：库内电影全部级联删除（issue #18）
        mediaMovieCascadeSupport.deleteByDirectoryId(id);
        // 其他库新模型：库内 other 行全部级联删除（issue #19）
        mediaOtherCascadeSupport.deleteByDirectoryId(id);
        mediaDirectoryMapper.deleteById(id);
    }

    /**
     * 事务提交后再触发异步强制全量扫描，避免扫描线程读到未提交数据。
     */
    private void submitForceScanAfterCommit(String directoryId, String userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    mediaScanService.submitScan(directoryId, userId, true);
                }
            });
        } else {
            mediaScanService.submitScan(directoryId, userId, true);
        }
    }

    /**
     * 事务提交后再触发异步扫描，避免扫描线程读到未提交数据。
     */
    private void submitScanAfterCommit(String directoryId, String userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    mediaScanService.submitScan(directoryId, userId);
                }
            });
        } else {
            mediaScanService.submitScan(directoryId, userId);
        }
    }

    private MediaDirectory requireOwned(String id, String userId) {
        MediaDirectory directory = mediaDirectoryMapper.selectById(id);
        if (directory == null || !userId.equals(directory.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "媒体库不存在");
        }
        return directory;
    }

    private void validateCron(String cron) {
        if (cron != null && !cron.isBlank() && !CronExpression.isValidExpression(cron)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "cron 表达式不合法");
        }
    }

    private LocalDateTime computeNextScanTime(String cron) {
        if (cron == null || cron.isBlank()) {
            return null;
        }
        return CronExpression.parse(cron).next(LocalDateTime.now());
    }

    private MediaDirectoryVo toVo(MediaDirectory directory, Long itemCount, List<MediaDirectorySource> sources) {
        MediaDirectoryVo vo = new MediaDirectoryVo();
        vo.setId(directory.getId());
        vo.setSources(sourceSupport.toSourceVos(sources));
        vo.setName(directory.getName());
        vo.setMediaType(directory.getMediaType());
        vo.setScanCron(directory.getScanCron());
        vo.setLastScanTime(directory.getLastScanTime());
        vo.setLastScanStatus(directory.getLastScanStatus());
        vo.setLastScanError(directory.getLastScanError());
        vo.setLastScrapeTime(directory.getLastScrapeTime());
        vo.setLastScrapeStatus(directory.getLastScrapeStatus());
        vo.setLastScrapeError(directory.getLastScrapeError());
        vo.setItemCount(itemCount);
        vo.setCoverPosterUrl(resolveCoverPosterUrl(directory));
        return vo;
    }

    /**
     * 统计若干媒体库的条目数：电影/剧集/其他三类新表按 directory_id 聚合（issue #21）。
     */
    private Map<String, Long> countByDirectory(List<String> directoryIds) {
        if (directoryIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> countMap = mediaMovieMapper.selectList(new LambdaQueryWrapper<MediaMovie>()
                        .in(MediaMovie::getDirectoryId, directoryIds))
                .stream().collect(Collectors.groupingBy(MediaMovie::getDirectoryId, Collectors.counting()));
        mediaSeriesMapper.selectList(new LambdaQueryWrapper<MediaSeries>()
                        .in(MediaSeries::getDirectoryId, directoryIds))
                .forEach(s -> countMap.merge(s.getDirectoryId(), 1L, Long::sum));
        mediaOtherMapper.selectList(new LambdaQueryWrapper<MediaOther>()
                        .in(MediaOther::getDirectoryId, directoryIds))
                .forEach(o -> countMap.merge(o.getDirectoryId(), 1L, Long::sum));
        return countMap;
    }

    private long countByDirectoryId(String directoryId) {
        return countByDirectory(List.of(directoryId)).getOrDefault(directoryId, 0L);
    }

    /**
     * 解析媒体库封面：电影/电视库取库内最新添加且有海报条目的元数据海报（新表，issue #21）；
     * 其他类型库无海报条目时用最新 other 行的预览缩略图兜底（issue #19）；空库为空。
     */
    private String resolveCoverPosterUrl(MediaDirectory directory) {
        if (MediaType.OTHER.getCode().equals(directory.getMediaType())) {
            MediaOther latest = mediaOtherMapper.selectOne(new LambdaQueryWrapper<MediaOther>()
                    .eq(MediaOther::getDirectoryId, directory.getId())
                    .orderByDesc(MediaOther::getCreateTime)
                    .last("limit 1"));
            return latest == null ? null : mediaItemVoSupport.filePreviewPosterUrl(latest.getFileNodeId());
        }
        MediaMetadata posterMetadata = latestPosterMetadata(directory);
        if (posterMetadata == null) {
            return null;
        }
        // 库封面取海报文件节点版本：节点存在时附带 ?v= 使覆盖写后缓存失效，缺失时不带
        FileNode posterNode = fileMapper.selectById(posterMetadata.getPosterFileNodeId());
        Long version = posterNode == null ? null : posterNode.getLastModified();
        return mediaItemVoSupport.metadataPosterUrl(posterMetadata.getId(), version);
    }

    /**
     * 库内最新添加且元数据有海报的条目元数据（电影库取电影行，电视库取剧集行）。
     */
    private MediaMetadata latestPosterMetadata(MediaDirectory directory) {
        List<String> candidates;
        if (MediaType.MOVIE.getCode().equals(directory.getMediaType())) {
            candidates = mediaMovieMapper.selectList(new LambdaQueryWrapper<MediaMovie>()
                            .eq(MediaMovie::getDirectoryId, directory.getId())
                            .isNotNull(MediaMovie::getMetadataId)
                            .orderByDesc(MediaMovie::getCreateTime)
                            .last("limit 50"))
                    .stream().map(MediaMovie::getMetadataId).toList();
        } else {
            candidates = mediaSeriesMapper.selectList(new LambdaQueryWrapper<MediaSeries>()
                            .eq(MediaSeries::getDirectoryId, directory.getId())
                            .isNotNull(MediaSeries::getMetadataId)
                            .orderByDesc(MediaSeries::getCreateTime)
                            .last("limit 50"))
                    .stream().map(MediaSeries::getMetadataId).toList();
        }
        for (String metadataId : candidates) {
            MediaMetadata metadata = mediaMetadataMapper.selectById(metadataId);
            if (metadata != null && metadata.getPosterFileNodeId() != null) {
                return metadata;
            }
        }
        return null;
    }
}
