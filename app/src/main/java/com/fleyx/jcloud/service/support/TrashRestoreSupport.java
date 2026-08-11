package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.FileChangeOperation;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.event.FileTreeChangedEvent;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.dto.RestoreItemDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import com.fleyx.jcloud.util.FileConflictOverwriteHandler;
import com.fleyx.jcloud.util.FileConflictResolver;
import com.fleyx.jcloud.util.FileHashUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 回收站恢复执行支撑组件。
 */
@Component
@RequiredArgsConstructor
public class TrashRestoreSupport {

    private static final String TYPE_FILE = "file";
    private static final String TYPE_FOLDER = "folder";

    private final FileMapper fileMapper;
    private final RecycleRecordMapper recycleRecordMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final FileNodeSupport fileNodeSupport;
    private final FilePathSupport filePathSupport;
    private final UserSpaceSupport userSpaceSupport;
    private final FileConflictResolver conflictResolver;
    private final FileConflictOverwriteHandler overwriteHandler;
    private final TrashRestorePathSupport trashRestorePathSupport;
    private final FileChangeEventSupport fileChangeEventSupport;

    /**
     * 批量恢复回收站记录，整个列表在一个事务内执行。
     *
     * @param items          恢复项列表
     * @param userId         用户 ID
     * @param globalStrategy 全局冲突策略
     * @return 操作结果列表
     */
    @Transactional(rollbackFor = Exception.class)
    public List<OperationResultVo> doRestore(List<RestoreItemDto> items, String userId,
                                             ConflictStrategy globalStrategy) {
        List<OperationResultVo> results = new ArrayList<>();
        for (RestoreItemDto item : items) {
            results.add(restoreOne(item, userId, globalStrategy));
        }
        return results;
    }

    private OperationResultVo restoreOne(RestoreItemDto item, String userId,
                                         ConflictStrategy globalStrategy) {
        RecycleRecord record = getOwnedRecord(item.getId(), userId);
        User user = userSpaceSupport.requireUser(userId);
        StorageSpace space = storageSpaceMapper.selectById(user.getStorageSpaceId());
        if (space == null) {
            return failedResult(record.getId(), record.getName(), "存储空间不存在");
        }
        String username = user.getUsername();

        String targetParentId = trashRestorePathSupport.resolveRestoreParentId(record, userId);
        String targetPathName = trashRestorePathSupport.resolveRestorePathName(targetParentId, userId);
        ConflictStrategy strategy = resolveStrategy(item, globalStrategy);

        if (TYPE_FILE.equals(record.getType())) {
            return restoreFile(record, targetParentId, targetPathName, space, username, strategy);
        }
        return restoreFolder(record, targetParentId, targetPathName, space, username, strategy);
    }

    private ConflictStrategy resolveStrategy(RestoreItemDto item, ConflictStrategy globalStrategy) {
        ConflictStrategy strategy = ConflictStrategy.fromCode(item.getStrategy());
        if (strategy == null && globalStrategy != null) {
            strategy = globalStrategy;
        }
        return strategy == null ? ConflictStrategy.KEEP : strategy;
    }

    private OperationResultVo restoreFile(RecycleRecord record, String targetParentId,
                                          String targetParentPathName, StorageSpace space,
                                          String username, ConflictStrategy strategy) {
        String userId = record.getUserId();
        User user = userSpaceSupport.requireUser(userId);
        Path trashRoot = FilePathUtil.resolveTrashRoot(space, username, record.getId());
        Path source = trashRoot.resolve(FilePathUtil.stripLeadingSlash(record.getOriginalPathName()));
        String resolvedName = resolveRestoreName(targetParentId, record.getName(), userId, strategy);
        if (resolvedName == null) {
            OperationResultVo vo = new OperationResultVo();
            vo.setSourceId(record.getId());
            vo.setSourceName(record.getName());
            vo.setStatus(FileNodeConstants.STATUS_SKIPPED);
            vo.setMessage("目标位置已存在同名文件");
            return vo;
        }

        String filePathName = FilePathUtil.buildPathName(targetParentPathName, resolvedName);
        Path target = FilePathUtil.resolvePhysicalPath(space, username, filePathName);

        FileNode existing = fileNodeSupport.findExistingChild(userId, targetParentId, resolvedName);
        if (existing != null) {
            overwriteHandler.deleteExistingForOverwrite(existing, userSpaceSupport.requireUser(userId));
        }

        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "恢复文件失败: " + record.getName());
        }

        long size;
        String hash;
        String mimeType;
        try {
            size = Files.size(target);
            hash = FileHashUtil.identityHash(target);
            mimeType = Files.probeContentType(target);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "恢复文件元数据计算失败");
        }

        FileNode fileNode = fileNodeSupport.buildFileNode(userId, targetParentId, resolvedName, size, hash,
                space.getId(), mimeType);
        fileNodeSupport.setNodePath(fileNode, targetParentId);
        fileMapper.insert(fileNode);

        fileChangeEventSupport.publishAfterCommit(new FileTreeChangedEvent(this, FileChangeOperation.RESTORE,
                userId, fileNode.getId(), fileNode.getType(), fileNode.getName(), fileNode.getSize(),
                null, targetParentId, null, fileNode.getPath()));

        userSpaceSupport.updateUsedSpace(user, space, size);
        recycleRecordMapper.physicalDeleteById(record.getId());

        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(record.getId());
        vo.setSourceName(record.getName());
        vo.setStatus(FileNodeConstants.STATUS_SUCCESS);
        vo.setNewName(resolvedName.equals(record.getName()) ? null : resolvedName);
        vo.setNodeId(fileNode.getId());
        return vo;
    }

    private String resolveRestoreName(String parentId, String originalName, String userId,
                                      ConflictStrategy strategy) {
        FileNode existing = fileNodeSupport.findExistingChild(userId, parentId, originalName);
        FileConflictResolver.ConflictResolution resolution =
                conflictResolver.resolveName(userId, parentId, originalName, existing, strategy);
        return resolution.skipped() ? null : resolution.finalName();
    }

    private OperationResultVo restoreFolder(RecycleRecord record, String targetParentId,
                                            String targetParentPathName, StorageSpace space,
                                            String username, ConflictStrategy strategy) {
        String userId = record.getUserId();
        Path trashRoot = FilePathUtil.resolveTrashRoot(space, username, record.getId());
        Path sourceTop = trashRoot.resolve(record.getName());

        FileNode existingFolder = fileNodeSupport.findExistingChild(userId, targetParentId, record.getName());
        if (existingFolder != null && TYPE_FOLDER.equals(existingFolder.getType())) {
            String topPathName = FilePathUtil.buildPathName(targetParentPathName, record.getName());
            restoreFolderTree(sourceTop, topPathName, existingFolder, space, username, strategy);
            recycleRecordMapper.physicalDeleteById(record.getId());
            fileChangeEventSupport.publishAfterCommit(new FileTreeChangedEvent(this, FileChangeOperation.RESTORE,
                    userId, existingFolder.getId(), existingFolder.getType(), existingFolder.getName(),
                    existingFolder.getSize(), null, existingFolder.getParentId(), null, existingFolder.getPath()));
            OperationResultVo vo = new OperationResultVo();
            vo.setSourceId(record.getId());
            vo.setSourceName(record.getName());
            vo.setStatus(FileNodeConstants.STATUS_SUCCESS);
            vo.setNodeId(existingFolder.getId());
            return vo;
        }

        String resolvedFolderName = resolveRestoreName(targetParentId, record.getName(), userId, strategy);
        if (resolvedFolderName == null) {
            OperationResultVo vo = new OperationResultVo();
            vo.setSourceId(record.getId());
            vo.setSourceName(record.getName());
            vo.setStatus(FileNodeConstants.STATUS_SKIPPED);
            vo.setMessage("目标位置已存在同名文件夹");
            return vo;
        }
        String topPathName = FilePathUtil.buildPathName(targetParentPathName, resolvedFolderName);
        Path targetTop = FilePathUtil.resolvePhysicalPath(space, username, topPathName);

        FileNode existing = fileNodeSupport.findExistingChild(userId, targetParentId, resolvedFolderName);
        if (existing != null) {
            overwriteHandler.deleteExistingForOverwrite(existing, userSpaceSupport.requireUser(userId));
        }
        try {
            Files.createDirectories(targetTop.getParent());
            if (Files.isDirectory(targetTop) && isEmptyDirectory(targetTop)) {
                Files.delete(targetTop);
            }
            Files.move(sourceTop, targetTop);
        } catch (Exception e) {
            throw new SystemException(ResultCode.BUSINESS_ERROR, "恢复文件夹失败: " + record.getName(), e);
        }

        FileNode topFolder = fileNodeSupport.buildFolderNode(userId, targetParentId, resolvedFolderName);
        fileNodeSupport.setNodePath(topFolder, targetParentId);
        fileMapper.insert(topFolder);
        fileChangeEventSupport.publishAfterCommit(new FileTreeChangedEvent(this, FileChangeOperation.RESTORE,
                userId, topFolder.getId(), topFolder.getType(), topFolder.getName(), topFolder.getSize(),
                null, targetParentId, null, topFolder.getPath()));
        restoreFolderTree(targetTop, topPathName, topFolder, space, username, strategy);

        recycleRecordMapper.physicalDeleteById(record.getId());

        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(record.getId());
        vo.setSourceName(record.getName());
        vo.setStatus(FileNodeConstants.STATUS_SUCCESS);
        vo.setNewName(resolvedFolderName.equals(record.getName()) ? null : resolvedFolderName);
        vo.setNodeId(topFolder.getId());
        return vo;
    }

    private void restoreFolderTree(Path sourceTop, String topPathName, FileNode topFolder,
                                   StorageSpace space, String username, ConflictStrategy strategy) {
        String userId = topFolder.getUserId();
        Map<Path, String> folderIds = new HashMap<>();
        Map<Path, String> folderPathNames = new HashMap<>();
        folderIds.put(sourceTop, topFolder.getId());
        folderPathNames.put(sourceTop, topPathName);

        try (Stream<Path> stream = Files.walk(sourceTop)) {
            for (Path sourcePath : stream.toList()) {
                if (sourcePath.equals(sourceTop)) {
                    continue;
                }
                Path relative = sourceTop.relativize(sourcePath);
                String rel = relative.toString().replace(java.io.File.separator, "/");
                Path targetPath = sourceTop.resolve(relative);
                Path parentPath = targetPath.getParent();
                String parentId = folderIds.get(parentPath);
                String parentPathName = folderPathNames.get(parentPath);
                if (parentId == null || parentPathName == null) {
                    continue;
                }
                String name = sourcePath.getFileName().toString();
                if (Files.isDirectory(sourcePath)) {
                    FileNode existing = fileNodeSupport.findExistingChild(userId, parentId, name);
                    FileNode folder;
                    if (existing != null && TYPE_FOLDER.equals(existing.getType())) {
                        folder = existing;
                    } else {
                        if (existing != null) {
                            overwriteHandler.deleteExistingForOverwrite(existing, userSpaceSupport.requireUser(userId));
                        }
                        folder = fileNodeSupport.buildFolderNode(userId, parentId, name);
                        fileNodeSupport.setNodePath(folder, parentId);
                        fileMapper.insert(folder);
                    }
                    String folderPathName = FilePathUtil.buildPathName(parentPathName, name);
                    folderIds.put(targetPath, folder.getId());
                    folderPathNames.put(targetPath, folderPathName);
                } else {
                    restoreTreeFile(sourcePath, userId, parentId, parentPathName, name,
                            space, username, strategy);
                }
            }
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "遍历恢复文件夹失败");
        }
    }

    private void restoreTreeFile(Path sourcePath, String userId, String parentId,
                                 String parentPathName, String name, StorageSpace space,
                                 String username, ConflictStrategy strategy) {
        FileNode existing = fileNodeSupport.findExistingChild(userId, parentId, name);
        String resolvedName = name;
        if (existing != null) {
            FileConflictResolver.ConflictResolution resolution =
                    conflictResolver.resolveName(userId, parentId, name, existing, strategy);
            if (resolution.skipped()) {
                return;
            }
            if (resolution.existingToReplace() != null) {
                overwriteHandler.deleteExistingForOverwrite(resolution.existingToReplace(), userSpaceSupport.requireUser(userId));
            }
            resolvedName = resolution.finalName();
        }
        String filePathName = FilePathUtil.buildPathName(parentPathName, resolvedName);
        Path finalTargetPath = FilePathUtil.resolvePhysicalPath(space, username, filePathName);
        try {
            Files.createDirectories(finalTargetPath.getParent());
            Files.move(sourcePath, finalTargetPath);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "恢复文件失败: " + name);
        }
        restoreFileFromPath(finalTargetPath, userId, parentId, space, resolvedName);
    }

    private void restoreFileFromPath(Path path, String userId, String parentId,
                                     StorageSpace space, String name) {
        long size;
        String hash;
        String mimeType;
        try {
            size = Files.size(path);
            hash = FileHashUtil.identityHash(path);
            mimeType = Files.probeContentType(path);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "恢复文件元数据计算失败: " + name);
        }

        FileNode fileNode = fileNodeSupport.buildFileNode(userId, parentId, name, size, hash,
                space.getId(), mimeType);
        fileNodeSupport.setNodePath(fileNode, parentId);
        fileMapper.insert(fileNode);
    }

    /**
     * 按 ID 查询回收站记录并校验归属。
     *
     * @param recordId 回收站记录 ID
     * @param userId   用户 ID
     * @return 回收站记录
     */
    public RecycleRecord getOwnedRecord(String recordId, String userId) {
        if (recordId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "回收站记录 ID 不能为空");
        }
        RecycleRecord record = recycleRecordMapper.selectById(recordId);
        if (record == null || !userId.equals(record.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "回收站记录不存在");
        }
        return record;
    }

    private boolean isEmptyDirectory(Path path) throws IOException {
        try (Stream<Path> stream = Files.list(path)) {
            return stream.findFirst().isEmpty();
        }
    }

    private OperationResultVo failedResult(String sourceId, String sourceName, String message) {
        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(sourceId);
        vo.setSourceName(sourceName);
        vo.setStatus(FileNodeConstants.STATUS_FAILED);
        vo.setMessage(message);
        return vo;
    }
}
