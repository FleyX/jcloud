package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.FileConflictOverwriteHandler;
import com.fleyx.jcloud.util.FileConflictResolver;
import com.fleyx.jcloud.util.FileHashUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.UserReadOnlyChecker;
import com.fleyx.jcloud.util.UserReadWriteLock;
import lombok.RequiredArgsConstructor;
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

/**
 * 文件回收站服务实现。
 */
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

    @Override
    public List<OperationResultVo> deleteToTrash(FileDeleteDto dto, Long userId) {
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
    protected List<OperationResultVo> doDeleteToTrash(List<Long> ids, Long userId) {
        List<OperationResultVo> results = new ArrayList<>();
        for (Long id : ids) {
            OperationResultVo result = deleteOneToTrash(id, userId);
            results.add(result);
        }
        return results;
    }

    private OperationResultVo deleteOneToTrash(Long id, Long userId) {
        FileNode node = getOwnedNode(id, userId);
        StorageSpace space = storageSpaceMapper.selectById(node.getStorageSpaceId());
        if (space == null) {
            return failedResult(id, node.getName(), "存储空间不存在");
        }

        List<FileNode> nodesToDelete = collectNodesToDelete(node, userId);
        long totalSize = nodesToDelete.stream()
                .filter(n -> TYPE_FILE.equals(n.getType()))
                .mapToLong(n -> n.getSize() == null ? 0L : n.getSize())
                .sum();

        Path trashRoot = resolveTrashRoot(space, userId, node.getId());
        if (TYPE_FOLDER.equals(node.getType())) {
            moveFolderToTrash(node, space, trashRoot);
        } else {
            moveFileToTrash(node, space, trashRoot);
        }

        RecycleRecord record = new RecycleRecord();
        record.setUserId(userId);
        record.setNodeId(node.getId());
        record.setName(node.getName());
        record.setType(node.getType());
        record.setOriginalParentId(node.getParentId());
        record.setOriginalPathName(node.getPathName());
        record.setTotalSize(totalSize);
        record.setStatus(1);
        recycleRecordMapper.insert(record);

        List<Long> nodeIds = nodesToDelete.stream().map(FileNode::getId).toList();
        fileMapper.physicalDeleteByIds(nodeIds);

        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(node.getId());
        vo.setSourceName(node.getName());
        vo.setStatus(STATUS_SUCCESS);
        vo.setNodeId(record.getId());
        return vo;
    }

    private List<FileNode> collectNodesToDelete(FileNode node, Long userId) {
        List<FileNode> nodes = new ArrayList<>();
        nodes.add(node);
        if (TYPE_FOLDER.equals(node.getType())) {
            List<FileNode> descendants = fileMapper.selectByPathNamePrefix(userId, node.getPathName());
            for (FileNode descendant : descendants) {
                if (!descendant.getId().equals(node.getId())) {
                    nodes.add(descendant);
                }
            }
        }
        return nodes;
    }

    private void moveFileToTrash(FileNode fileNode, StorageSpace space, Path trashRoot) {
        Path source = FilePathUtil.resolvePhysicalPath(fileNode, space);
        String relativePath = buildRelativePath(fileNode.getPathName(), fileNode.getName());
        Path target = trashRoot.resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR,
                    "移动文件到回收站失败: " + fileNode.getName());
        }
    }

    private void moveFolderToTrash(FileNode folder, StorageSpace space, Path trashRoot) {
        Path sourceTop = resolveFolderPhysicalPath(space, folder.getUserId(), folder.getPathName());
        Path targetTop = trashRoot.resolve(folder.getName());
        try {
            Files.createDirectories(targetTop.getParent());
            if (Files.exists(sourceTop)) {
                Files.move(sourceTop, targetTop);
            } else {
                Files.createDirectories(targetTop);
            }
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR,
                    "移动文件夹到回收站失败: " + folder.getName());
        }
    }

    private String buildRelativePath(String pathName, String name) {
        String base = pathName == null || "/".equals(pathName) ? "" : pathName;
        if (base.startsWith("/")) {
            base = base.substring(1);
        }
        if (base.isEmpty()) {
            return name;
        }
        return base + "/" + name;
    }

    private Path resolveTrashRoot(StorageSpace space, Long userId, Long nodeId) {
        String idStr = nodeId.toString();
        String prefix = idStr.length() >= 10 ? idStr.substring(0, 10) : idStr;
        return Path.of(space.getPath(), userId.toString(), "trash", prefix, idStr);
    }

    private FileNode getOwnedNode(Long nodeId, Long userId) {
        if (nodeId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "节点 ID 不能为空");
        }
        FileNode node = fileMapper.selectById(nodeId);
        if (node == null || !userId.equals(node.getUserId()) || node.getDeleteAt() != 0L) {
            throw new BusinessException(ResultCode.NOT_FOUND, "节点不存在");
        }
        return node;
    }

    private OperationResultVo failedResult(Long sourceId, String sourceName, String message) {
        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(sourceId);
        vo.setSourceName(sourceName);
        vo.setStatus("failed");
        vo.setMessage(message);
        return vo;
    }

    private OperationResultVo successResult(Long sourceId, String sourceName, String message) {
        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(sourceId);
        vo.setSourceName(sourceName);
        vo.setStatus(STATUS_SUCCESS);
        vo.setMessage(message);
        return vo;
    }

    @Override
    public IPage<RecycleRecordVo> listTrash(Long pageNum, Long pageSize, Long userId) {
        Page<RecycleRecord> pageParam = new Page<>(pageNum == null || pageNum < 1 ? 1 : pageNum,
                pageSize == null || pageSize < 1 ? 20 : pageSize);
        LambdaQueryWrapper<RecycleRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RecycleRecord::getUserId, userId);
        wrapper.orderByDesc(RecycleRecord::getCreateTime);
        IPage<RecycleRecord> page = recycleRecordMapper.selectPage(pageParam, wrapper);
        return page.convert(recycleRecordConvert::poToVo);
    }

    @Override
    public List<ConflictItemVo> preCheckRestore(FilePreCheckRestoreDto dto, Long userId) {
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

    private List<ConflictItemVo> doPreCheckRestore(FilePreCheckRestoreDto dto, Long userId) {
        User user = userMapper.selectById(userId);
        StorageSpace space = storageSpaceMapper.selectById(user.getStorageSpaceId());
        List<ConflictItemVo> conflicts = new ArrayList<>();
        for (Long id : dto.getIds()) {
            RecycleRecord record = getOwnedRecord(id, userId);
            if (record == null) {
                continue;
            }
            Long targetParentId = resolveRestoreParentId(record, userId);
            String targetParentPathName = resolveRestorePathName(targetParentId, userId);
            collectRestoreConflicts(record, targetParentId, targetParentPathName, userId, space, conflicts);
        }
        return conflicts;
    }

    private void collectRestoreConflicts(RecycleRecord record, Long targetParentId,
                                         String targetParentPathName, Long userId,
                                         StorageSpace space, List<ConflictItemVo> conflicts) {
        FileNode existing = findExistingChild(targetParentId, record.getName(), userId);
        if (TYPE_FOLDER.equals(record.getType())) {
            if (existing != null && TYPE_FOLDER.equals(existing.getType())) {
                conflicts.add(buildAutoMergeConflictItem(record, existing, targetParentPathName));
                String targetTopPathName = FilePathUtil.buildPathName(targetParentPathName, record.getName());
                walkTrashTreeForConflicts(record, existing, targetTopPathName, userId, space, conflicts);
            } else if (existing != null) {
                conflicts.add(buildConflictItem(record, existing, targetParentPathName));
            }
        } else if (existing != null) {
            conflicts.add(buildConflictItem(record, existing, targetParentPathName));
        }
    }

    private void walkTrashTreeForConflicts(RecycleRecord record, FileNode targetFolder,
                                           String targetTopPathName, Long userId,
                                           StorageSpace space, List<ConflictItemVo> conflicts) {
        Path trashRoot = resolveTrashRoot(space, userId, record.getNodeId());
        Path sourceTop = trashRoot.resolve(record.getName());
        if (!Files.exists(sourceTop)) {
            return;
        }
        try {
            Files.walk(sourceTop).forEach(sourcePath -> {
                if (sourcePath.equals(sourceTop) || Files.isDirectory(sourcePath)) {
                    return;
                }
                Path relative = sourceTop.relativize(sourcePath);
                String rel = relative.toString().replace(java.io.File.separator, "/");
                String targetName = sourcePath.getFileName().toString();
                String targetPathName;
                String relParent = relative.getParent() == null ? "" : relative.getParent().toString().replace(java.io.File.separator, "/");
                if (relParent.isEmpty()) {
                    targetPathName = targetTopPathName;
                } else {
                    targetPathName = targetTopPathName.endsWith("/")
                            ? targetTopPathName + relParent
                            : targetTopPathName + "/" + relParent;
                }
                FileNode targetParent = findFolderByPathName(userId, targetPathName);
                Long parentId = targetParent == null ? null : targetParent.getId();
                if (parentId == null) {
                    return;
                }
                FileNode existingFile = findExistingChild(parentId, targetName, userId);
                if (existingFile != null) {
                    conflicts.add(buildTrashFileConflictItem(record, sourcePath, rel, targetPathName, existingFile));
                }
            });
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "扫描回收站目录失败");
        }
    }

    private ConflictItemVo buildTrashFileConflictItem(RecycleRecord record, Path sourcePath,
                                                      String relativePath, String targetPathName,
                                                      FileNode existingFile) {
        String sourceName = sourcePath.getFileName().toString();
        String sourceFullPath = "/".equals(record.getOriginalPathName())
                ? "/" + relativePath
                : record.getOriginalPathName() + "/" + relativePath;
        ConflictItemVo vo = new ConflictItemVo();
        vo.setNodeId(record.getNodeId());
        vo.setSourceId(record.getNodeId());
        vo.setSourceName(sourceName);
        vo.setSourceType(TYPE_FILE);
        vo.setExistingId(existingFile.getId());
        vo.setExistingName(existingFile.getName());
        vo.setExistingType(existingFile.getType());
        vo.setType(TYPE_FILE);
        vo.setSuggestedStrategy(ConflictStrategy.KEEP.getCode());
        vo.setSourcePath(sourceFullPath);
        vo.setTargetPath("/".equals(targetPathName) ? "/" + sourceName : targetPathName + "/" + sourceName);
        vo.setAutoMerge(false);
        return vo;
    }

    private FileNode findExistingChild(Long parentId, String name, Long userId) {
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId)
                .eq(FileNode::getParentId, parentId)
                .eq(FileNode::getName, name)
                .eq(FileNode::getDeleteAt, 0L);
        return fileMapper.selectOne(wrapper);
    }

    @Override
    public List<OperationResultVo> restore(FileExecuteRestoreDto dto, Long userId) {
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
    protected List<OperationResultVo> doRestore(List<RestoreItemDto> items, Long userId,
                                                ConflictStrategy globalStrategy) {
        List<OperationResultVo> results = new ArrayList<>();
        for (RestoreItemDto item : items) {
            OperationResultVo result = restoreOne(item, userId, globalStrategy);
            results.add(result);
        }
        return results;
    }

    private OperationResultVo restoreOne(RestoreItemDto item, Long userId, ConflictStrategy globalStrategy) {
        RecycleRecord record = getOwnedRecord(item.getId(), userId);
        User user = userMapper.selectById(userId);
        StorageSpace space = storageSpaceMapper.selectById(user.getStorageSpaceId());
        if (space == null) {
            return failedResult(record.getNodeId(), record.getName(), "存储空间不存在");
        }

        Long targetParentId = resolveRestoreParentId(record, userId);
        String targetPathName = resolveRestorePathName(targetParentId, userId);
        ConflictStrategy strategy = resolveStrategy(item, globalStrategy);

        if (TYPE_FILE.equals(record.getType())) {
            return restoreFile(record, targetParentId, targetPathName, space, userId, strategy);
        }
        return restoreFolder(record, targetParentId, targetPathName, space, userId, strategy);
    }

    private ConflictStrategy resolveStrategy(RestoreItemDto item, ConflictStrategy globalStrategy) {
        ConflictStrategy strategy = ConflictStrategy.fromCode(item.getStrategy());
        if (strategy == null && globalStrategy != null) {
            strategy = globalStrategy;
        }
        return strategy == null ? ConflictStrategy.KEEP : strategy;
    }

    private OperationResultVo restoreFile(RecycleRecord record, Long targetParentId,
                                          String targetPathName, StorageSpace space, Long userId,
                                          ConflictStrategy strategy) {
        Path trashRoot = resolveTrashRoot(space, userId, record.getNodeId());
        Path source = trashRoot.resolve(buildRelativePath(record.getOriginalPathName(), record.getName()));
        String resolvedName = resolveRestoreName(targetParentId, record.getName(), userId, strategy, record.getNodeId());
        if (resolvedName == null) {
            OperationResultVo vo = new OperationResultVo();
            vo.setSourceId(record.getNodeId());
            vo.setSourceName(record.getName());
            vo.setStatus(STATUS_SKIPPED);
            vo.setMessage("目标位置已存在同名文件");
            return vo;
        }
        Path target = FilePathUtil.resolvePhysicalPath(space, userId, targetPathName, resolvedName);

        FileNode existing = findExistingChild(targetParentId, resolvedName, userId);
        if (existing != null) {
            overwriteHandler.deleteExistingForOverwrite(existing, userMapper.selectById(userId));
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
            mimeType = java.nio.file.Files.probeContentType(target);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "恢复文件元数据计算失败");
        }

        FileNode fileNode = new FileNode();
        fileNode.setUserId(userId);
        fileNode.setParentId(targetParentId);
        fileNode.setName(resolvedName);
        fileNode.setType(TYPE_FILE);
        fileNode.setSize(size);
        fileNode.setHash(hash);
        fileNode.setStorageSpaceId(space.getId());
        fileNode.setPath(targetPathName);
        fileNode.setPathName(targetPathName);
        fileNode.setMimeType(mimeType);
        fileNode.setStatus(1);
        fileMapper.insert(fileNode);

        recycleRecordMapper.physicalDeleteById(record.getId());

        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(record.getNodeId());
        vo.setSourceName(record.getName());
        vo.setStatus(STATUS_SUCCESS);
        vo.setNewName(resolvedName.equals(record.getName()) ? null : resolvedName);
        vo.setNodeId(fileNode.getId());
        return vo;
    }

    private String resolveRestoreName(Long parentId, String originalName, Long userId,
                                      ConflictStrategy strategy, Long sourceId) {
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

    private OperationResultVo restoreFolder(RecycleRecord record, Long targetParentId,
                                            String targetPathName, StorageSpace space, Long userId,
                                            ConflictStrategy strategy) {
        Path trashRoot = resolveTrashRoot(space, userId, record.getNodeId());
        Path sourceTop = trashRoot.resolve(record.getName());

        FileNode existingFolder = findExistingChild(targetParentId, record.getName(), userId);
        if (existingFolder != null && TYPE_FOLDER.equals(existingFolder.getType())) {
            String topPathName = FilePathUtil.buildPathName(targetPathName, record.getName());
            Path targetTop = resolveFolderPhysicalPath(space, userId, topPathName);
            mergeTrashFolderIntoExisting(sourceTop, targetTop, existingFolder, space, userId, strategy);
            recycleRecordMapper.physicalDeleteById(record.getId());

            OperationResultVo vo = new OperationResultVo();
            vo.setSourceId(record.getNodeId());
            vo.setSourceName(record.getName());
            vo.setStatus(STATUS_SUCCESS);
            vo.setNodeId(existingFolder.getId());
            return vo;
        }

        String resolvedFolderName = resolveRestoreName(targetParentId, record.getName(), userId, strategy, record.getNodeId());
        if (resolvedFolderName == null) {
            OperationResultVo vo = new OperationResultVo();
            vo.setSourceId(record.getNodeId());
            vo.setSourceName(record.getName());
            vo.setStatus(STATUS_SKIPPED);
            vo.setMessage("目标位置已存在同名文件夹");
            return vo;
        }
        String topPathName = FilePathUtil.buildPathName(targetPathName, resolvedFolderName);
        Path targetTop = resolveFolderPhysicalPath(space, userId, topPathName);

        FileNode existing = findExistingChild(targetParentId, resolvedFolderName, userId);
        FileNode topFolder;
        if (existing != null) {
            overwriteHandler.deleteExistingForOverwrite(existing, userMapper.selectById(userId));
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
        topFolder = buildFolderNode(userId, targetParentId, resolvedFolderName, topPathName);
        topFolder.setStorageSpaceId(space.getId());
        fileMapper.insert(topFolder);
        restoreFolderChildrenFromTarget(targetTop, topPathName, topFolder, space, userId);

        recycleRecordMapper.physicalDeleteById(record.getId());

        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(record.getNodeId());
        vo.setSourceName(record.getName());
        vo.setStatus(STATUS_SUCCESS);
        vo.setNewName(resolvedFolderName.equals(record.getName()) ? null : resolvedFolderName);
        vo.setNodeId(topFolder.getId());
        return vo;
    }

    private void mergeTrashFolderIntoExisting(Path sourceTop, Path targetTop, FileNode targetFolder,
                                              StorageSpace space, Long userId, ConflictStrategy strategy) {
        User user = userMapper.selectById(userId);
        Map<Path, Long> folderIds = new HashMap<>();
        folderIds.put(targetTop, targetFolder.getId());
        try {
            Files.walk(sourceTop).forEach(sourcePath -> {
                if (sourcePath.equals(sourceTop)) {
                    return;
                }
                Path relative = sourceTop.relativize(sourcePath);
                Path targetParentPath = targetTop.resolve(relative).getParent();
                Long parentId = folderIds.get(targetParentPath);
                String parentPathName = resolvePathNameByPhysicalPath(targetTop, targetFolder.getPathName(), targetParentPath);
                String name = sourcePath.getFileName().toString();
                if (Files.isDirectory(sourcePath)) {
                    FileNode existing = findExistingChild(parentId, name, userId);
                    FileNode folder;
                    if (existing != null && TYPE_FOLDER.equals(existing.getType())) {
                        folder = existing;
                    } else {
                        if (existing != null) {
                            overwriteHandler.deleteExistingForOverwrite(existing, user);
                        }
                        String pathName = FilePathUtil.buildPathName(parentPathName, name);
                        folder = buildFolderNode(userId, parentId, name, pathName);
                        folder.setStorageSpaceId(space.getId());
                        fileMapper.insert(folder);
                    }
                    folderIds.put(targetTop.resolve(relative), folder.getId());
                } else {
                    FileNode existing = findExistingChild(parentId, name, userId);
                    String resolvedName = name;
                    if (existing != null) {
                        FileConflictResolver.ConflictResolution resolution =
                                conflictResolver.resolveName(userId, parentId, name, existing, strategy);
                        if (resolution.skipped()) {
                            return;
                        }
                        if (resolution.existingToReplace() != null) {
                            overwriteHandler.deleteExistingForOverwrite(resolution.existingToReplace(), user);
                        }
                        resolvedName = resolution.finalName();
                    }
                    Path targetPath = FilePathUtil.resolvePhysicalPath(space, userId, parentPathName, resolvedName);
                    try {
                        Files.createDirectories(targetPath.getParent());
                        Files.move(sourcePath, targetPath);
                    } catch (IOException e) {
                        throw new BusinessException(ResultCode.BUSINESS_ERROR, "恢复文件失败: " + name);
                    }
                    restoreFileFromPath(targetPath, userId, parentId, parentPathName, space, resolvedName);
                }
            });
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "遍历恢复文件夹失败");
        }
    }

    private void restoreFolderChildrenFromTarget(Path targetTop, String topPathName, FileNode topFolder,
                                                 StorageSpace space, Long userId) {
        Map<Path, Long> folderIds = new HashMap<>();
        folderIds.put(targetTop, topFolder.getId());
        try {
            java.util.stream.Stream<Path> stream = Files.walk(targetTop);
            stream.forEach(path -> {
                if (path.equals(targetTop)) {
                    return;
                }
                Path parentPath = path.getParent();
                Long parentId = folderIds.get(parentPath);
                String name = path.getFileName().toString();
                String parentPathName = resolvePathNameByPhysicalPath(targetTop, topPathName, parentPath);
                if (Files.isDirectory(path)) {
                    String pathName = FilePathUtil.buildPathName(parentPathName, name);
                    FileNode folder = buildFolderNode(userId, parentId, name, pathName);
                    folder.setStorageSpaceId(space.getId());
                    fileMapper.insert(folder);
                    folderIds.put(path, folder.getId());
                } else {
                    restoreFileFromPath(path, userId, parentId, parentPathName, space, path.getFileName().toString());
                }
            });
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "遍历恢复文件夹失败");
        }
    }

    private void restoreFileFromPath(Path path, Long userId, Long parentId,
                                     String parentPathName, StorageSpace space, String name) {
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

        FileNode fileNode = new FileNode();
        fileNode.setUserId(userId);
        fileNode.setParentId(parentId);
        fileNode.setName(name);
        fileNode.setType(TYPE_FILE);
        fileNode.setSize(size);
        fileNode.setHash(hash);
        fileNode.setStorageSpaceId(space.getId());
        fileNode.setPath(parentPathName);
        fileNode.setPathName(parentPathName);
        fileNode.setMimeType(mimeType);
        fileNode.setStatus(1);
        fileMapper.insert(fileNode);
    }

    private String resolvePathNameByPhysicalPath(Path targetTop, String topPathName, Path currentPath) {
        Path relative = targetTop.relativize(currentPath);
        String rel = relative.toString().replace(java.io.File.separator, "/");
        if (rel.isEmpty()) {
            return topPathName;
        }
        if (topPathName.equals("/")) {
            return "/" + rel;
        }
        return topPathName + "/" + rel;
    }

    private Path resolveFolderPhysicalPath(StorageSpace space, Long userId, String pathName) {
        Path base = Path.of(space.getPath(), userId.toString(), "files");
        if (pathName == null || pathName.isBlank() || "/".equals(pathName)) {
            return base;
        }
        String relative = pathName.startsWith("/") ? pathName.substring(1) : pathName;
        return base.resolve(relative);
    }

    private Long resolveRestoreParentId(RecycleRecord record, Long userId) {
        Long parentId = record.getOriginalParentId();
        if (parentId == null || parentId == 0L) {
            return 0L;
        }
        FileNode parent = fileMapper.selectById(parentId);
        if (parent != null && parent.getUserId().equals(userId) && parent.getDeleteAt() == 0L) {
            return parentId;
        }
        return createMissingParentChain(record.getOriginalPathName(), userId);
    }

    private Long createMissingParentChain(String originalPathName, Long userId) {
        if (originalPathName == null || "/".equals(originalPathName) || originalPathName.isBlank()) {
            return 0L;
        }
        String clean = originalPathName.startsWith("/") ? originalPathName.substring(1) : originalPathName;
        String[] parts = clean.split("/");
        Long currentParentId = 0L;
        String currentPath = "/";
        for (String part : parts) {
            currentPath = currentPath.equals("/") ? "/" + part : currentPath + "/" + part;
            FileNode existing = findFolderByPathName(userId, currentParentId, part);
            if (existing != null) {
                currentParentId = existing.getId();
                continue;
            }
            FileNode folder = buildFolderNode(userId, currentParentId, part, currentPath);
            fileMapper.insert(folder);
            currentParentId = folder.getId();
        }
        return currentParentId;
    }

    private FileNode findFolderByPathName(Long userId, Long parentId, String name) {
        return fileMapper.selectOne(
                new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getUserId, userId)
                        .eq(FileNode::getParentId, parentId)
                        .eq(FileNode::getName, name)
                        .eq(FileNode::getType, TYPE_FOLDER)
                        .eq(FileNode::getDeleteAt, 0L));
    }

    private FileNode findFolderByPathName(Long userId, String pathName) {
        return fileMapper.selectOne(
                new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getUserId, userId)
                        .eq(FileNode::getPathName, pathName)
                        .eq(FileNode::getType, TYPE_FOLDER)
                        .eq(FileNode::getDeleteAt, 0L));
    }

    private String resolveRestorePathName(Long parentId, Long userId) {
        if (parentId == null || parentId == 0L) {
            return "/";
        }
        FileNode parent = fileMapper.selectById(parentId);
        if (parent == null || !parent.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "父目录不存在");
        }
        return parent.getPathName();
    }

    private RecycleRecord getOwnedRecord(Long recordId, Long userId) {
        if (recordId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "回收站记录 ID 不能为空");
        }
        RecycleRecord record = recycleRecordMapper.selectById(recordId);
        if (record == null || !userId.equals(record.getUserId()) || record.getDeleteAt() != 0L) {
            throw new BusinessException(ResultCode.NOT_FOUND, "回收站记录不存在");
        }
        return record;
    }

    private ConflictItemVo buildConflictItem(RecycleRecord record, FileNode existing, String targetParentPathName) {
        ConflictItemVo vo = buildBaseConflictItem(record, existing, targetParentPathName);
        vo.setAutoMerge(false);
        return vo;
    }

    private ConflictItemVo buildAutoMergeConflictItem(RecycleRecord record, FileNode existing, String targetParentPathName) {
        ConflictItemVo vo = buildBaseConflictItem(record, existing, targetParentPathName);
        vo.setType(TYPE_FOLDER);
        vo.setAutoMerge(true);
        return vo;
    }

    private ConflictItemVo buildBaseConflictItem(RecycleRecord record, FileNode existing, String targetParentPathName) {
        ConflictItemVo vo = new ConflictItemVo();
        vo.setNodeId(record.getNodeId());
        vo.setSourceId(record.getNodeId());
        vo.setSourceName(record.getName());
        vo.setSourceType(record.getType());
        vo.setExistingId(existing.getId());
        vo.setExistingName(existing.getName());
        vo.setExistingType(existing.getType());
        vo.setType(record.getType());
        vo.setSuggestedStrategy(ConflictStrategy.KEEP.getCode());
        vo.setSourcePath(buildDisplayPath(record.getOriginalPathName(), record.getName(), record.getType()));
        vo.setTargetPath(buildDisplayPath(targetParentPathName, existing.getName(), existing.getType()));
        return vo;
    }

    private String buildDisplayPath(String parentPathName, String name, String type) {
        if (TYPE_FOLDER.equals(type)) {
            return FilePathUtil.buildPathName(parentPathName, name);
        }
        return "/".equals(parentPathName) ? "/" + name : parentPathName + "/" + name;
    }

    private FileNode buildFolderNode(Long userId, Long parentId, String name, String pathName) {
        FileNode folder = new FileNode();
        folder.setUserId(userId);
        folder.setParentId(parentId);
        folder.setName(name);
        folder.setType(TYPE_FOLDER);
        folder.setSize(0L);
        folder.setStorageSpaceId(resolveStorageSpaceId(userId));
        folder.setPath(pathName);
        folder.setPathName(pathName);
        folder.setStatus(1);
        return folder;
    }

    private Long resolveStorageSpaceId(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getStorageSpaceId() == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户未绑定存储空间");
        }
        return user.getStorageSpaceId();
    }

    @Override
    public List<OperationResultVo> permanentDelete(FilePermanentDeleteDto dto, Long userId) {
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
    protected List<OperationResultVo> doPermanentDelete(List<Long> ids, Long userId) {
        List<OperationResultVo> results = new ArrayList<>();
        for (Long id : ids) {
            results.add(permanentDeleteOne(id, userId));
        }
        return results;
    }

    private OperationResultVo permanentDeleteOne(Long recordId, Long userId) {
        RecycleRecord record = recycleRecordMapper.selectById(recordId);
        if (record == null || !record.getUserId().equals(userId)) {
            return failedResult(recordId, null, "记录不存在或无权限");
        }
        User user = userMapper.selectById(userId);
        StorageSpace space = storageSpaceMapper.selectById(user.getStorageSpaceId());
        if (space == null) {
            return failedResult(recordId, record.getName(), "用户未绑定存储空间");
        }
        Path trashBase = resolveTrashBasePath(space, userId, record.getNodeId());
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

    private Path resolveTrashBasePath(StorageSpace space, Long userId, Long nodeId) {
        String idStr = nodeId.toString();
        String prefix = idStr.length() >= 10 ? idStr.substring(0, 10) : idStr;
        return Path.of(space.getPath(), userId.toString(), "trash", prefix, idStr);
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
        try (var stream = Files.list(path)) {
            return stream.findFirst().isEmpty();
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        try (var stream = Files.walk(path)) {
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
