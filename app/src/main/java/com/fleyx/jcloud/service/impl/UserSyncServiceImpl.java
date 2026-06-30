package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.event.UserSyncSubmittedEvent;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
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
import com.fleyx.jcloud.util.IdUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户存储空间同步服务实现。
 */
@Service
@RequiredArgsConstructor
public class UserSyncServiceImpl implements UserSyncService {

    private static final String TYPE_MANUAL = "manual";
    private static final String TYPE_SCHEDULED = "scheduled";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final int DEFAULT_ENABLED = 0;

    private final UserMapper userMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final UserSyncTaskMapper userSyncTaskMapper;
    private final UserSyncConfigMapper userSyncConfigMapper;
    private final UserSyncTaskConvert userSyncTaskConvert;
    private final UserSyncConfigConvert userSyncConfigConvert;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserSyncTaskVo submitImmediate(String userId) {
        User user = requireUser(userId);
        requireEnabledSpace(user.getStorageSpaceId());
        rejectIfSyncRunning(userId);

        UserSyncTask task = new UserSyncTask();
        task.setId(IdUtil.nextId());
        task.setUserId(userId);
        task.setType(TYPE_MANUAL);
        task.setStatus(STATUS_PENDING);
        task.setTotalCount(0L);
        task.setSuccessCount(0L);
        task.setFailCount(0L);
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
        requireUser(userId);

        CronExpression expression = parseCron(dto.getCronExpr());
        LocalDateTime nextSyncTime = expression.next(LocalDateTime.now());

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
        UserSyncTask task = new UserSyncTask();
        task.setId(IdUtil.nextId());
        task.setUserId(userId);
        task.setType(TYPE_SCHEDULED);
        task.setStatus(STATUS_PENDING);
        task.setTotalCount(0L);
        task.setSuccessCount(0L);
        task.setFailCount(0L);
        userSyncTaskMapper.insert(task);
        return task;
    }

    private User requireUser(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
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
        LambdaQueryWrapper<UserSyncTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserSyncTask::getUserId, userId);
        wrapper.in(UserSyncTask::getStatus, List.of(STATUS_PENDING, STATUS_RUNNING));
        wrapper.eq(UserSyncTask::getDeleteAt, 0L);
        if (userSyncTaskMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户存在进行中的同步任务");
        }
    }

    private CronExpression parseCron(String cronExpr) {
        try {
            return CronExpression.parse(cronExpr);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "cron 表达式格式错误: " + e.getMessage());
        }
    }
}
