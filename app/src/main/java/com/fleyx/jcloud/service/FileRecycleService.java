package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.model.dto.FileDeleteDto;
import com.fleyx.jcloud.model.dto.FileExecuteRestoreDto;
import com.fleyx.jcloud.model.dto.FilePermanentDeleteDto;
import com.fleyx.jcloud.model.dto.FilePreCheckRestoreDto;
import com.fleyx.jcloud.model.vo.ConflictItemVo;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import com.fleyx.jcloud.model.vo.RecycleRecordVo;

import java.util.List;

/**
 * 文件回收站服务接口。
 */
public interface FileRecycleService {

    /**
     * 删除文件/文件夹到回收站。
     *
     * @param dto    删除参数
     * @param userId 用户 ID
     * @return 操作结果
     */
    List<OperationResultVo> deleteToTrash(FileDeleteDto dto, String userId);

    /**
     * 分页查询回收站列表。
     *
     * @param userId 用户 ID
     * @param pageNum  页码
     * @param pageSize 页大小
     * @return 分页结果
     */
    IPage<RecycleRecordVo> listTrash(Long pageNum, Long pageSize, String userId);

    /**
     * 恢复前冲突预检。
     *
     * @param dto    预检参数
     * @param userId 用户 ID
     * @return 冲突列表
     */
    List<ConflictItemVo> preCheckRestore(FilePreCheckRestoreDto dto, String userId);

    /**
     * 恢复文件/文件夹。
     *
     * @param dto    恢复参数
     * @param userId 用户 ID
     * @return 操作结果
     */
    List<OperationResultVo> restore(FileExecuteRestoreDto dto, String userId);

    /**
     * 永久删除回收站中的文件/文件夹。
     *
     * @param dto    永久删除参数
     * @param userId 用户 ID
     * @return 操作结果
     */
    List<OperationResultVo> permanentDelete(FilePermanentDeleteDto dto, String userId);
}
