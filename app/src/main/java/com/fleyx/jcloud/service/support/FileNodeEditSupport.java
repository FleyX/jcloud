package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileRenameDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.service.RemoteFileOperationService;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.FileNodeUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件节点编辑（重命名、新建文件夹）支撑组件。
 */
@Component
@RequiredArgsConstructor
public class FileNodeEditSupport {

    private static final String TYPE_FILE = "file";
    private static final String TYPE_FOLDER = "folder";

    private final FileMapper fileMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final FileConvert fileConvert;
    private final RemoteFileOperationService remoteFileOperationService;
    private final RemoteFileService remoteFileService;
    private final FileNodeSupport fileNodeSupport;
    private final FilePathSupport filePathSupport;

    /**
     * 执行重命名。
     *
     * @param dto    重命名参数
     * @param userId 用户 ID
     * @return 重命名后的文件节点视图
     */
    @Transactional(rollbackFor = Exception.class)
    public FileNodeVo doRename(FileRenameDto dto, String userId) {
        FileNode node = fileNodeSupport.getOwnedNode(dto.getId(), userId);
        String newName = normalizeName(dto.getNewName());

        if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            return remoteFileOperationService.rename(node, newName, userId);
        }

        validateNameConflict(userId, node.getParentId(), newName, node.getId());

        StorageSpace space = storageSpaceMapper.selectById(node.getStorageSpaceId());
        String username = UserContext.requireUserCode();
        String oldPathName = filePathSupport.resolveNamePath(node, userId);
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

    /**
     * 执行新建文件夹。
     *
     * @param dto    新建文件夹参数
     * @param userId 用户 ID
     * @return 新建的文件夹节点视图
     */
    @Transactional(rollbackFor = Exception.class)
    public FileNodeVo doCreateFolder(FileCreateFolderDto dto, String userId) {
        String parentId = FileNodeUtil.normalizeParentId(dto.getParentId());
        String name = normalizeName(dto.getName());

        FileNode parentNode = resolveParentNode(parentId, userId);
        if (parentNode != null && FileNodeConstants.SOURCE_REMOTE.equals(parentNode.getSourceType())) {
            return remoteFileService.createFolder(parentNode, name, userId);
        }

        validateNameConflict(userId, parentId, name, null);

        FileNode folder = fileNodeSupport.buildFolderNode(userId, parentId, name);
        fileNodeSupport.setNodePath(folder, parentId);
        fileMapper.insert(folder);
        return fileConvert.poToVo(folder);
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

    private void validateNameConflict(String userId, String parentId, String name, String excludeId) {
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
}
