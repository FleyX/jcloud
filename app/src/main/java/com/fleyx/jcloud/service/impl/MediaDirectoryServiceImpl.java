package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.MediaType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.model.dto.MediaDirectorySaveDto;
import com.fleyx.jcloud.model.dto.MediaDirectoryUpdateDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaDirectorySource;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.vo.MediaDirectoryVo;
import com.fleyx.jcloud.service.MediaDirectoryService;
import com.fleyx.jcloud.service.MediaScanService;
import com.fleyx.jcloud.service.support.MediaDirectorySourceSupport;
import com.fleyx.jcloud.service.support.MediaItemVoSupport;
import com.fleyx.jcloud.service.support.MediaSeriesSupport;
import com.fleyx.jcloud.service.support.MediaSubtitleSupport;
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
 */
@Service
@RequiredArgsConstructor
public class MediaDirectoryServiceImpl implements MediaDirectoryService {

    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final MediaItemMapper mediaItemMapper;
    private final MediaScanService mediaScanService;
    private final MediaSeriesSupport mediaSeriesSupport;
    private final MediaDirectorySourceSupport sourceSupport;
    private final MediaItemVoSupport mediaItemVoSupport;
    private final MediaSubtitleSupport mediaSubtitleSupport;
    private final MediaTvCascadeSupport mediaTvCascadeSupport;

    @Override
    public List<MediaDirectoryVo> list(String userId) {
        List<MediaDirectory> directories = mediaDirectoryMapper.selectList(
                new LambdaQueryWrapper<MediaDirectory>()
                        .eq(MediaDirectory::getUserId, userId)
                        .orderByAsc(MediaDirectory::getCreateTime));
        Map<String, Long> countMap = mediaItemMapper.selectList(
                        new LambdaQueryWrapper<MediaItem>().eq(MediaItem::getUserId, userId))
                .stream().collect(Collectors.groupingBy(MediaItem::getDirectoryId, Collectors.counting()));
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
                // 被移除来源目录下的条目（含播放进度）全部删除
                List<String> removedItemIds = mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                                .eq(MediaItem::getDirectoryId, directory.getId())
                                .in(MediaItem::getSourceId, removed.stream().map(MediaDirectorySource::getId).toList()))
                        .stream().map(MediaItem::getId).toList();
                mediaItemMapper.delete(new LambdaQueryWrapper<MediaItem>()
                        .eq(MediaItem::getDirectoryId, directory.getId())
                        .in(MediaItem::getSourceId, removed.stream().map(MediaDirectorySource::getId).toList()));
                mediaSubtitleSupport.deleteByItemIds(removedItemIds);
                mediaSeriesSupport.cleanupOrphans(userId);
                if (MediaType.TV.getCode().equals(directory.getMediaType())) {
                    // 电视库新模型：被移除来源目录下的剧级联删除（issue #17）
                    mediaTvCascadeSupport.deleteByDirectoryAndSourceIds(directory.getId(),
                            removed.stream().map(MediaDirectorySource::getId).toList());
                }
            }
            submitForceScanAfterCommit(directory.getId(), userId);
        }
        Long count = mediaItemMapper.selectCount(
                new LambdaQueryWrapper<MediaItem>().eq(MediaItem::getDirectoryId, directory.getId()));
        return toVo(directory, count, sourceSupport.listByDirectoryId(directory.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id, String userId) {
        requireOwned(id, userId);
        mediaScanService.requestCancel(id);
        List<String> itemIds = mediaItemMapper.selectList(new LambdaQueryWrapper<MediaItem>()
                        .eq(MediaItem::getDirectoryId, id))
                .stream().map(MediaItem::getId).toList();
        mediaItemMapper.delete(new LambdaQueryWrapper<MediaItem>().eq(MediaItem::getDirectoryId, id));
        mediaSubtitleSupport.deleteByItemIds(itemIds);
        sourceSupport.deleteByDirectoryId(id);
        mediaSeriesSupport.cleanupOrphans(userId);
        // 电视库新模型：库内剧集全部级联删除（issue #17）
        mediaTvCascadeSupport.deleteByDirectoryId(id);
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
     * 解析媒体库封面：库内最新添加且元数据有海报的条目；
     * 其他类型库无海报条目时用最新条目的预览缩略图兜底。
     */
    private String resolveCoverPosterUrl(MediaDirectory directory) {
        MediaItem posterItem = mediaItemMapper.selectLatestPosterItem(directory.getId());
        if (posterItem != null) {
            return mediaItemVoSupport.metadataPosterUrl(posterItem.getMetadataId());
        }
        if (!MediaType.OTHER.getCode().equals(directory.getMediaType())) {
            return null;
        }
        MediaItem latest = mediaItemMapper.selectOne(new LambdaQueryWrapper<MediaItem>()
                .eq(MediaItem::getDirectoryId, directory.getId())
                .orderByDesc(MediaItem::getFileLastModified)
                .last("limit 1"));
        return latest == null ? null : mediaItemVoSupport.filePreviewPosterUrl(latest.getFileNodeId());
    }
}
