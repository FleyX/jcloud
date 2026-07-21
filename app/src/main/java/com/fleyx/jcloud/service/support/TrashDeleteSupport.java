package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import com.fleyx.jcloud.service.RemoteFileOperationService;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 回收站入站（删除到回收站）支撑组件。
 */
@Component
@RequiredArgsConstructor
public class TrashDeleteSupport {

    private static final String TYPE_FILE = "file";
    private static final String TYPE_FOLDER = "folder";

    private final FileMapper fileMapper;
    private final RecycleRecordMapper recycleRecordMapper;
    private final RemoteFileOperationService remoteFileOperationService;
    private final FileNodeSupport fileNodeSupport;
    private final FilePathSupport filePathSupport;
    private final UserSpaceSupport userSpaceSupport;

    /**
     * 批量删除节点到回收站，整个列表在一个事务内执行。
     *
     * @param ids    节点 ID 列表
     * @param userId 用户 ID
     * @return 操作结果列表
     */
    @Transactional(rollbackFor = Exception.class)
    public List<OperationResultVo> doDeleteToTrash(List<String> ids, String userId) {
        List<OperationResultVo> results = new ArrayList<>();
        for (String id : ids) {
            results.add(deleteOneToTrash(id, userId));
        }
        return results;
    }

    private OperationResultVo deleteOneToTrash(String id, String userId) {
        FileNode node = fileNodeSupport.getOwnedNode(id, userId);
        if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            remoteFileOperationService.delete(node, userId);
            return successResult(node.getId(), node.getName());
        }
        User user = userSpaceSupport.requireUser(userId);
        StorageSpace space = userSpaceSupport.requireSpace(user);
        String username = user.getUsername();

        List<FileNode> subtree = collectSubtree(node, userId);
        long totalSize = subtree.stream()
                .filter(n -> TYPE_FILE.equals(n.getType()))
                .mapToLong(n -> n.getSize() == null ? 0L : n.getSize())
                .sum();

        String originalPathName = filePathSupport.resolveNamePath(node, userId);
        RecycleRecord record = buildRecycleRecord(userId, node, originalPathName, totalSize);
        recycleRecordMapper.insert(record);

        Path trashRoot = FilePathUtil.resolveTrashRoot(space, username, record.getId());
        moveFilesToTrash(node, subtree, space, username, trashRoot);

        List<String> nodeIds = subtree.stream().map(FileNode::getId).toList();
        fileMapper.physicalDeleteByIds(nodeIds);

        OperationResultVo result = successResult(node.getId(), node.getName());
        result.setNodeId(record.getId());
        return result;
    }

    private List<FileNode> collectSubtree(FileNode node, String userId) {
        List<FileNode> nodes = new ArrayList<>();
        nodes.add(node);
        if (TYPE_FOLDER.equals(node.getType())) {
            List<FileNode> descendants = fileMapper.selectByIdPathPrefix(userId, node.getPath(), node.getId());
            for (FileNode descendant : descendants) {
                if (!descendant.getId().equals(node.getId())) {
                    nodes.add(descendant);
                }
            }
        }
        return nodes;
    }

    private void moveFilesToTrash(FileNode topNode, List<FileNode> subtree, StorageSpace space,
                                  String username, Path trashRoot) {
        String userId = topNode.getUserId();
        String topNamePath = filePathSupport.resolveNamePath(topNode, userId);
        if (TYPE_FOLDER.equals(topNode.getType())) {
            try {
                Files.createDirectories(trashRoot.resolve(topNode.getName()));
            } catch (IOException e) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR,
                        "创建回收站文件夹占位失败: " + topNode.getName());
            }
        }
        for (FileNode node : subtree) {
            if (!TYPE_FILE.equals(node.getType())) {
                continue;
            }
            String fileNamePath = filePathSupport.resolveNamePath(node, userId);
            String relative = computeTrashRelativePath(topNode, topNamePath, node, fileNamePath);
            Path source = FilePathUtil.resolvePhysicalPath(space, username, fileNamePath);
            if (!Files.exists(source)) {
                continue;
            }
            Path target = trashRoot.resolve(relative);
            try {
                Files.createDirectories(target.getParent());
                Files.move(source, target);
            } catch (IOException e) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR,
                        "移动文件到回收站失败: " + node.getName());
            }
        }
        deleteIfEmpty(FilePathUtil.resolvePhysicalPath(space, username, topNamePath));
    }

    private String computeTrashRelativePath(FileNode topNode, String topNamePath,
                                            FileNode fileNode, String fileNamePath) {
        if (topNode.getId().equals(fileNode.getId())) {
            return fileNode.getName();
        }
        if (fileNamePath.startsWith(topNamePath + "/")) {
            return topNode.getName() + fileNamePath.substring(topNamePath.length());
        }
        return FilePathUtil.stripLeadingSlash(fileNamePath);
    }

    private void deleteIfEmpty(Path path) {
        if (!Files.isDirectory(path)) {
            return;
        }
        try (Stream<Path> list = Files.list(path)) {
            if (list.findFirst().isEmpty()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // 忽略空目录清理失败
        }
    }

    private RecycleRecord buildRecycleRecord(String userId, FileNode node,
                                             String originalPathName, long totalSize) {
        RecycleRecord record = new RecycleRecord();
        record.setUserId(userId);
        record.setName(node.getName());
        record.setType(node.getType());
        record.setOriginalPathName(originalPathName);
        record.setTotalSize(totalSize);
        record.setStatus(1);
        return record;
    }

    private OperationResultVo successResult(String sourceId, String sourceName) {
        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(sourceId);
        vo.setSourceName(sourceName);
        vo.setStatus(FileNodeConstants.STATUS_SUCCESS);
        return vo;
    }
}
