package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.fleyx.jcloud.service.FileOperationService;
import com.fleyx.jcloud.util.FileConflictHelper;
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
import java.util.List;

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
    public FileNodeVo rename(FileRenameDto dto, Long userId) {
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
    protected FileNodeVo doRename(FileRenameDto dto, Long userId) {
        FileNode node = getOwnedNode(dto.getId(), userId);
        String newName = normalizeName(dto.getNewName());
        validateNameConflict(fileMapper, userId, node.getParentId(), newName, node.getId());

        if (TYPE_FILE.equals(node.getType())) {
            renamePhysicalFile(node, newName);
        }
        if (TYPE_FOLDER.equals(node.getType())) {
            updateFolderName(node, newName);
        }
        node.setName(newName);
        fileMapper.updateById(node);
        return fileConvert.poToVo(node);
    }

    @Override
    public FileNodeVo createFolder(FileCreateFolderDto dto, Long userId) {
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
    protected FileNodeVo doCreateFolder(FileCreateFolderDto dto, Long userId) {
        Long parentId = dto.getParentId() == null ? 0L : dto.getParentId();
        String name = normalizeName(dto.getName());
        validateNameConflict(fileMapper, userId, parentId, name, null);

        String parentPathName = resolveParentPathName(parentId, userId);
        FileNode folder = buildFolderNode(userId, parentId, name, parentPathName);
        fileMapper.insert(folder);
        return fileConvert.poToVo(folder);
    }

    @Override
    public List<ConflictItemVo> preCheckOperation(FilePreCheckOperationDto dto, Long userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return doPreCheck(dto, userId);
        } finally {
            lock.unlock();
        }
    }

    protected List<ConflictItemVo> doPreCheck(FilePreCheckOperationDto dto, Long userId) {
        Long targetParentId = dto.getTargetParentId() == null ? 0L : dto.getTargetParentId();
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

    private void collectConflicts(FileNode source, String targetName, Long targetParentId,
                                  String targetParentPathName, Long userId, List<ConflictItemVo> conflicts) {
        FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, targetParentId, targetName);
        if (TYPE_FOLDER.equals(source.getType())) {
            if (existing != null && TYPE_FOLDER.equals(existing.getType())) {
                conflicts.add(buildAutoMergeConflictItem(source, existing, targetParentPathName));
                String currentPathName = FilePathUtil.buildPathName(targetParentPathName, targetName);
                List<FileNode> children = fileMapper.selectList(
                        new LambdaQueryWrapper<FileNode>()
                                .eq(FileNode::getParentId, source.getId())
                                .eq(FileNode::getDeleteAt, 0L));
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
    public List<OperationResultVo> move(FileExecuteOperationDto dto, Long userId) {
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
    protected List<OperationResultVo> doMove(FileExecuteOperationDto dto, Long userId) {
        Long targetParentId = dto.getTargetParentId() == null ? 0L : dto.getTargetParentId();
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
    public List<OperationResultVo> copy(FileExecuteOperationDto dto, Long userId) {
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
    protected List<OperationResultVo> doCopy(FileExecuteOperationDto dto, Long userId) {
        Long targetParentId = dto.getTargetParentId() == null ? 0L : dto.getTargetParentId();
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

    private void renamePhysicalFile(FileNode node, String newName) {
        StorageSpace space = storageSpaceMapper.selectById(node.getStorageSpaceId());
        Path oldPath = FilePathUtil.resolvePhysicalPath(node, space);
        Path newPath = oldPath.resolveSibling(newName);
        try {
            Files.move(oldPath, newPath);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件重命名失败");
        }
    }

    private void updateFolderName(FileNode folder, String newName) {
        String oldPathName = folder.getPathName();
        String parentPathName = oldPathName.contains("/")
                ? oldPathName.substring(0, oldPathName.lastIndexOf('/'))
                : "/";
        String newPathName = FilePathUtil.buildPathName(parentPathName, newName);
        updateFolderPathName(folder, newPathName);
    }

    private void updateFolderPathName(FileNode folder, String newPathName) {
        String oldPathName = folder.getPathName();
        String oldPrefix = oldPathName.endsWith("/") ? oldPathName : oldPathName + "/";
        String newPrefix = newPathName.endsWith("/") ? newPathName : newPathName + "/";

        List<FileNode> descendants = fileMapper.selectList(
                new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getUserId, folder.getUserId())
                        .likeRight(FileNode::getPathName, oldPathName)
                        .eq(FileNode::getDeleteAt, 0L));
        for (FileNode node : descendants) {
            if (node.getId().equals(folder.getId())) {
                continue;
            }
            String updated = node.getPathName();
            if (updated.startsWith(oldPrefix)) {
                updated = newPrefix + updated.substring(oldPrefix.length());
            }
            node.setPathName(updated);
            node.setPath(updated);
            fileMapper.updateById(node);
        }
        folder.setPathName(newPathName);
        folder.setPath(newPathName);
    }

    private String resolveParentPathName(Long parentId, Long userId) {
        if (parentId == null || parentId == 0L) {
            return "/";
        }
        FileNode parent = getOwnedNode(parentId, userId);
        if (!TYPE_FOLDER.equals(parent.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标父节点不是文件夹");
        }
        return parent.getPathName();
    }

    private void validateTargetParent(Long targetParentId, Long userId) {
        if (targetParentId == null || targetParentId == 0L) {
            return;
        }
        FileNode parent = getOwnedNode(targetParentId, userId);
        if (!TYPE_FOLDER.equals(parent.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标父节点不是文件夹");
        }
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

    private static void validateNameConflict(FileMapper fileMapper, Long userId,
                                             Long parentId, String name, Long excludeId) {
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
        vo.setSourcePath(buildDisplayPath(source.getPathName(), source.getName(), source.getType()));
        vo.setTargetPath(buildDisplayPath(targetParentPathName, existing.getName(), existing.getType()));
        return vo;
    }

    private String buildDisplayPath(String parentPathName, String name, String type) {
        if (TYPE_FOLDER.equals(type)) {
            return FilePathUtil.buildPathName(parentPathName, name);
        }
        return "/".equals(parentPathName) ? "/" + name : parentPathName + "/" + name;
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

    private FileNode buildFolderNode(Long userId, Long parentId, String name, String parentPathName) {
        FileNode folder = new FileNode();
        folder.setUserId(userId);
        folder.setParentId(parentId);
        folder.setName(name);
        folder.setType(TYPE_FOLDER);
        folder.setSize(0L);
        folder.setStorageSpaceId(resolveStorageSpaceId(userId));
        folder.setPathName(FilePathUtil.buildPathName(parentPathName, name));
        folder.setPath(folder.getPathName());
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
}
