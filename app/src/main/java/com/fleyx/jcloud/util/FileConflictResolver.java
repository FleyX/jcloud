package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.po.FileNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 统一文件冲突解决器（纯计算，不参与事务和 IO）。
 * <p>
 * 为上传、移动、复制、恢复提供一致的冲突策略解析与保留命名生成。
 */
@Component
@RequiredArgsConstructor
public class FileConflictResolver {

    private static final String TYPE_FOLDER = "folder";

    private final FileMapper fileMapper;

    /**
     * 根据策略解析最终应使用的名称。
     *
     * @param userId     用户 ID
     * @param parentId   目标父节点 ID
     * @param sourceName 源名称
     * @param existing   目标位置已存在的同名节点（不存在为 {@code null}）
     * @param strategy   冲突解决策略
     * @return 冲突解决结果
     */
    public ConflictResolution resolveName(String userId, String parentId, String sourceName,
                                          FileNode existing, ConflictStrategy strategy) {
        if (existing == null) {
            return new ConflictResolution(sourceName, null, false);
        }
        if (strategy == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标文件已存在");
        }
        return switch (strategy) {
            case SKIP -> new ConflictResolution(sourceName, null, true);
            case OVERWRITE -> {
                if (TYPE_FOLDER.equals(existing.getType())) {
                    throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能覆盖文件夹");
                }
                yield new ConflictResolution(sourceName, existing, false);
            }
            case KEEP -> {
                String newName = FileConflictHelper.generateKeepName(fileMapper, userId, parentId, sourceName);
                yield new ConflictResolution(newName, null, false);
            }
        };
    }

    /**
     * 按 name(n).ext 规则生成保留名称。
     *
     * @param userId     用户 ID
     * @param parentId   目标父节点 ID
     * @param sourceName 源名称
     * @return 可用名称
     */
    public String keepNameGenerator(String userId, String parentId, String sourceName) {
        return FileConflictHelper.generateKeepName(fileMapper, userId, parentId, sourceName);
    }

    /**
     * 冲突解决结果。
     *
     * @param finalName         最终使用的名称
     * @param existingToReplace 需要覆盖的旧节点（无覆盖为 {@code null}）
     * @param skipped           是否跳过
     */
    public record ConflictResolution(String finalName, FileNode existingToReplace, boolean skipped) {
    }
}
