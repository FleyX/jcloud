package com.fleyx.jcloud.util;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.po.FileNode;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件冲突检测与自动重命名辅助类。
 */
public final class FileConflictHelper {

    private FileConflictHelper() {
    }

    /**
     * 在指定父目录下查找同名节点。
     *
     * @param fileMapper 文件 Mapper
     * @param userId     用户 ID
     * @param parentId   父节点 ID
     * @param name       待查找名称
     * @return 同名节点，不存在返回 null
     */
    public static FileNode findSameName(FileMapper fileMapper, Long userId,
                                        Long parentId, String name) {
        return fileMapper.selectList(new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getUserId, userId)
                        .eq(FileNode::getParentId, parentId)
                        .eq(FileNode::getName, name)
                        .eq(FileNode::getDeleteAt, 0L))
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * 在指定父目录下生成自动重命名名称。
     * <p>
     * 命名规则：name.1.ext、name.2.ext，若无扩展名则为 name.1、name.2。
     * 取当前目录下已存在的最大后缀数字 + 1，避免重名。
     *
     * @param fileMapper   文件 Mapper
     * @param userId       用户 ID
     * @param parentId     父节点 ID
     * @param originalName 原始名称
     * @return 可用名称
     */
    public static String generateAutoRename(FileMapper fileMapper, Long userId,
                                            Long parentId, String originalName) {
        NameParts parts = splitName(originalName);
        int maxIndex = resolveMaxSuffixIndex(fileMapper, userId, parentId, parts);
        return parts.base() + "." + (maxIndex + 1) + parts.ext();
    }

    private static int resolveMaxSuffixIndex(FileMapper fileMapper, Long userId,
                                             Long parentId, NameParts parts) {
        String base = parts.base();
        String ext = parts.ext();
        String pattern = ext.isEmpty()
                ? "^" + java.util.regex.Pattern.quote(base) + "\\.(\\d+)$"
                : "^" + java.util.regex.Pattern.quote(base) + "\\.(\\d+)" + java.util.regex.Pattern.quote(ext) + "$";
        java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern);

        List<FileNode> siblings = fileMapper.selectList(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getUserId, userId)
                .eq(FileNode::getParentId, parentId)
                .eq(FileNode::getDeleteAt, 0L));

        int maxIndex = 0;
        for (FileNode node : siblings) {
            String name = node.getName();
            if (name == null) {
                continue;
            }
            Matcher matcher = regex.matcher(name);
            if (matcher.matches()) {
                maxIndex = Math.max(maxIndex, Integer.parseInt(matcher.group(1)));
            }
        }
        return maxIndex;
    }

    /**
     * 检查指定父目录下是否存在与给定名称匹配的节点（可排除指定节点）。
     *
     * @param fileMapper 文件 Mapper
     * @param userId     用户 ID
     * @param parentId   父节点 ID
     * @param name       待匹配名称
     * @param excludeId  排除的节点 ID
     * @return 是否存在冲突
     */
    public static boolean existsSameName(FileMapper fileMapper, Long userId,
                                         Long parentId, String name, Long excludeId) {
        Predicate<FileNode> filter = node -> !node.getId().equals(excludeId);
        FileNode conflict = findSameName(fileMapper, userId, parentId, name);
        return conflict != null && filter.test(conflict);
    }

    private static NameParts splitName(String name) {
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0) {
            return new NameParts(name.substring(0, dotIndex), name.substring(dotIndex));
        }
        return new NameParts(name, "");
    }

    private record NameParts(String base, String ext) {
    }
}
