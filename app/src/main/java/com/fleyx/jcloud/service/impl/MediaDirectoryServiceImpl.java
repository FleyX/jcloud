package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.MediaItemMapper;
import com.fleyx.jcloud.model.dto.MediaDirectorySaveDto;
import com.fleyx.jcloud.model.dto.MediaDirectoryUpdateDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.MediaItem;
import com.fleyx.jcloud.model.vo.MediaDirectoryVo;
import com.fleyx.jcloud.service.MediaDirectoryService;
import com.fleyx.jcloud.service.MediaScanService;
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
 * 视频目录管理服务实现。
 */
@Service
@RequiredArgsConstructor
public class MediaDirectoryServiceImpl implements MediaDirectoryService {

    private final MediaDirectoryMapper mediaDirectoryMapper;
    private final MediaItemMapper mediaItemMapper;
    private final FileMapper fileMapper;
    private final MediaScanService mediaScanService;

    @Override
    public List<MediaDirectoryVo> list(String userId) {
        List<MediaDirectory> directories = mediaDirectoryMapper.selectList(
                new LambdaQueryWrapper<MediaDirectory>()
                        .eq(MediaDirectory::getUserId, userId)
                        .orderByAsc(MediaDirectory::getCreateTime));
        Map<String, Long> countMap = mediaItemMapper.selectList(
                        new LambdaQueryWrapper<MediaItem>().eq(MediaItem::getUserId, userId))
                .stream().collect(Collectors.groupingBy(MediaItem::getDirectoryId, Collectors.counting()));
        return directories.stream().map(d -> toVo(d, countMap.getOrDefault(d.getId(), 0L))).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaDirectoryVo save(MediaDirectorySaveDto dto, String userId) {
        FileNode folder = fileMapper.selectById(dto.getFileNodeId());
        if (folder == null || !userId.equals(folder.getUserId()) || !"folder".equals(folder.getType())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "所选文件夹不存在");
        }
        Long exists = mediaDirectoryMapper.selectCount(new LambdaQueryWrapper<MediaDirectory>()
                .eq(MediaDirectory::getUserId, userId)
                .eq(MediaDirectory::getFileNodeId, dto.getFileNodeId()));
        if (exists > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "该文件夹已添加为视频目录");
        }
        validateCron(dto.getScanCron());

        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setFileNodeId(dto.getFileNodeId());
        directory.setName(dto.getName() == null || dto.getName().isBlank() ? folder.getName() : dto.getName());
        directory.setMediaType(dto.getMediaType());
        directory.setScanCron(dto.getScanCron());
        directory.setNextScanTime(computeNextScanTime(dto.getScanCron()));
        mediaDirectoryMapper.insert(directory);

        submitScanAfterCommit(directory.getId(), userId);
        return toVo(directory, 0L);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaDirectoryVo update(MediaDirectoryUpdateDto dto, String userId) {
        MediaDirectory directory = requireOwned(dto.getId(), userId);
        validateCron(dto.getScanCron());
        boolean typeChanged = !directory.getMediaType().equals(dto.getMediaType());

        directory.setName(dto.getName());
        directory.setMediaType(dto.getMediaType());
        directory.setScanCron(dto.getScanCron());
        directory.setNextScanTime(computeNextScanTime(dto.getScanCron()));
        mediaDirectoryMapper.updateById(directory);

        if (typeChanged) {
            // 类型变更后清空条目并重扫
            mediaItemMapper.delete(new LambdaQueryWrapper<MediaItem>().eq(MediaItem::getDirectoryId, directory.getId()));
            submitScanAfterCommit(directory.getId(), userId);
        }
        Long count = mediaItemMapper.selectCount(
                new LambdaQueryWrapper<MediaItem>().eq(MediaItem::getDirectoryId, directory.getId()));
        return toVo(directory, count);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id, String userId) {
        requireOwned(id, userId);
        mediaItemMapper.delete(new LambdaQueryWrapper<MediaItem>().eq(MediaItem::getDirectoryId, id));
        mediaDirectoryMapper.deleteById(id);
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
            throw new BusinessException(ResultCode.NOT_FOUND, "视频目录不存在");
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

    private MediaDirectoryVo toVo(MediaDirectory directory, Long itemCount) {
        MediaDirectoryVo vo = new MediaDirectoryVo();
        vo.setId(directory.getId());
        vo.setFileNodeId(directory.getFileNodeId());
        vo.setName(directory.getName());
        vo.setMediaType(directory.getMediaType());
        vo.setScanCron(directory.getScanCron());
        vo.setLastScanTime(directory.getLastScanTime());
        vo.setLastScanStatus(directory.getLastScanStatus());
        vo.setLastScanError(directory.getLastScanError());
        vo.setItemCount(itemCount);
        return vo;
    }
}
