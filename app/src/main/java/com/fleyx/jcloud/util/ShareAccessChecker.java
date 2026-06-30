package com.fleyx.jcloud.util;

import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.ShareItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分享文件访问权限检查器。
 */
@Component
@RequiredArgsConstructor
public class ShareAccessChecker {

    private static final String TYPE_FOLDER = "folder";
    private static final String PATH_SEPARATOR = ".";

    private final FileMapper fileMapper;

    /**
     * 检查指定文件节点是否可通过分享访问。
     *
     * @param fileNodeId 文件节点 ID
     * @param items      分享项列表
     * @return 是否可访问
     */
    public boolean isAccessible(String fileNodeId, List<ShareItem> items) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        Set<String> itemIds = items.stream()
                .map(ShareItem::getFileNodeId)
                .collect(Collectors.toSet());
        if (itemIds.contains(fileNodeId)) {
            return true;
        }
        FileNode node = fileMapper.selectById(fileNodeId);
        if (node == null) {
            return false;
        }
        Set<String> folderIds = filterFolderIds(itemIds);
        if (folderIds.isEmpty()) {
            return false;
        }
        return containsAnyAncestor(node.getPath(), folderIds);
    }

    /**
     * 从分享项中提取顶层文件夹 ID 集合。
     *
     * @param items 分享项列表
     * @return 顶层文件夹 ID 集合
     */
    public Set<String> extractTopFolderIds(List<ShareItem> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> itemIds = items.stream()
                .map(ShareItem::getFileNodeId)
                .collect(Collectors.toSet());
        return filterFolderIds(itemIds);
    }

    private Set<String> filterFolderIds(Set<String> nodeIds) {
        if (nodeIds.isEmpty()) {
            return Collections.emptySet();
        }
        List<FileNode> nodes = fileMapper.selectBatchIds(nodeIds);
        return nodes.stream()
                .filter(node -> TYPE_FOLDER.equals(node.getType()))
                .map(FileNode::getId)
                .collect(Collectors.toSet());
    }

    private boolean containsAnyAncestor(String path, Set<String> candidateIds) {
        if (path == null || path.isBlank() || candidateIds.isEmpty()) {
            return false;
        }
        for (String ancestorId : path.split("\\" + PATH_SEPARATOR)) {
            if (candidateIds.contains(ancestorId)) {
                return true;
            }
        }
        return false;
    }
}
