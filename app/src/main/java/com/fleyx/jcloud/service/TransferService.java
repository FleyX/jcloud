package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.vo.TransferTaskVo;

import java.util.List;

/**
 * 跨来源传输服务：本地与远程挂载之间的复制/移动。
 */
public interface TransferService {

    /**
     * 创建跨来源传输任务（预检同步完成，执行异步进行）。
     *
     * @param dto    操作参数（与移动/复制接口一致）
     * @param opType 操作类型：copy / move
     * @param userId 用户 ID
     * @return 传输任务
     */
    TransferTaskVo createTransfer(FileExecuteOperationDto dto, String opType, String userId);

    /**
     * 查询传输任务详情。
     *
     * @param taskId 任务 ID
     * @param userId 用户 ID
     * @return 传输任务
     */
    TransferTaskVo getTask(String taskId, String userId);

    /**
     * 查询用户最近的传输任务（含进行中），按创建时间倒序。
     *
     * @param userId 用户 ID
     * @param limit  最大条数
     * @return 传输任务列表
     */
    List<TransferTaskVo> listRecent(String userId, int limit);

    /**
     * 取消传输任务：PENDING 直接取消，RUNNING 置为取消中（当前文件传完后停止）。
     *
     * @param taskId 任务 ID
     * @param userId 用户 ID
     */
    void cancel(String taskId, String userId);

    /**
     * 轮询等待任务到达终态（供 WebDAV 等同步语义调用方使用）。
     *
     * @param taskId 任务 ID
     * @param userId 用户 ID
     * @return 终态任务
     */
    TransferTaskVo waitTerminal(String taskId, String userId);
}
