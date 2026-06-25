package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.dto.UserMigrationSubmitDto;
import com.fleyx.jcloud.model.vo.UserMigrationTaskVo;

/**
 * 用户存储空间迁移服务。
 */
public interface UserMigrationService {

    /**
     * 提交用户存储空间迁移任务。
     *
     * @param dto 迁移提交 DTO
     * @return 迁移任务视图
     */
    UserMigrationTaskVo submitMigration(UserMigrationSubmitDto dto);

    /**
     * 查询用户最新的迁移任务。
     *
     * @param userId 用户 ID
     * @return 迁移任务视图，不存在时返回 null
     */
    UserMigrationTaskVo getLatestTaskByUserId(Long userId);
}
