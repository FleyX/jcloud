package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.FileChangeOperation;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.event.FileTreeChangedEvent;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.dto.FileInstantUploadDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.common.constant.StorageConstant;
import com.fleyx.jcloud.service.FolderPathService;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.util.FileConflictResolver;
import com.fleyx.jcloud.util.FileHashUtil;
import com.fleyx.jcloud.util.FileLinkUtil;
import com.fleyx.jcloud.util.FileNodeUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.UploadConflictResolver;
import com.fleyx.jcloud.util.UserReadOnlyChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件上传（普通上传与秒传）支撑组件。
 */
@Component
@RequiredArgsConstructor
public class FileUploadSupport {

    private static final String TYPE_FILE = "file";

    private final FileMapper fileMapper;
    private final FileConvert fileConvert;
    private final UploadConflictResolver conflictResolver;
    private final FolderPathService folderPathService;
    private final RemoteFileService remoteFileService;
    private final UserReadOnlyChecker userReadOnlyChecker;
    private final FileNodeSupport fileNodeSupport;
    private final FilePathSupport filePathSupport;
    private final UserSpaceSupport userSpaceSupport;
    private final UserUsedSpaceSupport userUsedSpaceSupport;
    private final FileChangeEventSupport fileChangeEventSupport;

    /**
     * 执行普通上传。
     *
     * @param file     上传文件
     * @param userId   用户 ID
     * @param parentId 父节点 ID
     * @param strategy 冲突解决策略
     * @return 文件节点视图；跳过返回 {@code null}
     */
    @Transactional(rollbackFor = Exception.class)
    public FileNodeVo doUpload(MultipartFile file, String userId, String parentId, String strategy) {
        userReadOnlyChecker.checkWriteAllowed(userId);

        String fileName = normalizeFileName(file.getOriginalFilename());
        String resolvedParentId = FileNodeUtil.normalizeParentId(parentId);
        FileNode parentNode = fileNodeSupport.resolveParentNode(resolvedParentId, userId);

        FileConflictResolver.ConflictResolution resolution =
                conflictResolver.resolve(userId, resolvedParentId, fileName, strategy);
        if (resolution.skipped()) {
            return null;
        }

        if (parentNode != null && FileNodeConstants.SOURCE_REMOTE.equals(parentNode.getSourceType())) {
            FileNodeVo remoteNode = remoteFileService.upload(file, parentNode, userId, resolution.finalName());
            fileChangeEventSupport.publishAfterCommit(new FileTreeChangedEvent(this, FileChangeOperation.CREATE,
                    userId, remoteNode.getId(), TYPE_FILE, remoteNode.getName(), file.getSize(),
                    null, parentNode.getId(), null, FilePathUtil.buildChildPath(parentNode)));
            return remoteNode;
        }

        String parentPathName = filePathSupport.resolveNamePath(parentNode, userId);
        User user = userSpaceSupport.requireUser(userId);
        StorageSpace space = userSpaceSupport.requireSpace(user.getStorageSpaceId());
        long fileSize = file.getSize();

        if (resolution.existingToReplace() != null) {
            conflictResolver.deleteExistingForOverwrite(resolution.existingToReplace(), user);
        }

        long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        if (quota > 0 && usedSpace + fileSize > quota) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户配额不足");
        }

        String username = user.getUsername();
        String filePathName = FilePathUtil.buildPathName(parentPathName, resolution.finalName());
        Path physicalPath = FilePathUtil.resolvePhysicalPath(space, username, filePathName);
        try {
            Files.createDirectories(physicalPath.getParent());
            Files.copy(file.getInputStream(), physicalPath);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件保存失败", e);
        }

        String hash;
        try {
            hash = FileHashUtil.identityHash(physicalPath);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件 hash 计算失败", e);
        }

        FileNode node = fileNodeSupport.buildFileNode(userId, resolvedParentId, resolution.finalName(), fileSize, hash,
                space.getId(), file.getContentType());
        fileNodeSupport.setNodePath(node, resolvedParentId);
        fileMapper.insert(node);

        fileChangeEventSupport.publishAfterCommit(new FileTreeChangedEvent(this, FileChangeOperation.CREATE,
                userId, node.getId(), node.getType(), node.getName(), node.getSize(),
                null, resolvedParentId, null, node.getPath()));

        userUsedSpaceSupport.addUsedSpace(userId, fileSize);

        FileNodeVo vo = fileConvert.poToVo(node);
        vo.setPhysicalPath(relativizePhysicalPath(space, username, physicalPath));
        return vo;
    }

    /**
     * 执行秒传。
     *
     * @param dto    秒传参数
     * @param userId 用户 ID
     * @return 文件节点视图；跳过返回 {@code null}
     */
    @Transactional(rollbackFor = Exception.class)
    public FileNodeVo doInstantUpload(FileInstantUploadDto dto, String userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        FileNode candidate = fileMapper.selectById(dto.getCandidateId());
        if (candidate == null || !candidate.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "候选文件不存在");
        }
        if (!TYPE_FILE.equals(candidate.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "候选文件类型错误");
        }

        User user = userSpaceSupport.requireUser(userId);
        StorageSpace space = userSpaceSupport.requireSpace(candidate.getStorageSpaceId());

        String parentId = FileNodeUtil.normalizeParentId(dto.getParentId());
        FileNode parentNode = fileNodeSupport.resolveParentNode(parentId, userId);
        if (parentNode != null && FileNodeConstants.SOURCE_REMOTE.equals(parentNode.getSourceType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "远程目录不支持秒传");
        }
        fileNodeSupport.validateTargetParent(parentId, userId);
        String finalParentId = parentId;
        String finalFileName = dto.getFileName();
        if (StringUtils.hasText(dto.getRelativePath())) {
            finalParentId = folderPathService.resolveOrCreateFolderPath(userId, parentId, dto.getRelativePath());
            finalFileName = FilePathUtil.extractFileName(dto.getRelativePath());
        }

        parentNode = fileNodeSupport.resolveParentNode(finalParentId, userId);
        String parentPathName = filePathSupport.resolveNamePath(parentNode, userId);
        String fileName = normalizeFileName(finalFileName);

        FileConflictResolver.ConflictResolution resolution =
                conflictResolver.resolve(userId, finalParentId, fileName, dto.getStrategy());
        if (resolution.skipped()) {
            return null;
        }

        long fileSize = candidate.getSize();
        if (resolution.existingToReplace() != null) {
            conflictResolver.deleteExistingForOverwrite(resolution.existingToReplace(), user);
        }

        long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        if (quota > 0 && usedSpace + fileSize > quota) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户配额不足");
        }

        String username = user.getUsername();
        FilePathUtil.ResolveContext ctx = filePathSupport.buildResolveContext(candidate, username, space);
        Path sourcePath = FilePathUtil.resolvePhysicalPath(candidate, ctx);
        String filePathName = FilePathUtil.buildPathName(parentPathName, resolution.finalName());
        Path targetPath = FilePathUtil.resolvePhysicalPath(space, username, filePathName);

        if (!Files.exists(sourcePath)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "候选文件物理数据已丢失");
        }

        String actualFullHash;
        try {
            actualFullHash = FileHashUtil.fullHash(sourcePath);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "候选文件完整 hash 计算失败", e);
        }
        if (!dto.getFullHash().equals(actualFullHash)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "完整 hash 校验失败");
        }

        try {
            Files.createDirectories(targetPath.getParent());
            FileLinkUtil.linkOrCopy(sourcePath, targetPath);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "秒传文件复制失败", e);
        }

        FileNode node = fileNodeSupport.buildFileNode(userId, finalParentId, resolution.finalName(), fileSize,
                candidate.getHash(), space.getId(), candidate.getMimeType());
        fileNodeSupport.setNodePath(node, finalParentId);
        fileMapper.insert(node);

        fileChangeEventSupport.publishAfterCommit(new FileTreeChangedEvent(this, FileChangeOperation.CREATE,
                userId, node.getId(), node.getType(), node.getName(), node.getSize(),
                null, finalParentId, null, node.getPath()));

        userUsedSpaceSupport.addUsedSpace(userId, fileSize);

        FileNodeVo vo = fileConvert.poToVo(node);
        vo.setPhysicalPath(relativizePhysicalPath(space, username, targetPath));
        return vo;
    }

    private String normalizeFileName(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            return "unnamed";
        }
        String trimmed = originalFilename.trim();
        if (trimmed.contains("/") || trimmed.contains("\\")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "文件名包含非法字符");
        }
        return trimmed;
    }

    private String relativizePhysicalPath(StorageSpace space, String username, Path physicalPath) {
        Path base = Path.of(space.getPath(), StorageConstant.FILES_DIR, username);
        return base.relativize(physicalPath).toString();
    }
}
