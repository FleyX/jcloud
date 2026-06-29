package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 上传冲突解决器。
 * <p>
 * 为普通上传、秒传与分片上传合并提供统一的冲突检测与处理，
 * 核心逻辑已收敛到 {@link FileConflictResolver} 与 {@link FileConflictOverwriteHandler}。
 */
@Component
@RequiredArgsConstructor
public class UploadConflictResolver {

    private final FileMapper fileMapper;
    private final FileConflictResolver conflictResolver;
    private final FileConflictOverwriteHandler overwriteHandler;

    /**
     * 根据策略解析最终应使用的文件名，并返回需要覆盖的旧节点。
     *
     * @param userId   用户 ID
     * @param parentId 目标父节点 ID
     * @param fileName 原始文件名
     * @param strategy 冲突解决策略编码
     * @return 冲突解决结果
     */
    public FileConflictResolver.ConflictResolution resolve(String userId, String parentId, String fileName, String strategy) {
        FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, parentId, fileName);
        ConflictStrategy conflictStrategy = ConflictStrategy.fromCode(strategy);
        if (existing != null && conflictStrategy == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标文件已存在");
        }
        return conflictResolver.resolveName(userId, parentId, fileName, existing,
                conflictStrategy == null ? ConflictStrategy.KEEP : conflictStrategy);
    }

    /**
     * 删除被覆盖的旧文件节点及物理数据，并扣减用户已用配额。
     *
     * @param existing 待覆盖的旧文件节点
     * @param user     当前用户
     */
    public void deleteExistingForOverwrite(FileNode existing, User user) {
        overwriteHandler.deleteExistingForOverwrite(existing, user);
    }
}
