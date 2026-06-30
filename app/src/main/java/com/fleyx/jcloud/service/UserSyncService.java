package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.dto.UserSyncConfigUpdateDto;
import com.fleyx.jcloud.model.po.UserSyncTask;
import com.fleyx.jcloud.model.vo.UserSyncConfigVo;
import com.fleyx.jcloud.model.vo.UserSyncTaskVo;

/**
 * 用户存储空间同步服务。
 */
public interface UserSyncService {

    /**
     * 提交立即同步任务。
     *
     * @param userId 用户 ID
     * @return 同步任务视图
     */
    UserSyncTaskVo submitImmediate(String userId);

    /**
     * 查询用户最新同步任务。
     *
     * @param userId 用户 ID
     * @return 同步任务视图，不存在返回 null
     */
    UserSyncTaskVo getLatestTask(String userId);

    /**
     * 查询用户同步配置。
     *
     * @param userId 用户 ID
     * @return 同步配置视图，不存在返回默认未启用配置
     */
    UserSyncConfigVo getConfig(String userId);

    /**
     * 更新用户同步配置。
     *
     * @param dto 同步配置更新 DTO
     * @return 更新后的同步配置视图
     */
    UserSyncConfigVo updateConfig(UserSyncConfigUpdateDto dto);

    /**
     * 创建定时同步任务记录（供调度器使用）。
     *
     * @param userId 用户 ID
     * @return 同步任务
     */
    UserSyncTask createScheduledTask(String userId);
}
