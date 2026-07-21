package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件路径共享支撑组件。
 * <p>
 * 收敛各文件服务中重复出现的 namePath 解析与祖先名称查询逻辑。
 */
@Component
@RequiredArgsConstructor
public class FilePathSupport {

    private final FileMapper fileMapper;

    /**
     * 解析节点的名称路径（namePath），节点为 {@code null} 时返回根路径 "/"。
     *
     * @param node   节点
     * @param userId 用户 ID
     * @return 名称路径
     */
    public String resolveNamePath(FileNode node, String userId) {
        if (node == null) {
            return "/";
        }
        Set<String> ancestorIds = FilePathUtil.extractAncestorIds(List.of(node));
        Map<String, String> cache = queryAncestorNames(userId, ancestorIds);
        return FilePathUtil.resolveNamePath(node, FilePathUtil.contextOf(null, userId, cache));
    }

    /**
     * 批量查询祖先节点名称，仅保留当前用户的节点。
     *
     * @param userId      用户 ID
     * @param ancestorIds 祖先节点 ID 集合
     * @return 节点 ID 到名称的映射
     */
    public Map<String, String> queryAncestorNames(String userId, Set<String> ancestorIds) {
        Map<String, String> cache = new HashMap<>();
        if (ancestorIds.isEmpty()) {
            return cache;
        }
        List<FileNode> ancestors = fileMapper.selectBatchIds(ancestorIds);
        for (FileNode ancestor : ancestors) {
            if (userId.equals(ancestor.getUserId())) {
                cache.put(ancestor.getId(), ancestor.getName());
            }
        }
        return cache;
    }
}
