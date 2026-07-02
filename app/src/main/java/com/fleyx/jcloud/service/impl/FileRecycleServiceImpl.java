package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.constant.StorageConstant;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.convert.RecycleRecordConvert;
import com.fleyx.jcloud.model.dto.FileDeleteDto;
import com.fleyx.jcloud.model.dto.FileExecuteRestoreDto;
import com.fleyx.jcloud.model.dto.FilePermanentDeleteDto;
import com.fleyx.jcloud.model.dto.FilePreCheckRestoreDto;
import com.fleyx.jcloud.model.dto.RestoreItemDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.ConflictItemVo;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import com.fleyx.jcloud.model.vo.RecycleRecordVo;
import com.fleyx.jcloud.service.FileRecycleService;
import com.fleyx.jcloud.service.RemoteFileOperationService;
import com.fleyx.jcloud.util.FileConflictOverwriteHandler;
import com.fleyx.jcloud.util.FileConflictResolver;
import com.fleyx.jcloud.util.FileHashUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.UserReadOnlyChecker;
import com.fleyx.jcloud.util.UserReadWriteLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 文件回收站服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileRecycleServiceImpl implements FileRecycleService {

    private static final String TYPE_FILE = "file";
    private static final String TYPE_FOLDER = "folder";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_SKIPPED = "skipped";

    private final FileMapper fileMapper;
    private final RecycleRecordMapper recycleRecordMapper;
    private final UserMapper userMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final FileConvert fileConvert;
    private final RecycleRecordConvert recycleRecordConvert;
    private final UserReadWriteLock userReadWriteLock;
    private final UserReadOnlyChecker userReadOnlyChecker;
    private final FileConflictResolver conflictResolver;
    private final FileConflictOverwriteHandler overwriteHandler;
    private final RemoteFileOperationService remoteFileOperationService;

    @Override
    public List<OperationResultVo> deleteToTrash(FileDeleteDto dto, String userId) {
        if (dto == null || CollectionUtils.isEmpty(dto.getIds())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "删除节点 ID 不能为空");
        }
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return doDeleteToTrash(dto.getIds(), userId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected List<OperationResultVo> doDeleteToTrash(List<String> ids, String userId) {
        List<OperationResultVo> results = new ArrayList<>();
        for (String id : ids) {
            results.add(deleteOneToTrash(id, userId));
        }
        return results;
    }

    private OperationResultVo deleteOneToTrash(String id, String userId) {
        FileNode node = getOwnedNode(id, userId);
        if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            remoteFileOperationService.delete(node, userId);
            return successResult(node.getId(), node.getName());
        }
        User user = requireUser(userId);
        StorageSpace space = requireSpace(user);
        String username = user.getUsername();

        List<FileNode> subtree = collectSubtree(node, userId);
        long totalSize = subtree.stream()
                .filter(n -> TYPE_FILE.equals(n.getType()))
                .mapToLong(n -> n.getSize() == null ? 0L : n.getSize())
                .sum();

        String originalPathName = resolveNamePath(node, userId);
        RecycleRecord record = buildRecycleRecord(userId, node, originalPathName, totalSize);
        recycleRecordMapper.insert(record);

        Path trashRoot = resolveTrashRoot(space, username, record.getId());
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
        String topNamePath = resolveNamePath(topNode, userId);
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
            String fileNamePath = resolveNamePath(node, userId);
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

    private Path resolveTrashRoot(StorageSpace space, String username, String recordId) {
        return Path.of(space.getPath(), StorageConstant.TRASH_DIR, username, recordId);
    }

    private FileNode getOwnedNode(String nodeId, String userId) {
        if (nodeId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "节点 ID 不能为空");
        }
        FileNode node = fileMapper.selectById(nodeId);
        if (node == null || !userId.equals(node.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "节点不存在");
        }
        return node;
    }

    private OperationResultVo successResult(String sourceId, String sourceName) {
        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(sourceId);
        vo.setSourceName(sourceName);
        vo.setStatus(STATUS_SUCCESS);
        return vo;
    }

    private OperationResultVo successResult(String sourceId, String sourceName, String message) {
        OperationResultVo vo = successResult(sourceId, sourceName);
        vo.setMessage(message);
        return vo;
    }

    private OperationResultVo failedResult(String sourceId, String sourceName, String message) {
        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(sourceId);
        vo.setSourceName(sourceName);
        vo.setStatus("failed");
        vo.setMessage(message);
        return vo;
    }

    @Override
    public IPage<RecycleRecordVo> listTrash(Long pageNum, Long pageSize, String userId) {
        Page<RecycleRecord> pageParam = new Page<>(pageNum == null || pageNum < 1 ? 1 : pageNum,
                pageSize == null || pageSize < 1 ? 20 : pageSize);
        LambdaQueryWrapper<RecycleRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RecycleRecord::getUserId, userId);
        wrapper.orderByDesc(RecycleRecord::getCreateTime);
        IPage<RecycleRecord> page = recycleRecordMapper.selectPage(pageParam, wrapper);
        return page.convert(recycleRecordConvert::poToVo);
    }

    @Override
    public List<ConflictItemVo> preCheckRestore(FilePreCheckRestoreDto dto, String userId) {
        if (dto == null || CollectionUtils.isEmpty(dto.getIds())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "恢复记录 ID 不能为空");
        }
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return doPreCheckRestore(dto, userId);
        } finally {
            lock.unlock();
        }
    }

    private List<ConflictItemVo> doPreCheckRestore(FilePreCheckRestoreDto dto, String userId) {
        User user = requireUser(userId);
        StorageSpace space = storageSpaceMapper.selectById(user.getStorageSpaceId());
        String username = user.getUsername();
        List<ConflictItemVo> conflicts = new ArrayList<>();
        for (String id : dto.getIds()) {
            RecycleRecord record = getOwnedRecord(id, userId);
            String targetParentId = resolveRestoreParentId(record, userId);
            String targetParentPathName = resolveRestorePathName(targetParentId, userId);
            collectRestoreConflicts(record, targetParentId, targetParentPathName, userId, username, space, conflicts);
        }
        return conflicts;
    }

    private void collectRestoreConflicts(RecycleRecord record, String targetParentId,
                                         String targetParentPathName, String userId,
                                         String username, StorageSpace space,
                                         List<ConflictItemVo> conflicts) {
        FileNode existing = findExistingChild(targetParentId, record.getName(), userId);
        if (TYPE_FOLDER.equals(record.getType())) {
            if (existing != null && TYPE_FOLDER.equals(existing.getType())) {
                conflicts.add(buildAutoMergeConflictItem(record, existing, targetParentPathName));
                String targetTopPathName = FilePathUtil.buildPathName(targetParentPathName, record.getName());
                walkTrashTreeForConflicts(record, existing, targetTopPathName, userId, username, space, conflicts);
            } else if (existing != null) {
                conflicts.add(buildConflictItem(record, existing, targetParentPathName));
            }
        } else if (existing != null) {
            conflicts.add(buildConflictItem(record, existing, targetParentPathName));
        }
    }

    private void walkTrashTreeForConflicts(RecycleRecord record, FileNode targetFolder,
                                           String targetTopPathName, String userId,
                                           String username, StorageSpace space,
                                           List<ConflictItemVo> conflicts) {
        Path trashRoot = resolveTrashRoot(space, username, record.getId());
        Path sourceTop = trashRoot.resolve(record.getName());
        if (!Files.exists(sourceTop)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(sourceTop)) {
            for (Path sourcePath : stream.toList()) {
                if (sourcePath.equals(sourceTop)) {
                    continue;
                }
                Path relative = sourceTop.relativize(sourcePath);
                String rel = relative.toString().replace(java.io.File.separator, "/");
                String targetPathName = FilePathUtil.buildPathName(targetTopPathName, rel);
                String parentPathName = FilePathUtil.parentOf(targetPathName);
                String name = sourcePath.getFileName().toString();
                FileNode parentFolder = findFolderByPathName(userId, parentPathName);
                String parentId = parentFolder == null ? null : parentFolder.getId();
                if (parentId == null) {
                    continue;
                }
                if (Files.isDirectory(sourcePath)) {
                    FileNode existing = findExistingChild(parentId, name, userId);
                    if (existing != null && TYPE_FOLDER.equals(existing.getType())) {
                        conflicts.add(buildAutoMergeConflictItem(record, existing, parentPathName));
                    } else if (existing != null) {
                        conflicts.add(buildConflictItem(record, existing, parentPathName));
                    }
                } else {
                    FileNode existingFile = findExistingChild(parentId, name, userId);
                    if (existingFile != null) {
                        conflicts.add(buildTrashFileConflictItem(record, rel, targetPathName, existingFile));
                    }
                }
            }
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "扫描回收站目录失败");
        }
    }

    private ConflictItemVo buildTrashFileConflictItem(RecycleRecord record, String relativePath,
                                                      String targetPathName, FileNode existingFile) {
        String sourceFullPath = "/".equals(record.getOriginalPathName())
                ? "/" + relativePath
                : record.getOriginalPathName() + "/" + relativePath;
        ConflictItemVo vo = new ConflictItemVo();
        vo.setNodeId(record.getId());
        vo.setSourceId(record.getId());
        vo.setSourceName(existingFile.getName());
        vo.setSourceType(TYPE_FILE);
        vo.setExistingId(existingFile.getId());
        vo.setExistingName(existingFile.getName());
        vo.setExistingType(existingFile.getType());
        vo.setType(TYPE_FILE);
        vo.setSuggestedStrategy(ConflictStrategy.KEEP.getCode());
        vo.setSourcePath(sourceFullPath);
        vo.setTargetPath(targetPathName);
        vo.setAutoMerge(false);
        return vo;
    }

    @Override
    public List<OperationResultVo> restore(FileExecuteRestoreDto dto, String userId) {
        if (dto == null || CollectionUtils.isEmpty(dto.getItems())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "恢复项不能为空");
        }
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return doRestore(dto.getItems(), userId, ConflictStrategy.fromCode(dto.getGlobalStrategy()));
        } finally {
            lock.unlock();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected List<OperationResultVo> doRestore(List<RestoreItemDto> items, String userId,
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
        User user = requireUser(userId);
        StorageSpace space = storageSpaceMapper.selectById(user.getStorageSpaceId());
        if (space == null) {
            return failedResult(record.getId(), record.getName(), "存储空间不存在");
        }
        String username = user.getUsername();

        String targetParentId = resolveRestoreParentId(record, userId);
        String targetPathName = resolveRestorePathName(targetParentId, userId);
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
        User user = requireUser(userId);
        Path trashRoot = resolveTrashRoot(space, username, record.getId());
        Path source = trashRoot.resolve(FilePathUtil.stripLeadingSlash(record.getOriginalPathName()));
        String resolvedName = resolveRestoreName(targetParentId, record.getName(), userId, strategy);
        if (resolvedName == null) {
            OperationResultVo vo = new OperationResultVo();
            vo.setSourceId(record.getId());
            vo.setSourceName(record.getName());
            vo.setStatus(STATUS_SKIPPED);
            vo.setMessage("目标位置已存在同名文件");
            return vo;
        }

        String filePathName = FilePathUtil.buildPathName(targetParentPathName, resolvedName);
        Path target = FilePathUtil.resolvePhysicalPath(space, username, filePathName);

        FileNode existing = findExistingChild(targetParentId, resolvedName, userId);
        if (existing != null) {
            overwriteHandler.deleteExistingForOverwrite(existing, requireUser(userId));
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

        FileNode fileNode = buildFileNode(userId, targetParentId, resolvedName, size, hash,
                space.getId(), mimeType);
        setNodePath(fileNode, targetParentId);
        fileMapper.insert(fileNode);

        updateUsedSpace(user, space, size);
        recycleRecordMapper.physicalDeleteById(record.getId());

        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(record.getId());
        vo.setSourceName(record.getName());
        vo.setStatus(STATUS_SUCCESS);
        vo.setNewName(resolvedName.equals(record.getName()) ? null : resolvedName);
        vo.setNodeId(fileNode.getId());
        return vo;
    }

    private String resolveRestoreName(String parentId, String originalName, String userId,
                                      ConflictStrategy strategy) {
        FileNode existing = findExistingChild(parentId, originalName, userId);
        if (existing == null) {
            return originalName;
        }
        if (strategy == ConflictStrategy.SKIP) {
            return null;
        }
        if (strategy == ConflictStrategy.OVERWRITE) {
            if (TYPE_FOLDER.equals(existing.getType())) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能覆盖文件夹");
            }
            return originalName;
        }
        return conflictResolver.keepNameGenerator(userId, parentId, originalName);
    }

    private OperationResultVo restoreFolder(RecycleRecord record, String targetParentId,
                                            String targetParentPathName, StorageSpace space,
                                            String username, ConflictStrategy strategy) {
        String userId = record.getUserId();
        Path trashRoot = resolveTrashRoot(space, username, record.getId());
        Path sourceTop = trashRoot.resolve(record.getName());

        FileNode existingFolder = findExistingChild(targetParentId, record.getName(), userId);
        if (existingFolder != null && TYPE_FOLDER.equals(existingFolder.getType())) {
            String topPathName = FilePathUtil.buildPathName(targetParentPathName, record.getName());
            restoreFolderTree(sourceTop, topPathName, existingFolder, space, username, strategy, true);
            recycleRecordMapper.physicalDeleteById(record.getId());
            OperationResultVo vo = new OperationResultVo();
            vo.setSourceId(record.getId());
            vo.setSourceName(record.getName());
            vo.setStatus(STATUS_SUCCESS);
            vo.setNodeId(existingFolder.getId());
            return vo;
        }

        String resolvedFolderName = resolveRestoreName(targetParentId, record.getName(), userId, strategy);
        if (resolvedFolderName == null) {
            OperationResultVo vo = new OperationResultVo();
            vo.setSourceId(record.getId());
            vo.setSourceName(record.getName());
            vo.setStatus(STATUS_SKIPPED);
            vo.setMessage("目标位置已存在同名文件夹");
            return vo;
        }
        String topPathName = FilePathUtil.buildPathName(targetParentPathName, resolvedFolderName);
        Path targetTop = FilePathUtil.resolvePhysicalPath(space, username, topPathName);

        FileNode existing = findExistingChild(targetParentId, resolvedFolderName, userId);
        if (existing != null) {
            overwriteHandler.deleteExistingForOverwrite(existing, requireUser(userId));
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

        FileNode topFolder = buildFolderNode(userId, targetParentId, resolvedFolderName, space.getId());
        setNodePath(topFolder, targetParentId);
        fileMapper.insert(topFolder);
        restoreFolderTree(targetTop, topPathName, topFolder, space, username, strategy, false);

        recycleRecordMapper.physicalDeleteById(record.getId());

        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(record.getId());
        vo.setSourceName(record.getName());
        vo.setStatus(STATUS_SUCCESS);
        vo.setNewName(resolvedFolderName.equals(record.getName()) ? null : resolvedFolderName);
        vo.setNodeId(topFolder.getId());
        return vo;
    }

    private void restoreFolderTree(Path sourceTop, String topPathName, FileNode topFolder,
                                   StorageSpace space, String username, ConflictStrategy strategy,
                                   boolean merge) {
        String userId = topFolder.getUserId();
        Path realTop = merge ? sourceTop : sourceTop;
        Map<Path, String> folderIds = new HashMap<>();
        Map<Path, String> folderPathNames = new HashMap<>();
        folderIds.put(realTop, topFolder.getId());
        folderPathNames.put(realTop, topPathName);

        try (Stream<Path> stream = Files.walk(realTop)) {
            for (Path sourcePath : stream.toList()) {
                if (sourcePath.equals(realTop)) {
                    continue;
                }
                Path relative = realTop.relativize(sourcePath);
                String rel = relative.toString().replace(java.io.File.separator, "/");
                Path targetPath = merge ? realTop.resolve(relative) : sourceTop.resolve(relative);
                Path parentPath = targetPath.getParent();
                String parentId = folderIds.get(parentPath);
                String parentPathName = folderPathNames.get(parentPath);
                if (parentId == null || parentPathName == null) {
                    continue;
                }
                String name = sourcePath.getFileName().toString();
                if (Files.isDirectory(sourcePath)) {
                    FileNode existing = findExistingChild(parentId, name, userId);
                    FileNode folder;
                    if (existing != null && TYPE_FOLDER.equals(existing.getType())) {
                        folder = existing;
                    } else {
                        if (existing != null) {
                            overwriteHandler.deleteExistingForOverwrite(existing, requireUser(userId));
                        }
                        folder = buildFolderNode(userId, parentId, name, space.getId());
                        setNodePath(folder, parentId);
                        fileMapper.insert(folder);
                    }
                    String folderPathName = FilePathUtil.buildPathName(parentPathName, name);
                    folderIds.put(targetPath, folder.getId());
                    folderPathNames.put(targetPath, folderPathName);
                } else {
                    FileNode existing = findExistingChild(parentId, name, userId);
                    String resolvedName = name;
                    if (existing != null) {
                        FileConflictResolver.ConflictResolution resolution =
                                conflictResolver.resolveName(userId, parentId, name, existing, strategy);
                        if (resolution.skipped()) {
                            continue;
                        }
                        if (resolution.existingToReplace() != null) {
                            overwriteHandler.deleteExistingForOverwrite(resolution.existingToReplace(), requireUser(userId));
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
            }
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "遍历恢复文件夹失败");
        }
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

        FileNode fileNode = buildFileNode(userId, parentId, name, size, hash,
                space.getId(), mimeType);
        setNodePath(fileNode, parentId);
        fileMapper.insert(fileNode);
    }

    private String resolveRestoreParentId(RecycleRecord record, String userId) {
        String parentPathName = FilePathUtil.parentOf(record.getOriginalPathName());
        if ("/".equals(parentPathName) || parentPathName.isBlank()) {
            return FileNodeConstants.ROOT_ID;
        }
        FileNode parent = findFolderByPathName(userId, parentPathName);
        if (parent != null) {
            return parent.getId();
        }
        return createMissingParentChain(parentPathName, userId);
    }

    private String createMissingParentChain(String parentPathName, String userId) {
        if (parentPathName == null || "/".equals(parentPathName) || parentPathName.isBlank()) {
            return FileNodeConstants.ROOT_ID;
        }
        String clean = FilePathUtil.stripLeadingSlash(parentPathName);
        String[] parts = clean.split("/");
        String currentParentId = FileNodeConstants.ROOT_ID;
        for (String part : parts) {
            FileNode existing = findFolderByName(userId, currentParentId, part);
            if (existing != null) {
                currentParentId = existing.getId();
                continue;
            }
            FileNode folder = buildFolderNode(userId, currentParentId, part,
                    resolveStorageSpaceId(userId));
            setNodePath(folder, currentParentId);
            fileMapper.insert(folder);
            currentParentId = folder.getId();
        }
        return currentParentId;
    }

    private FileNode findFolderByPathName(String userId, String pathName) {
        if (pathName == null || "/".equals(pathName) || pathName.isBlank()) {
            return null;
        }
        String[] parts = FilePathUtil.stripLeadingSlash(pathName).split("/");
        String parentId = FileNodeConstants.ROOT_ID;
        FileNode current = null;
        for (String part : parts) {
            current = findFolderByName(userId, parentId, part);
            if (current == null) {
                return null;
            }
            parentId = current.getId();
        }
        return current;
    }

    private FileNode findFolderByName(String userId, String parentId, String name) {
        return fileMapper.selectOne(
                new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getUserId, userId)
                        .eq(FileNode::getParentId, parentId)
                        .eq(FileNode::getName, name)
                        .eq(FileNode::getType, TYPE_FOLDER));
    }

    private FileNode findExistingChild(String parentId, String name, String userId) {
        return fileMapper.selectOne(
                new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getUserId, userId)
                        .eq(FileNode::getParentId, parentId)
                        .eq(FileNode::getName, name));
    }

    private String resolveRestorePathName(String parentId, String userId) {
        if (FileNodeConstants.ROOT_ID.equals(parentId)) {
            return "/";
        }
        FileNode parent = fileMapper.selectById(parentId);
        if (parent == null || !parent.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "父目录不存在");
        }
        return resolveNamePath(parent, userId);
    }

    private RecycleRecord getOwnedRecord(String recordId, String userId) {
        if (recordId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "回收站记录 ID 不能为空");
        }
        RecycleRecord record = recycleRecordMapper.selectById(recordId);
        if (record == null || !userId.equals(record.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "回收站记录不存在");
        }
        return record;
    }

    private ConflictItemVo buildConflictItem(RecycleRecord record, FileNode existing,
                                             String targetParentPathName) {
        ConflictItemVo vo = buildBaseConflictItem(record, existing, targetParentPathName);
        vo.setAutoMerge(false);
        return vo;
    }

    private ConflictItemVo buildAutoMergeConflictItem(RecycleRecord record, FileNode existing,
                                                      String targetParentPathName) {
        ConflictItemVo vo = buildBaseConflictItem(record, existing, targetParentPathName);
        vo.setType(TYPE_FOLDER);
        vo.setAutoMerge(true);
        return vo;
    }

    private ConflictItemVo buildBaseConflictItem(RecycleRecord record, FileNode existing,
                                                 String targetParentPathName) {
        ConflictItemVo vo = new ConflictItemVo();
        vo.setNodeId(record.getId());
        vo.setSourceId(record.getId());
        vo.setSourceName(record.getName());
        vo.setSourceType(record.getType());
        vo.setExistingId(existing.getId());
        vo.setExistingName(existing.getName());
        vo.setExistingType(existing.getType());
        vo.setType(record.getType());
        vo.setSuggestedStrategy(ConflictStrategy.KEEP.getCode());
        vo.setSourcePath(record.getOriginalPathName());
        vo.setTargetPath(FilePathUtil.buildPathName(targetParentPathName, existing.getName()));
        return vo;
    }

    private FileNode buildFolderNode(String userId, String parentId, String name, String storageSpaceId) {
        FileNode folder = new FileNode();
        folder.setUserId(userId);
        folder.setParentId(parentId);
        folder.setName(name);
        folder.setType(TYPE_FOLDER);
        folder.setSize(0L);
        folder.setStorageSpaceId(storageSpaceId);
        folder.setSourceType(FileNodeConstants.SOURCE_LOCAL);
        folder.setStatus(1);
        return folder;
    }

    private FileNode buildFileNode(String userId, String parentId, String name, long size,
                                   String hash, String storageSpaceId, String mimeType) {
        FileNode node = new FileNode();
        node.setUserId(userId);
        node.setParentId(parentId);
        node.setName(name);
        node.setType(TYPE_FILE);
        node.setSize(size);
        node.setHash(hash);
        node.setStorageSpaceId(storageSpaceId);
        node.setSourceType(FileNodeConstants.SOURCE_LOCAL);
        node.setMimeType(mimeType);
        node.setStatus(1);
        return node;
    }

    private void setNodePath(FileNode node, String parentId) {
        if (FileNodeConstants.ROOT_ID.equals(parentId)) {
            node.setPath(FileNodeConstants.ROOT_ID);
        } else {
            FileNode parent = fileMapper.selectById(parentId);
            if (parent == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "父目录不存在");
            }
            node.setPath(FilePathUtil.fullIdPath(parent));
        }
    }

    private String resolveStorageSpaceId(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getStorageSpaceId() == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户未绑定存储空间");
        }
        return user.getStorageSpaceId();
    }

    private StorageSpace requireSpace(String userId) {
        return requireSpace(requireUser(userId));
    }

    private StorageSpace requireSpace(User user) {
        StorageSpace space = storageSpaceMapper.selectById(user.getStorageSpaceId());
        if (space == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        return space;
    }

    private User requireUser(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private String resolveNamePath(FileNode node, String userId) {
        if (node == null) {
            return "/";
        }
        Set<String> ancestorIds = FilePathUtil.extractAncestorIds(List.of(node));
        Map<String, String> cache = queryAncestorNames(userId, ancestorIds);
        return FilePathUtil.resolveNamePath(node, FilePathUtil.contextOf(null, userId, cache));
    }

    private Map<String, String> queryAncestorNames(String userId, Set<String> ancestorIds) {
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

    @Override
    public List<OperationResultVo> permanentDelete(FilePermanentDeleteDto dto, String userId) {
        if (dto == null || CollectionUtils.isEmpty(dto.getIds())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "删除记录 ID 不能为空");
        }
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return doPermanentDelete(dto.getIds(), userId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected List<OperationResultVo> doPermanentDelete(List<String> ids, String userId) {
        List<OperationResultVo> results = new ArrayList<>();
        for (String id : ids) {
            results.add(permanentDeleteOne(id, userId));
        }
        return results;
    }

    private OperationResultVo permanentDeleteOne(String recordId, String userId) {
        RecycleRecord record = recycleRecordMapper.selectById(recordId);
        if (record == null || !record.getUserId().equals(userId)) {
            return failedResult(recordId, null, "记录不存在或无权限");
        }
        User user = requireUser(userId);
        StorageSpace space = storageSpaceMapper.selectById(user.getStorageSpaceId());
        if (space == null) {
            return failedResult(recordId, record.getName(), "用户未绑定存储空间");
        }
        Path trashBase = resolveTrashRoot(space, user.getUsername(), record.getId());
        try {
            if (Files.exists(trashBase)) {
                deleteRecursively(trashBase);
            }
            long freed = record.getTotalSize() == null ? 0L : record.getTotalSize();
            updateUsedSpace(user, space, -freed);
            recycleRecordMapper.physicalDeleteById(record.getId());
            return successResult(record.getId(), record.getName(), "已永久删除");
        } catch (IOException e) {
            return failedResult(record.getId(), record.getName(), "物理文件删除失败: " + e.getMessage());
        }
    }

    private void updateUsedSpace(User user, StorageSpace space, long delta) {
        long newUserUsed = Math.max(0L, user.getUsedSpace() + delta);
        user.setUsedSpace(newUserUsed);
        userMapper.updateById(user);
        long newSpaceUsed = Math.max(0L, space.getUsedSpace() + delta);
        space.setUsedSpace(newSpaceUsed);
        storageSpaceMapper.updateById(space);
    }

    private boolean isEmptyDirectory(Path path) throws IOException {
        try (Stream<Path> stream = Files.list(path)) {
            return stream.findFirst().isEmpty();
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new BusinessException(ResultCode.BUSINESS_ERROR,
                                    "删除物理文件失败: " + p);
                        }
                    });
        }
    }
}
