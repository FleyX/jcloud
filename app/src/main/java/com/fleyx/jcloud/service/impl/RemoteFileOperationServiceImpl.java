package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.service.RemoteFileOperationService;
import com.fleyx.jcloud.service.RemoteMountService;
import com.fleyx.jcloud.service.RemoteProtocolAdapter;
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.RemoteMountLock;
import com.fleyx.jcloud.util.RemotePathUtil;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 远程文件组织操作服务实现。
 */
@Service
@RequiredArgsConstructor
public class RemoteFileOperationServiceImpl implements RemoteFileOperationService {

    private static final String TYPE_FILE = "file";
    private static final String TYPE_FOLDER = "folder";

    private final FileMapper fileMapper;
    private final RemoteMountMapper remoteMountMapper;
    private final FileConvert fileConvert;
    private final RemoteProtocolAdapterFactory adapterFactory;
    private final RemoteMountLock remoteMountLock;
    private final RemoteMountService remoteMountService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileNodeVo rename(FileNode node, String newName, String userId) {
        if (!userId.equals(node.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该节点");
        }
        if (!FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不是远程文件");
        }
        if (isMountPoint(node)) {
            return renameMountPoint(node, newName, userId);
        }
        return renameRemoteNode(node, newName, userId);
    }

    private FileNodeVo renameMountPoint(FileNode node, String newName, String userId) {
        if (!StringUtils.hasText(newName) || newName.contains("/") || newName.contains("\\")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "名称包含非法字符");
        }
        node.setName(newName);
        fileMapper.updateById(node);

        RemoteMount mount = requireMount(node.getRemoteMountId(), userId);
        mount.setName(newName);
        remoteMountMapper.updateById(mount);
        return fileConvert.poToVo(node);
    }

    private FileNodeVo renameRemoteNode(FileNode node, String newName, String userId) {
        if (FileConflictHelper.existsSameName(fileMapper, userId, node.getParentId(), newName, node.getId())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "同名文件或文件夹已存在");
        }

        RemoteMount mount = requireMount(node.getRemoteMountId(), userId);
        RLock lock = remoteMountLock.getLock(mount.getId());
        lock.lock();
        try {
            String oldRemotePath = deriveRemotePath(node, mount);
            String newRemotePath = FilePathUtil.buildPathName(FilePathUtil.parentOf(oldRemotePath), newName);

            RemoteProtocolAdapter adapter = adapterFactory.create(mount);
            adapter.move(oldRemotePath, newRemotePath);

            node.setName(newName);
            fileMapper.updateById(node);
            return fileConvert.poToVo(node);
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileNodeVo move(FileNode node, FileNode newParentNode, String finalName, String userId) {
        if (!userId.equals(node.getUserId()) || !userId.equals(newParentNode.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该节点");
        }
        if (!FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())
                || !FileNodeConstants.SOURCE_REMOTE.equals(newParentNode.getSourceType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能跨本地与远程目录操作");
        }
        if (!Objects.equals(node.getRemoteMountId(), newParentNode.getRemoteMountId())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能跨本地与远程目录操作");
        }
        if (isMountPoint(node)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能移动远程挂载点");
        }
        if (TYPE_FOLDER.equals(node.getType())) {
            validateNotMoveToSelfSubtree(node, newParentNode.getId(), userId);
        }

        RemoteMount mount = requireMount(node.getRemoteMountId(), userId);
        RLock lock = remoteMountLock.getLock(mount.getId());
        lock.lock();
        try {
            return doMove(node, newParentNode, finalName, mount);
        } finally {
            lock.unlock();
        }
    }

    private FileNodeVo doMove(FileNode node, FileNode newParentNode, String finalName, RemoteMount mount) {
        String oldRemotePath = deriveRemotePath(node, mount);
        String parentRemotePath = deriveRemotePath(newParentNode, mount);
        String newRemotePath = FilePathUtil.buildPathName(parentRemotePath, finalName);

        if (!oldRemotePath.equals(newRemotePath)) {
            RemoteProtocolAdapter adapter = adapterFactory.create(mount);
            adapter.move(oldRemotePath, newRemotePath);
        }

        String oldFullPath = FilePathUtil.fullIdPath(node);
        String newFullPath = FilePathUtil.fullIdPath(newParentNode) + FileNodeConstants.PATH_SEPARATOR + node.getId();
        List<FileNode> descendants = TYPE_FOLDER.equals(node.getType())
                ? fileMapper.selectByIdPathPrefix(node.getUserId(), node.getPath(), node.getId())
                : List.of();

        node.setParentId(newParentNode.getId());
        node.setName(finalName);
        node.setPath(FilePathUtil.fullIdPath(newParentNode));

        if (TYPE_FOLDER.equals(node.getType())) {
            updateFolderDescendantPaths(descendants, oldFullPath, newFullPath);
        }

        fileMapper.updateById(node);
        return fileConvert.poToVo(node);
    }

    private void updateFolderDescendantPaths(List<FileNode> descendants, String oldFullPath, String newFullPath) {
        String quoted = Pattern.quote(oldFullPath);
        for (FileNode descendant : descendants) {
            if (descendant.getPath() == null) {
                continue;
            }
            String updatedPath = descendant.getPath().replaceFirst("^" + quoted, newFullPath);
            descendant.setPath(updatedPath);
            fileMapper.updateById(descendant);
        }
    }

    @Override
    public FileNodeVo copy(FileNode node, FileNode newParentNode, String userId) {
        throw new BusinessException(ResultCode.BUSINESS_ERROR, "远程文件暂不支持复制");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(FileNode node, String userId) {
        if (!userId.equals(node.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该节点");
        }
        if (!FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不是远程文件");
        }
        if (isMountPoint(node)) {
            remoteMountService.delete(node.getRemoteMountId(), userId);
            return;
        }

        RemoteMount mount = requireMount(node.getRemoteMountId(), userId);
        RLock lock = remoteMountLock.getLock(mount.getId());
        lock.lock();
        try {
            doDelete(node, mount);
        } finally {
            lock.unlock();
        }
    }

    private void doDelete(FileNode node, RemoteMount mount) {
        String remotePath = deriveRemotePath(node, mount);
        RemoteProtocolAdapter adapter = adapterFactory.create(mount);
        adapter.delete(remotePath);

        List<FileNode> subtree = fileMapper.selectByIdPathPrefix(node.getUserId(), node.getPath(), node.getId());
        Set<String> ids = new HashSet<>();
        for (FileNode n : subtree) {
            ids.add(n.getId());
        }
        if (!ids.isEmpty()) {
            fileMapper.physicalDeleteByIds(ids);
        }
    }

    @Override
    public void validateSameSource(List<FileNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        String firstSource = nodes.get(0).getSourceType();
        if (firstSource == null) {
            firstSource = FileNodeConstants.SOURCE_LOCAL;
        }
        String firstMountId = nodes.get(0).getRemoteMountId();
        for (int i = 1; i < nodes.size(); i++) {
            FileNode node = nodes.get(i);
            String source = node.getSourceType() == null ? FileNodeConstants.SOURCE_LOCAL : node.getSourceType();
            if (!firstSource.equals(source)) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能跨本地与远程目录操作");
            }
            if (FileNodeConstants.SOURCE_REMOTE.equals(source)
                    && !Objects.equals(firstMountId, node.getRemoteMountId())) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能跨本地与远程目录操作");
            }
        }
    }

    @Override
    public String deriveRemotePath(FileNode node, RemoteMount mount) {
        Set<String> ancestorIds = FilePathUtil.extractAncestorIds(List.of(node));
        Map<String, String> cache = queryAncestorNames(node.getUserId(), ancestorIds);
        FilePathUtil.ResolveContext ctx = FilePathUtil.contextOf(null, node.getUserId(), cache);
        String fullNamePath = FilePathUtil.resolveNamePath(node, ctx);
        return RemotePathUtil.relativeNamePath(mount.getName(), fullNamePath);
    }

    private boolean isMountPoint(FileNode node) {
        return FileNodeConstants.ROOT_ID.equals(node.getParentId())
                && FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType());
    }

    private RemoteMount requireMount(String remoteMountId, String userId) {
        RemoteMount mount = remoteMountMapper.selectById(remoteMountId);
        if (mount == null || !userId.equals(mount.getUserId()) || mount.getDeleteAt() != 0L) {
            throw new BusinessException(ResultCode.NOT_FOUND, "远程挂载不存在");
        }
        return mount;
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

    private void validateNotMoveToSelfSubtree(FileNode source, String targetParentId, String userId) {
        if (targetParentId.equals(source.getId())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能将文件夹移动到自身内部");
        }
        FileNode targetParent = fileMapper.selectById(targetParentId);
        if (targetParent == null || !userId.equals(targetParent.getUserId())) {
            return;
        }
        String sourceFullPath = FilePathUtil.fullIdPath(source);
        String targetParentPath = targetParent.getPath();
        if (targetParentPath == null) {
            return;
        }
        if (targetParentPath.equals(sourceFullPath)
                || targetParentPath.startsWith(sourceFullPath + FileNodeConstants.PATH_SEPARATOR)
                || (FileNodeConstants.ROOT_ID + FileNodeConstants.PATH_SEPARATOR + source.getId()).equals(targetParentPath)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不能将文件夹移动到自身子目录");
        }
    }
}
