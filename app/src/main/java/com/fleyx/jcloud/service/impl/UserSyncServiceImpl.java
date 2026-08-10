package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.enums.SyncTaskType;
import com.fleyx.jcloud.common.event.UserSyncSubmittedEvent;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserSyncConfigMapper;
import com.fleyx.jcloud.mapper.UserSyncTaskMapper;
import com.fleyx.jcloud.model.convert.UserSyncConfigConvert;
import com.fleyx.jcloud.model.convert.UserSyncTaskConvert;
import com.fleyx.jcloud.model.dto.UserSyncConfigUpdateDto;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.po.UserSyncConfig;
import com.fleyx.jcloud.model.po.UserSyncTask;
import com.fleyx.jcloud.model.vo.UserSyncConfigVo;
import com.fleyx.jcloud.model.vo.UserSyncTaskVo;
import com.fleyx.jcloud.service.UserSyncService;
import com.fleyx.jcloud.service.support.SyncTaskSupport;
import com.fleyx.jcloud.service.support.UserSpaceSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户存储空间同步服务实现。
 */
@Service
@RequiredArgsConstructor
public class UserSyncServiceImpl implements UserSyncService {

    private static final int DEFAULT_ENABLED = 0;

    private final StorageSpaceMapper storageSpaceMapper;
    private final UserSyncTaskMapper userSyncTaskMapper;
    private final UserSyncConfigMapper userSyncConfigMapper;
    private final UserSyncTaskConvert userSyncTaskConvert;
    private final UserSyncConfigConvert userSyncConfigConvert;
    private final ApplicationEventPublisher eventPublisher;
    private final UserSpaceSupport userSpaceSupport;
    private final SyncTaskSupport syncTaskSupport;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSyncTaskVo submitImmediate(String userId) {
        User user = userSpaceSupport.requireUser(userId);
        requireEnabledSpace(user.getStorageSpaceId());
        rejectIfSyncRunning(userId);

        UserSyncTask task = buildTask(userId, SyncTaskType.MANUAL.getValue());
        userSyncTaskMapper.insert(task);

        eventPublisher.publishEvent(new UserSyncSubmittedEvent(this, task.getId()));
        return userSyncTaskConvert.poToVo(task);
    }

    @Override
    public UserSyncTaskVo getLatestTask(String userId) {
        UserSyncTask task = userSyncTaskMapper.selectLatestByUserId(userId);
        if (task == null) {
            return null;
        }
        return userSyncTaskConvert.poToVo(task);
    }

    @Override
    public UserSyncConfigVo getConfig(String userId) {
        UserSyncConfig config = userSyncConfigMapper.selectById(userId);
        if (config == null) {
            UserSyncConfigVo vo = new UserSyncConfigVo();
            vo.setUserId(userId);
            vo.setCronExpr("");
            vo.setEnabled(DEFAULT_ENABLED);
            return vo;
        }
        return userSyncConfigConvert.poToVo(config);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSyncConfigVo updateConfig(UserSyncConfigUpdateDto dto) {
        String userId = dto.getUserId();
        userSpaceSupport.requireUser(userId);

        CronExpression expression = syncTaskSupport.parseCron(dto.getCronExpr());
        LocalDateTime nextSyncTime = expression.next(LocalDateTime.now());
        if (nextSyncTime == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "cron 表达式在未来没有可执行的时间");
        }

        UserSyncConfig config = userSyncConfigMapper.selectById(userId);
        if (config == null) {
            config = new UserSyncConfig();
            config.setUserId(userId);
            config.setCronExpr(dto.getCronExpr());
            config.setEnabled(dto.getEnabled());
            config.setNextSyncTime(nextSyncTime);
            userSyncConfigMapper.insert(config);
        } else {
            config.setCronExpr(dto.getCronExpr());
            config.setEnabled(dto.getEnabled());
            config.setNextSyncTime(nextSyncTime);
            userSyncConfigMapper.updateById(config);
        }
        return userSyncConfigConvert.poToVo(config);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSyncTask createScheduledTask(String userId) {
        UserSyncTask task = buildTask(userId, SyncTaskType.SCHEDULED.getValue());
        userSyncTaskMapper.insert(task);
        return task;
    }

    private UserSyncTask buildTask(String userId, String type) {
        UserSyncTask task = syncTaskSupport.buildPendingTask(UserSyncTask::new, type);
        task.setUserId(userId);
        return task;
    }

    private StorageSpace requireEnabledSpace(String spaceId) {
        if (spaceId == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户未绑定存储空间");
        }
        StorageSpace space = storageSpaceMapper.selectById(spaceId);
        if (space == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        if (!Integer.valueOf(1).equals(space.getStatus())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "存储空间已被禁用");
        }
        return space;
    }

    private void rejectIfSyncRunning(String userId) {
        if (syncTaskSupport.hasActiveTask(userSyncTaskMapper, UserSyncTask::getUserId, userId,
                UserSyncTask::getStatus, SyncTaskSupport.ACTIVE_STATUSES)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户存在进行中的同步任务");
        }
    }
}
