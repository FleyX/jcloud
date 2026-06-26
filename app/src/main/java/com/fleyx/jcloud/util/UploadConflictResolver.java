package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 上传冲突解决器。
 * <p>
 * 为普通上传、秒传与分片上传合并提供统一的冲突检测与处理。
 */
@Component
@RequiredArgsConstructor
public class UploadConflictResolver {

    private static final String TYPE_FOLDER = "folder";

    private final FileMapper fileMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final UserMapper userMapper;

    /**
     * 根据策略解析最终应使用的文件名，并返回需要覆盖的旧节点。
     *
     * @param userId   用户 ID
     * @param parentId 目标父节点 ID
     * @param fileName 原始文件名
     * @param strategy 冲突解决策略编码
     * @return 冲突解决结果
     */
    public ConflictResolution resolve(Long userId, Long parentId, String fileName, String strategy) {
        FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, parentId, fileName);
        if (existing == null) {
            return new ConflictResolution(fileName, null, false);
        }
        ConflictStrategy conflictStrategy = ConflictStrategy.fromCode(strategy);
        if (conflictStrategy == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标文件已存在");
        }
        return switch (conflictStrategy) {
            case SKIP -> new ConflictResolution(fileName, null, true);
            case OVERWRITE -> {
                if (TYPE_FOLDER.equals(existing.getType())) {
                    throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能覆盖文件夹");
                }
                yield new ConflictResolution(fileName, existing, false);
            }
            case AUTO_RENAME -> {
                String newName = FileConflictHelper.generateAutoRename(fileMapper, userId, parentId, fileName);
                yield new ConflictResolution(newName, null, false);
            }
        };
    }

    /**
     * 删除被覆盖的旧文件节点及物理数据，并扣减用户已用配额。
     *
     * @param existing 待覆盖的旧文件节点
     * @param user     当前用户
     */
    public void deleteExistingForOverwrite(FileNode existing, User user) {
        StorageSpace space = storageSpaceMapper.selectById(existing.getStorageSpaceId());
        Path physicalPath = FilePathUtil.resolvePhysicalPath(existing, space);
        fileMapper.deleteById(existing);
        long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        user.setUsedSpace(Math.max(0L, usedSpace - existing.getSize()));
        userMapper.updateById(user);
        try {
            Files.deleteIfExists(physicalPath);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "删除旧文件失败", e);
        }
    }

    /**
     * 冲突解决结果。
     *
     * @param finalName          最终使用的文件名
     * @param existingToReplace  需要覆盖的旧节点（无覆盖为 {@code null}）
     * @param skipped            是否跳过上传
     */
    public record ConflictResolution(String finalName, FileNode existingToReplace, boolean skipped) {
    }
}
