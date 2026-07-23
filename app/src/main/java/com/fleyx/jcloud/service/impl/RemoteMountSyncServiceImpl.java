package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.SyncTaskStatus;
import com.fleyx.jcloud.common.enums.SyncTaskType;
import com.fleyx.jcloud.common.event.RemoteMountSubmittedEvent;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.mapper.RemoteSyncTaskMapper;
import com.fleyx.jcloud.model.convert.RemoteSyncTaskConvert;
import com.fleyx.jcloud.model.dto.RemoteMountSyncConfigUpdateDto;
import com.fleyx.jcloud.model.dto.RemoteSyncTaskPageQueryDto;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.po.RemoteSyncTask;
import com.fleyx.jcloud.model.vo.RemoteSyncTaskVo;
import com.fleyx.jcloud.service.RemoteMountSyncService;
import com.fleyx.jcloud.service.support.RemoteMountSupport;
import com.fleyx.jcloud.service.support.SyncTaskSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 远程挂载同步服务实现。
 */
@Service
@RequiredArgsConstructor
public class RemoteMountSyncServiceImpl implements RemoteMountSyncService {

    private final RemoteMountMapper remoteMountMapper;
    private final RemoteSyncTaskMapper remoteSyncTaskMapper;
    private final RemoteSyncTaskConvert remoteSyncTaskConvert;
    private final ApplicationEventPublisher eventPublisher;
    private final RemoteMountSupport remoteMountSupport;
    private final SyncTaskSupport syncTaskSupport;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RemoteSyncTaskVo submitImmediate(String remoteMountId, String userId) {
        remoteMountSupport.requireOwnedMount(remoteMountId, userId);
        rejectIfSyncRunning(remoteMountId);
        removePendingTask(remoteMountId);

        RemoteSyncTask task = createTask(remoteMountId, SyncTaskType.MANUAL.getValue());
        remoteSyncTaskMapper.insert(task);
        eventPublisher.publishEvent(new RemoteMountSubmittedEvent(this, task.getId()));
        return remoteSyncTaskConvert.poToVo(task);
    }

    @Override
    public RemoteSyncTaskVo getLatestTask(String remoteMountId, String userId) {
        remoteMountSupport.requireOwnedMount(remoteMountId, userId);
        RemoteSyncTask task = remoteSyncTaskMapper.selectLatestByMountId(remoteMountId);
        if (task == null) {
            return null;
        }
        return remoteSyncTaskConvert.poToVo(task);
    }

    @Override
    public IPage<RemoteSyncTaskVo> pageTasks(String remoteMountId, String userId, RemoteSyncTaskPageQueryDto dto) {
        LambdaQueryWrapper<RemoteSyncTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RemoteSyncTask::getDeleteAt, 0L);
        wrapper.orderByDesc(RemoteSyncTask::getCreateTime);

        if (remoteMountId != null) {
            remoteMountSupport.requireOwnedMount(remoteMountId, userId);
            wrapper.eq(RemoteSyncTask::getRemoteMountId, remoteMountId);
        } else {
            List<String> mountIds = listUserMountIds(userId);
            if (mountIds.isEmpty()) {
                Page<RemoteSyncTask> emptyPage = new Page<>(dto.getPageNum(), dto.getPageSize());
                emptyPage.setRecords(List.of());
                emptyPage.setTotal(0L);
                return emptyPage.convert(remoteSyncTaskConvert::poToVo);
            }
            wrapper.in(RemoteSyncTask::getRemoteMountId, mountIds);
        }

        Page<RemoteSyncTask> page = new Page<>(dto.getPageNum(), dto.getPageSize());
        Page<RemoteSyncTask> result = remoteSyncTaskMapper.selectPage(page, wrapper);
        return result.convert(remoteSyncTaskConvert::poToVo);
    }

    private List<String> listUserMountIds(String userId) {
        LambdaQueryWrapper<RemoteMount> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RemoteMount::getUserId, userId);
        wrapper.eq(RemoteMount::getDeleteAt, 0L);
        wrapper.select(RemoteMount::getId);
        return remoteMountMapper.selectList(wrapper).stream()
                .map(RemoteMount::getId)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(String remoteMountId, RemoteMountSyncConfigUpdateDto dto, String userId) {
        RemoteMount mount = remoteMountSupport.requireOwnedMount(remoteMountId, userId);
        CronExpression expression = syncTaskSupport.parseCron(dto.getCronExpr());
        LocalDateTime nextSyncTime = dto.getEnabled() == 1 ? expression.next(LocalDateTime.now()) : null;

        LambdaUpdateWrapper<RemoteMount> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(RemoteMount::getId, remoteMountId);
        wrapper.set(RemoteMount::getCronExpr, dto.getCronExpr());
        wrapper.set(RemoteMount::getEnabled, dto.getEnabled());
        wrapper.set(RemoteMount::getNextSyncTime, nextSyncTime);
        wrapper.set(RemoteMount::getUpdateTime, LocalDateTime.now());
        remoteMountMapper.update(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RemoteSyncTask createScheduledTask(String remoteMountId) {
        RemoteSyncTask task = createTask(remoteMountId, SyncTaskType.SCHEDULED.getValue());
        remoteSyncTaskMapper.insert(task);
        return task;
    }

    private RemoteSyncTask createTask(String remoteMountId, String type) {
        RemoteSyncTask task = syncTaskSupport.buildPendingTask(RemoteSyncTask::new, type);
        task.setRemoteMountId(remoteMountId);
        return task;
    }

    private void rejectIfSyncRunning(String remoteMountId) {
        if (syncTaskSupport.hasActiveTask(remoteSyncTaskMapper, RemoteSyncTask::getRemoteMountId, remoteMountId,
                RemoteSyncTask::getStatus, List.of(SyncTaskStatus.RUNNING.getValue()))) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "存在进行中的同步任务");
        }
    }

    private void removePendingTask(String remoteMountId) {
        LambdaQueryWrapper<RemoteSyncTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RemoteSyncTask::getRemoteMountId, remoteMountId);
        wrapper.eq(RemoteSyncTask::getStatus, SyncTaskStatus.PENDING.getValue());
        wrapper.eq(RemoteSyncTask::getDeleteAt, 0L);
        List<RemoteSyncTask> pendingTasks = remoteSyncTaskMapper.selectList(wrapper);
        for (RemoteSyncTask task : pendingTasks) {
            remoteSyncTaskMapper.deleteById(task.getId());
        }
    }
}
