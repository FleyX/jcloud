package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.dto.FilePreCheckOperationDto;
import com.fleyx.jcloud.model.dto.FileRenameDto;
import com.fleyx.jcloud.model.dto.OperationItemDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.ConflictItemVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.service.FileOperationService;
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.FileNodeUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.UserReadOnlyChecker;
import com.fleyx.jcloud.util.UserReadWriteLock;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件组织操作服务实现。
 */
@Service
@RequiredArgsConstructor
public class FileOperationServiceImpl implements FileOperationService {

    private static final String TYPE_FILE = "file";
    private static final String TYPE_FOLDER = "folder";

    private final FileMapper fileMapper;
    private final UserMapper userMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final FileConvert fileConvert;
    private final FileOperationExecutor executor;
    private final UserReadWriteLock userReadWriteLock;
    private final UserReadOnlyChecker userReadOnlyChecker;

    @Override
    public FileNodeVo rename(FileRenameDto dto, String userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return doRename(dto, userId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected FileNodeVo doRename(FileRenameDto dto, String userId) {
        FileNode node = getOwnedNode(dto.getId(), userId);
        String newName = normalizeName(dto.getNewName());
        validateNameConflict(fileMapper, userId, node.getParentId(), newName, node.getId());

        StorageSpace space = storageSpaceMapper.selectById(node.getStorageSpaceId());
        String username = UserContext.requireUserCode();
        String oldPathName = resolveNamePath(node, userId);
        String newPathName = FilePathUtil.buildPathName(FilePathUtil.parentOf(oldPathName), newName);

        if (TYPE_FILE.equals(node.getType())) {
            renamePhysicalFile(node, space, username, oldPathName, newPathName);
        }
        if (TYPE_FOLDER.equals(node.getType())) {
            renamePhysicalFolder(node, space, username, oldPathName, newPathName);
        }
        node.setName(newName);
        fileMapper.updateById(node);
        return fileConvert.poToVo(node);
    }

    @Override
    public FileNodeVo createFolder(FileCreateFolderDto dto, String userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return doCreateFolder(dto, userId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected FileNodeVo doCreateFolder(FileCreateFolderDto dto, String userId) {
        String parentId = FileNodeUtil.normalizeParentId(dto.getParentId());
        String name = normalizeName(dto.getName());
        validateNameConflict(fileMapper, userId, parentId, name, null);

        FileNode parentNode = resolveParentNode(parentId, userId);
        FileNode folder = buildFolderNode(userId, parentId, name);
        setNodePath(folder, parentNode);
        fileMapper.insert(folder);
        return fileConvert.poToVo(folder);
    }

    @Override
    public List<ConflictItemVo> preCheckOperation(FilePreCheckOperationDto dto, String userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return doPreCheck(dto, userId);
        } finally {
            lock.unlock();
        }
    }

    protected List<ConflictItemVo> doPreCheck(FilePreCheckOperationDto dto, String userId) {
        String targetParentId = FileNodeUtil.normalizeParentId(dto.getTargetParentId());
        validateTargetParent(targetParentId, userId);
        String targetParentPathName = resolveParentPathName(targetParentId, userId);
        List<ConflictItemVo> conflicts = new ArrayList<>();
        for (OperationItemDto item : dto.getItems()) {
            FileNode source = getOwnedNode(item.getId(), userId);
            String targetName = StringUtils.hasText(item.getNewName())
                    ? item.getNewName().trim()
                    : source.getName();
            collectConflicts(source, targetName, targetParentId, targetParentPathName, userId, conflicts);
        }
        return conflicts;
    }

    private void collectConflicts(FileNode source, String targetName, String targetParentId,
                                  String targetParentPathName, String userId, List<ConflictItemVo> conflicts) {
        FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, targetParentId, targetName);
        if (TYPE_FOLDER.equals(source.getType())) {
            if (existing != null && TYPE_FOLDER.equals(existing.getType())) {
                conflicts.add(buildAutoMergeConflictItem(source, existing, targetParentPathName));
                String currentPathName = FilePathUtil.buildPathName(targetParentPathName, targetName);
                List<FileNode> children = fileMapper.selectByParentId(userId, source.getId());
                for (FileNode child : children) {
                    collectConflicts(child, child.getName(), existing.getId(), currentPathName, userId, conflicts);
                }
            } else if (existing != null) {
                conflicts.add(buildConflictItem(source, existing, targetParentPathName));
            }
        } else if (existing != null) {
            conflicts.add(buildConflictItem(source, existing, targetParentPathName));
        }
    }

    @Override
    public List<OperationResultVo> move(FileExecuteOperationDto dto, String userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return doMove(dto, userId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected List<OperationResultVo> doMove(FileExecuteOperationDto dto, String userId) {
        String targetParentId = FileNodeUtil.normalizeParentId(dto.getTargetParentId());
        validateTargetParent(targetParentId, userId);
        String targetParentPathName = resolveParentPathName(targetParentId, userId);
        ConflictStrategy globalStrategy = ConflictStrategy.fromCode(dto.getGlobalStrategy());
        List<OperationResultVo> results = new ArrayList<>();
        for (OperationItemDto item : dto.getItems()) {
            fillDefaultStrategy(item, globalStrategy);
            FileOperationExecutor.OperationOutcome outcome = executor.moveItem(
                    item, userId, targetParentId, targetParentPathName);
            results.add(toResultVo(outcome));
        }
        return results;
    }

    @Override
    public List<OperationResultVo> copy(FileExecuteOperationDto dto, String userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return doCopy(dto, userId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected List<OperationResultVo> doCopy(FileExecuteOperationDto dto, String userId) {
        String targetParentId = FileNodeUtil.normalizeParentId(dto.getTargetParentId());
        validateTargetParent(targetParentId, userId);
        String targetParentPathName = resolveParentPathName(targetParentId, userId);
        User user = userMapper.selectById(userId);
        ConflictStrategy globalStrategy = ConflictStrategy.fromCode(dto.getGlobalStrategy());
        List<OperationResultVo> results = new ArrayList<>();
        for (OperationItemDto item : dto.getItems()) {
            fillDefaultStrategy(item, globalStrategy);
            FileOperationExecutor.OperationOutcome outcome = executor.copyItem(
                    item, userId, targetParentId, targetParentPathName, user);
            results.add(toResultVo(outcome));
        }
        return results;
    }

    private void fillDefaultStrategy(OperationItemDto item, ConflictStrategy globalStrategy) {
        if (!StringUtils.hasText(item.getStrategy()) && globalStrategy != null) {
            item.setStrategy(globalStrategy.getCode());
        }
        if (!StringUtils.hasText(item.getStrategy())) {
            item.setStrategy(ConflictStrategy.KEEP.getCode());
        }
    }

    private void renamePhysicalFile(FileNode node, StorageSpace space, String username,
                                    String oldPathName, String newPathName) {
        Path oldPath = FilePathUtil.resolvePhysicalPath(space, username, oldPathName);
        Path newPath = FilePathUtil.resolvePhysicalPath(space, username, newPathName);
        try {
            Files.createDirectories(newPath.getParent());
            Files.move(oldPath, newPath);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件重命名失败");
        }
    }

    private void renamePhysicalFolder(FileNode folder, StorageSpace space, String username,
                                      String oldPathName, String newPathName) {
        Path oldPhysicalPath = FilePathUtil.resolvePhysicalPath(space, username, oldPathName);
        if (!Files.exists(oldPhysicalPath)) {
            return;
        }
        Path newPhysicalPath = FilePathUtil.resolvePhysicalPath(space, username, newPathName);
        try {
            Files.createDirectories(newPhysicalPath.getParent());
            Files.move(oldPhysicalPath, newPhysicalPath);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件夹重命名失败");
        }
    }

    private String resolveParentPathName(String parentId, String userId) {
        if (FileNodeConstants.ROOT_ID.equals(parentId)) {
            return "/";
        }
        FileNode parent = getOwnedNode(parentId, userId);
        if (!TYPE_FOLDER.equals(parent.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标父节点不是文件夹");
        }
        return resolveNamePath(parent, userId);
    }

    private FileNode resolveParentNode(String parentId, String userId) {
        if (FileNodeConstants.ROOT_ID.equals(parentId)) {
            return null;
        }
        FileNode parent = fileMapper.selectById(parentId);
        if (parent == null || !parent.getUserId().equals(userId) || !TYPE_FOLDER.equals(parent.getType())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "父目录不存在");
        }
        return parent;
    }

    private void validateTargetParent(String targetParentId, String userId) {
        if (FileNodeConstants.ROOT_ID.equals(targetParentId)) {
            return;
        }
        FileNode parent = getOwnedNode(targetParentId, userId);
        if (!TYPE_FOLDER.equals(parent.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标父节点不是文件夹");
        }
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

    private static void validateNameConflict(FileMapper fileMapper, String userId,
                                             String parentId, String name, String excludeId) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "名称不能为空");
        }
        if (FileConflictHelper.existsSameName(fileMapper, userId, parentId, name, excludeId)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "同名文件或文件夹已存在");
        }
    }

    private String normalizeName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.contains("/") || trimmed.contains("\\")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "名称包含非法字符");
        }
        return trimmed;
    }

    private ConflictItemVo buildConflictItem(FileNode source, FileNode existing, String targetParentPathName) {
        ConflictItemVo vo = buildBaseConflictItem(source, existing, targetParentPathName);
        vo.setAutoMerge(false);
        return vo;
    }

    private ConflictItemVo buildAutoMergeConflictItem(FileNode source, FileNode existing, String targetParentPathName) {
        ConflictItemVo vo = buildBaseConflictItem(source, existing, targetParentPathName);
        vo.setType(TYPE_FOLDER);
        vo.setAutoMerge(true);
        return vo;
    }

    private ConflictItemVo buildBaseConflictItem(FileNode source, FileNode existing, String targetParentPathName) {
        ConflictItemVo vo = new ConflictItemVo();
        vo.setNodeId(source.getId());
        vo.setSourceId(source.getId());
        vo.setSourceName(source.getName());
        vo.setSourceType(source.getType());
        vo.setExistingId(existing.getId());
        vo.setExistingName(existing.getName());
        vo.setExistingType(existing.getType());
        vo.setType(source.getType());
        vo.setSuggestedStrategy(ConflictStrategy.KEEP.getCode());
        String sourcePath = resolveNamePath(source, source.getUserId());
        vo.setSourcePath(sourcePath);
        vo.setTargetPath(FilePathUtil.buildPathName(targetParentPathName, existing.getName()));
        return vo;
    }

    private OperationResultVo toResultVo(FileOperationExecutor.OperationOutcome outcome) {
        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(outcome.sourceId());
        vo.setSourceName(outcome.sourceName());
        vo.setStatus(outcome.status());
        vo.setNewName(outcome.newName());
        vo.setNodeId(outcome.nodeId());
        return vo;
    }

    private FileNode buildFolderNode(String userId, String parentId, String name) {
        FileNode folder = new FileNode();
        folder.setUserId(userId);
        folder.setParentId(parentId);
        folder.setName(name);
        folder.setType(TYPE_FOLDER);
        folder.setSize(0L);
        folder.setStorageSpaceId(resolveStorageSpaceId(userId));
        folder.setStatus(1);
        return folder;
    }

    private void setNodePath(FileNode node, FileNode parent) {
        if (parent == null || FileNodeConstants.ROOT_ID.equals(parent.getId())) {
            node.setPath(FileNodeConstants.ROOT_ID);
        } else {
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

    private String resolveNamePath(FileNode node, String userId) {
        if (node == null) {
            return "/";
        }
        Set<String> ancestorIds = FilePathUtil.extractAncestorIds(List.of(node));
        Map<String, String> cache = queryAncestorNames(userId, ancestorIds);
        FilePathUtil.ResolveContext ctx = FilePathUtil.contextOf(null, userId, cache);
        return FilePathUtil.resolveNamePath(node, ctx);
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
}
