package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.SyncTaskStatus;
import com.fleyx.jcloud.common.event.UserMigrationSubmittedEvent;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserMigrationTaskMapper;
import com.fleyx.jcloud.model.convert.UserMigrationTaskConvert;
import com.fleyx.jcloud.model.dto.UserMigrationSubmitDto;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.po.UserMigrationTask;
import com.fleyx.jcloud.model.vo.UserMigrationTaskVo;
import com.fleyx.jcloud.service.UserMigrationService;
import com.fleyx.jcloud.service.support.SyncTaskSupport;
import com.fleyx.jcloud.service.support.UserSpaceSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户存储空间迁移服务实现。
 */
@Service
@RequiredArgsConstructor
public class UserMigrationServiceImpl implements UserMigrationService {

    private final UserMapper userMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final UserSpaceSupport userSpaceSupport;
    private final SyncTaskSupport syncTaskSupport;
    private final UserMigrationTaskMapper userMigrationTaskMapper;
    private final UserMigrationTaskConvert userMigrationTaskConvert;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserMigrationTaskVo submitMigration(UserMigrationSubmitDto dto) {
        User user = userSpaceSupport.requireUser(dto.getUserId());
        if (user.getStorageSpaceId() == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户未绑定存储空间");
        }
        if (user.getStorageSpaceId().equals(dto.getTargetSpaceId())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标存储空间不能与当前存储空间相同");
        }

        StorageSpace sourceSpace = requireSpace(user.getStorageSpaceId());
        StorageSpace targetSpace = requireSpace(dto.getTargetSpaceId());
        Long newQuota = dto.getNewQuota() != null ? dto.getNewQuota() : user.getQuota();
        validateTargetSpaceEnough(sourceSpace, targetSpace, newQuota);
        rejectIfMigrationInProgress(dto.getUserId());

        user.setReadOnly(1);
        userMapper.updateById(user);

        UserMigrationTask task = new UserMigrationTask();
        task.setUserId(dto.getUserId());
        task.setSourceSpaceId(sourceSpace.getId());
        task.setTargetSpaceId(targetSpace.getId());
        task.setNewQuota(newQuota);
        task.setStatus(SyncTaskStatus.PENDING.getValue());
        task.setTotalBytes(0L);
        task.setMigratedBytes(0L);
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        task.setDeleteAt(0L);
        userMigrationTaskMapper.insert(task);

        eventPublisher.publishEvent(new UserMigrationSubmittedEvent(this, task.getId()));

        return userMigrationTaskConvert.poToVo(task);
    }

    @Override
    public UserMigrationTaskVo getLatestTaskByUserId(String userId) {
        LambdaQueryWrapper<UserMigrationTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserMigrationTask::getUserId, userId);
        wrapper.eq(UserMigrationTask::getDeleteAt, 0L);
        wrapper.orderByDesc(UserMigrationTask::getCreateTime);
        wrapper.last("LIMIT 1");
        UserMigrationTask task = userMigrationTaskMapper.selectOne(wrapper);
        if (task == null) {
            return null;
        }
        return userMigrationTaskConvert.poToVo(task);
    }

    private StorageSpace requireSpace(String spaceId) {
        StorageSpace space = storageSpaceMapper.selectById(spaceId);
        if (space == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        if (space.getStatus() == null || space.getStatus() != 1) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "存储空间未启用");
        }
        return space;
    }

    private void validateTargetSpaceEnough(StorageSpace sourceSpace, StorageSpace targetSpace, Long newQuota) {
        long usedSpace = sourceSpace.getUsedSpace() == null ? 0L : sourceSpace.getUsedSpace();
        long targetCapacity = targetSpace.getCapacity() == null ? 0L : targetSpace.getCapacity();
        long targetUsed = targetSpace.getUsedSpace() == null ? 0L : targetSpace.getUsedSpace();
        long available = targetCapacity - targetUsed;
        if (usedSpace > available || newQuota > available) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标存储空间容量不足");
        }
    }

    private void rejectIfMigrationInProgress(String userId) {
        if (syncTaskSupport.hasActiveTask(userMigrationTaskMapper, UserMigrationTask::getUserId, userId,
                UserMigrationTask::getStatus, SyncTaskSupport.ACTIVE_STATUSES)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户存在进行中的迁移任务");
        }
    }
}
