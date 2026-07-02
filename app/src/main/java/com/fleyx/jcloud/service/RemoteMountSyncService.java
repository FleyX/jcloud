package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.model.dto.RemoteMountSyncConfigUpdateDto;
import com.fleyx.jcloud.model.dto.RemoteSyncTaskPageQueryDto;
import com.fleyx.jcloud.model.po.RemoteSyncTask;
import com.fleyx.jcloud.model.vo.RemoteSyncTaskVo;

/**
 * 远程挂载同步服务。
 */
public interface RemoteMountSyncService {

    /**
     * 提交立即同步任务。
     *
     * @param remoteMountId 远程挂载 ID
     * @param userId        用户 ID
     * @return 同步任务视图
     */
    RemoteSyncTaskVo submitImmediate(String remoteMountId, String userId);

    /**
     * 查询挂载点最新同步任务。
     *
     * @param remoteMountId 远程挂载 ID
     * @param userId        用户 ID
     * @return 同步任务视图，不存在返回 null
     */
    RemoteSyncTaskVo getLatestTask(String remoteMountId, String userId);

    /**
     * 分页查询挂载点的同步任务历史。
     *
     * @param remoteMountId 远程挂载 ID
     * @param userId        用户 ID
     * @param dto           分页条件
     * @return 分页结果
     */
    IPage<RemoteSyncTaskVo> pageTasks(String remoteMountId, String userId, RemoteSyncTaskPageQueryDto dto);

    /**
     * 更新挂载点同步配置。
     *
     * @param remoteMountId 远程挂载 ID
     * @param dto           同步配置 DTO
     * @param userId        用户 ID
     */
    void updateConfig(String remoteMountId, RemoteMountSyncConfigUpdateDto dto, String userId);

    /**
     * 创建定时同步任务记录（供调度器使用）。
     *
     * @param remoteMountId 远程挂载 ID
     * @return 同步任务
     */
    RemoteSyncTask createScheduledTask(String remoteMountId);
}
