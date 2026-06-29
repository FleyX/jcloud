package com.fleyx.jcloud.service;

import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.dto.FilePreCheckOperationDto;
import com.fleyx.jcloud.model.dto.FileRenameDto;
import com.fleyx.jcloud.model.vo.ConflictItemVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.OperationResultVo;

import java.util.List;

/**
 * 文件组织操作服务接口。
 * <p>
 * 负责重命名、创建文件夹、移动、复制及冲突解决。
 */
public interface FileOperationService {

    /**
     * 重命名文件或文件夹。
     *
     * @param dto    重命名参数
     * @param userId 用户 ID
     * @return 重命名后的节点视图
     */
    FileNodeVo rename(FileRenameDto dto, String userId);

    /**
     * 创建文件夹。
     *
     * @param dto    创建参数
     * @param userId 用户 ID
     * @return 文件夹节点视图
     */
    FileNodeVo createFolder(FileCreateFolderDto dto, String userId);

    /**
     * 预检移动/复制操作的冲突。
     *
     * @param dto    预检参数
     * @param userId 用户 ID
     * @return 冲突列表
     */
    List<ConflictItemVo> preCheckOperation(FilePreCheckOperationDto dto, String userId);

    /**
     * 批量移动文件/文件夹。
     *
     * @param dto    执行参数
     * @param userId 用户 ID
     * @return 操作结果
     */
    List<OperationResultVo> move(FileExecuteOperationDto dto, String userId);

    /**
     * 批量复制文件/文件夹。
     *
     * @param dto    执行参数
     * @param userId 用户 ID
     * @return 操作结果
     */
    List<OperationResultVo> copy(FileExecuteOperationDto dto, String userId);
}
