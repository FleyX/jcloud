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
        List<ConflictItemVo> conflicts = new ArrayList<>();
        for (Long id : dto.getIds()) {
            RecycleRecord record = getOwnedRecord(id, userId);
            if (record == null) {
                continue;
            }
            Long targetParentId = resolveRestoreParentId(record, userId);
            FileNode existing = findExistingChild(targetParentId, record.getName(), userId);
            if (existing != null) {
                ConflictItemVo conflict = new ConflictItemVo();
                conflict.setSourceId(record.getNodeId());
                conflict.setSourceName(record.getName());
                conflict.setExistingId(existing.getId());
                conflict.setExistingName(existing.getName());
                conflict.setExistingType(existing.getType());
                conflicts.add(conflict);
            }
        }
        return conflicts;
    }

    private FileNode findExistingChild(Long parentId, String name, Long userId) {
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId)
                .eq(FileNode::getParentId, parentId)
                .eq(FileNode::getName, name)
                .eq(FileNode::getDeleteAt, 0L);
        return fileMapper.selectOne(wrapper);
    }

    private String generateUniqueName(Long parentId, String name, Long userId) {
        int dotIndex = name.lastIndexOf('.');
        String base = dotIndex > 0 ? name.substring(0, dotIndex) : name;
        String ext = dotIndex > 0 ? name.substring(dotIndex) : "";
        int index = 1;
        while (true) {
            String candidate = base + "." + index + ext;
            if (findExistingChild(parentId, candidate, userId) == null) {
                return candidate;
            }
            index++;
            if (index > 1000) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "无法生成唯一文件名");
            }
        }
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
            return doRestore(dto.getItems(), userId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected List<OperationResultVo> doRestore(List<RestoreItemDto> items, Long userId) {
        List<OperationResultVo> results = new ArrayList<>();
        for (RestoreItemDto item : items) {
            OperationResultVo result = restoreOne(item, userId);
            results.add(result);
        }
        return results;
    }

    private OperationResultVo restoreOne(RestoreItemDto item, Long userId) {
        RecycleRecord record = getOwnedRecord(item.getId(), userId);
        User user = userMapper.selectById(userId);
        StorageSpace space = storageSpaceMapper.selectById(user.getStorageSpaceId());
        if (space == null) {
            return failedResult(record.getNodeId(), record.getName(), "存储空间不存在");
        }

        Long targetParentId = resolveRestoreParentId(record, userId);
        String targetPathName = resolveRestorePathName(targetParentId, userId);
        ConflictStrategy strategy = ConflictStrategy.fromCode(item.getStrategy());

        if (TYPE_FILE.equals(record.getType())) {
            return restoreFile(record, targetParentId, targetPathName, space, userId, strategy);
        }
        return restoreFolder(record, targetParentId, targetPathName, space, userId, strategy);
    }

    private OperationResultVo restoreFile(RecycleRecord record, Long targetParentId,
                                          String targetPathName, StorageSpace space, Long userId,
                                          ConflictStrategy strategy) {
        Path trashRoot = resolveTrashRoot(space, userId, record.getNodeId());
        Path source = trashRoot.resolve(buildRelativePath(record.getOriginalPathName(), record.getName()));
        String resolvedName = record.getName();
        Path target = FilePathUtil.resolvePhysicalPath(space, userId, targetPathName, resolvedName);

        FileNode existing = findExistingChild(targetParentId, resolvedName, userId);
        if (existing != null) {
            if (strategy == ConflictStrategy.SKIP) {
                OperationResultVo vo = new OperationResultVo();
                vo.setSourceId(record.getNodeId());
                vo.setSourceName(record.getName());
                vo.setStatus(STATUS_SKIPPED);
                vo.setMessage("目标位置已存在同名文件");
                return vo;
            }
            if (strategy == ConflictStrategy.OVERWRITE) {
                overwriteExistingFile(existing, space, userId);
            } else {
                resolvedName = generateUniqueName(targetParentId, resolvedName, userId);
                target = FilePathUtil.resolvePhysicalPath(space, userId, targetPathName, resolvedName);
            }
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

    private void overwriteExistingFile(FileNode existing, StorageSpace space, Long userId) {
        Path existingPath = FilePathUtil.resolvePhysicalPath(space, userId, existing.getPathName(), existing.getName());
        try {
            Files.deleteIfExists(existingPath);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "覆盖已有文件失败: " + existing.getName());
        }
        fileMapper.physicalDeleteById(existing.getId());
        updateUsedSpace(userMapper.selectById(userId), space, -existing.getSize());
    }

    private OperationResultVo restoreFolder(RecycleRecord record, Long targetParentId,
                                            String targetPathName, StorageSpace space, Long userId,
                                            ConflictStrategy strategy) {
        Path trashRoot = resolveTrashRoot(space, userId, record.getNodeId());
        Path sourceTop = trashRoot.resolve(record.getName());
        String resolvedFolderName = record.getName();
        String topPathName = FilePathUtil.buildPathName(targetPathName, resolvedFolderName);
        Path targetTop = resolveFolderPhysicalPath(space, userId, topPathName);

        FileNode existing = findExistingChild(targetParentId, resolvedFolderName, userId);
        if (existing != null) {
            if (strategy == ConflictStrategy.SKIP) {
                OperationResultVo vo = new OperationResultVo();
                vo.setSourceId(record.getNodeId());
                vo.setSourceName(record.getName());
                vo.setStatus(STATUS_SKIPPED);
                vo.setMessage("目标位置已存在同名文件夹");
                return vo;
            }
            if (strategy != ConflictStrategy.OVERWRITE) {
                resolvedFolderName = generateUniqueName(targetParentId, resolvedFolderName, userId);
                topPathName = FilePathUtil.buildPathName(targetPathName, resolvedFolderName);
                targetTop = resolveFolderPhysicalPath(space, userId, topPathName);
            }
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

        Map<Path, Long> folderIds = new HashMap<>();
        FileNode topFolder = buildFolderNode(userId, targetParentId, resolvedFolderName, topPathName);
        topFolder.setStorageSpaceId(space.getId());
        fileMapper.insert(topFolder);
        folderIds.put(targetTop, topFolder.getId());

        final Path finalTargetTop = targetTop;
        final String finalTopPathName = topPathName;
        try {
            java.util.stream.Stream<Path> stream = Files.walk(finalTargetTop);
            stream.forEach(path -> {
                if (path.equals(finalTargetTop)) {
                    return;
                }
                Path parentPath = path.getParent();
                Long parentId = folderIds.get(parentPath);
                String name = path.getFileName().toString();
                String parentPathName = resolvePathNameByPhysicalPath(finalTargetTop, finalTopPathName, parentPath);
                if (Files.isDirectory(path)) {
                    String pathName = FilePathUtil.buildPathName(parentPathName, name);
                    FileNode folder = buildFolderNode(userId, parentId, name, pathName);
                    folder.setStorageSpaceId(space.getId());
                    fileMapper.insert(folder);
                    folderIds.put(path, folder.getId());
                } else {
                    restoreFileFromPath(path, userId, parentId, parentPathName, space);
                }
            });
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "遍历恢复文件夹失败");
        }

        recycleRecordMapper.physicalDeleteById(record.getId());

        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(record.getNodeId());
        vo.setSourceName(record.getName());
        vo.setStatus(STATUS_SUCCESS);
        vo.setNewName(resolvedFolderName.equals(record.getName()) ? null : resolvedFolderName);
        vo.setNodeId(topFolder.getId());
        return vo;
    }

    private void restoreFileFromPath(Path path, Long userId, Long parentId,
                                     String parentPathName, StorageSpace space) {
        String name = path.getFileName().toString();
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
