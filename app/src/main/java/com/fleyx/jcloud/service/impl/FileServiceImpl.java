package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.dto.FileInstantUploadDto;
import com.fleyx.jcloud.model.dto.FilePageQueryDto;
import com.fleyx.jcloud.model.dto.FileUploadPreCheckDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.ConflictItemVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UploadPreCheckVo;
import com.fleyx.jcloud.service.FileService;
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.FileHashUtil;
import com.fleyx.jcloud.util.FileLinkUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.FileConflictResolver;
import com.fleyx.jcloud.util.UploadConflictResolver;
import com.fleyx.jcloud.util.UserReadOnlyChecker;
import com.fleyx.jcloud.util.UserReadWriteLock;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件业务实现。
 */
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private static final String TYPE_FILE = "file";
    private static final String TYPE_FOLDER = "folder";

    private final FileMapper fileMapper;
    private final UserMapper userMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final FileConvert fileConvert;
    private final UserReadWriteLock userReadWriteLock;
    private final UserReadOnlyChecker userReadOnlyChecker;
    private final UploadConflictResolver conflictResolver;

    @Override
    public FileNodeVo upload(MultipartFile file, Long userId, Long parentId, String strategy) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return doUpload(file, userId, parentId, strategy);
        } finally {
            lock.unlock();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected FileNodeVo doUpload(MultipartFile file, Long userId, Long parentId, String strategy) {
        userReadOnlyChecker.checkWriteAllowed(userId);

        String fileName = normalizeFileName(file.getOriginalFilename());
        Long resolvedParentId = parentId == null ? 0L : parentId;
        String parentPathName = resolveParentPathName(resolvedParentId, userId);

        FileConflictResolver.ConflictResolution resolution =
                conflictResolver.resolve(userId, resolvedParentId, fileName, strategy);
        if (resolution.skipped()) {
            return null;
        }

        User user = requireUser(userId);
        StorageSpace space = requireSpace(user.getStorageSpaceId());
        long fileSize = file.getSize();

        if (resolution.existingToReplace() != null) {
            conflictResolver.deleteExistingForOverwrite(resolution.existingToReplace(), user);
        }

        long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        if (usedSpace + fileSize > quota) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户配额不足");
        }

        String filePathName = FilePathUtil.buildPathName(parentPathName, resolution.finalName());
        Path physicalPath = FilePathUtil.resolvePhysicalPath(space, userId, filePathName);
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

        FileNode node = buildFileNode(userId, resolvedParentId, resolution.finalName(), fileSize, hash,
                space.getId(), filePathName, filePathName, file.getContentType());
        fileMapper.insert(node);

        user.setUsedSpace(usedSpace + fileSize);
        userMapper.updateById(user);

        FileNodeVo vo = fileConvert.poToVo(node);
        vo.setPhysicalPath(relativizePhysicalPath(space, userId, physicalPath));
        return vo;
    }

    @Override
    public UploadPreCheckVo preCheckUpload(FileUploadPreCheckDto dto, Long userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return doPreCheckUpload(dto, userId);
        } finally {
            lock.unlock();
        }
    }

    protected UploadPreCheckVo doPreCheckUpload(FileUploadPreCheckDto dto, Long userId) {
        Long parentId = dto.getParentId() == null ? 0L : dto.getParentId();
        validateTargetParent(parentId, userId);

        UploadPreCheckVo result = new UploadPreCheckVo();
        result.setConflicts(buildConflictItems(dto, userId, parentId));
        result.setCandidates(buildInstantCandidates(dto, userId));
        return result;
    }

    private List<ConflictItemVo> buildConflictItems(FileUploadPreCheckDto dto, Long userId, Long parentId) {
        FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, parentId, dto.getFileName());
        if (existing == null) {
            return List.of();
        }
        ConflictItemVo vo = new ConflictItemVo();
        vo.setNodeId(existing.getId());
        vo.setSourceName(dto.getFileName());
        vo.setExistingId(existing.getId());
        vo.setExistingName(existing.getName());
        vo.setExistingType(existing.getType());
        vo.setType(existing.getType());
        vo.setSuggestedStrategy(ConflictStrategy.KEEP.getCode());
        return List.of(vo);
    }

    private List<FileNodeVo> buildInstantCandidates(FileUploadPreCheckDto dto, Long userId) {
        if (!StringUtils.hasText(dto.getPartialHash())) {
            return List.of();
        }
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId);
        wrapper.eq(FileNode::getHash, dto.getPartialHash());
        wrapper.eq(FileNode::getType, TYPE_FILE);
        wrapper.orderByDesc(FileNode::getCreateTime);
        return fileMapper.selectList(wrapper).stream()
                .map(fileConvert::poToVo)
                .toList();
    }

    @Override
    public IPage<FileNodeVo> list(FilePageQueryDto dto, Long userId) {
        if (StringUtils.hasText(dto.getName())) {
            return searchByName(dto, userId);
        }

        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId);
        wrapper.eq(FileNode::getParentId, dto.getParentId());
        wrapper.orderByDesc(FileNode::getCreateTime);

        Page<FileNode> page = new Page<>(dto.getPageNum(), dto.getPageSize());
        IPage<FileNode> poPage = fileMapper.selectPage(page, wrapper);
        return poPage.convert(fileConvert::poToVo);
    }

    private IPage<FileNodeVo> searchByName(FilePageQueryDto dto, Long userId) {
        String keyword = dto.getName().trim();
        String likePattern = escapeLikePattern(keyword);
        List<FileNode> records = fileMapper.searchByName(userId, keyword, likePattern);
        Page<FileNodeVo> resultPage = new Page<>(dto.getPageNum(), dto.getPageSize());
        resultPage.setTotal(records.size());

        long offset = (dto.getPageNum() - 1) * dto.getPageSize();
        List<FileNodeVo> pageRecords = records.stream()
                .skip(offset)
                .limit(dto.getPageSize())
                .map(fileConvert::poToVo)
                .toList();
        resultPage.setRecords(pageRecords);
        return resultPage;
    }

    private String escapeLikePattern(String keyword) {
        return keyword.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    @Override
    public FileDownloadResult download(Long fileId, Long userId) {
        FileNode node = fileMapper.selectById(fileId);
        if (node == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文件不存在");
        }
        if (!node.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该文件");
        }

        StorageSpace space = storageSpaceMapper.selectById(node.getStorageSpaceId());
        if (space == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }

        Path physicalPath = FilePathUtil.resolvePhysicalPath(node, space);
        if (!Files.exists(physicalPath)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文件已丢失");
        }

        try {
            InputStream inputStream = Files.newInputStream(physicalPath);
            return new FileDownloadResult(node.getName(), inputStream, node.getMimeType(), node.getSize());
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件读取失败", e);
        }
    }

    @Override
    public FileNodeVo instantUpload(FileInstantUploadDto dto, Long userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return doInstantUpload(dto, userId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected FileNodeVo doInstantUpload(FileInstantUploadDto dto, Long userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        FileNode candidate = fileMapper.selectById(dto.getCandidateId());
        if (candidate == null || !candidate.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "候选文件不存在");
        }
        if (!TYPE_FILE.equals(candidate.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "候选文件类型错误");
        }

        User user = requireUser(userId);
        StorageSpace space = requireSpace(candidate.getStorageSpaceId());

        Long parentId = dto.getParentId() == null ? 0L : dto.getParentId();
        String parentPathName = resolveParentPathName(parentId, userId);
        String fileName = normalizeFileName(dto.getFileName());

        FileConflictResolver.ConflictResolution resolution =
                conflictResolver.resolve(userId, parentId, fileName, dto.getStrategy());
        if (resolution.skipped()) {
            return null;
        }

        long fileSize = candidate.getSize();
        if (resolution.existingToReplace() != null) {
            conflictResolver.deleteExistingForOverwrite(resolution.existingToReplace(), user);
        }

        long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        if (usedSpace + fileSize > quota) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户配额不足");
        }

        Path sourcePath = FilePathUtil.resolvePhysicalPath(candidate, space);
        String filePathName = FilePathUtil.buildPathName(parentPathName, resolution.finalName());
        Path targetPath = FilePathUtil.resolvePhysicalPath(space, userId, filePathName);

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

        FileNode node = buildFileNode(userId, parentId, resolution.finalName(), fileSize,
                candidate.getHash(), space.getId(), filePathName, filePathName, candidate.getMimeType());
        fileMapper.insert(node);

        user.setUsedSpace(usedSpace + fileSize);
        userMapper.updateById(user);

        FileNodeVo vo = fileConvert.poToVo(node);
        vo.setPhysicalPath(relativizePhysicalPath(space, userId, targetPath));
        return vo;
    }

    private void validateTargetParent(Long parentId, Long userId) {
        if (parentId == null || parentId == 0L) {
            return;
        }
        FileNode parent = fileMapper.selectById(parentId);
        if (parent == null || !parent.getUserId().equals(userId) || parent.getDeleteAt() != 0L) {
            throw new BusinessException(ResultCode.NOT_FOUND, "父目录不存在");
        }
        if (!TYPE_FOLDER.equals(parent.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标父节点不是文件夹");
        }
    }

    private String resolveParentPathName(Long parentId, Long userId) {
        if (parentId == null || parentId == 0L) {
            return "/";
        }
        FileNode parent = fileMapper.selectById(parentId);
        if (parent == null || !parent.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "父目录不存在");
        }
        return parent.getPathName();
    }

    private String relativizePhysicalPath(StorageSpace space, Long userId, Path physicalPath) {
        Path base = Path.of(space.getPath(), userId.toString(), "files");
        return base.relativize(physicalPath).toString();
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

    private FileNode buildFileNode(Long userId, Long parentId, String name, long size,
                                   String hash, Long storageSpaceId, String path,
                                   String pathName, String mimeType) {
        FileNode node = new FileNode();
        node.setUserId(userId);
        node.setParentId(parentId);
        node.setName(name);
        node.setType(TYPE_FILE);
        node.setSize(size);
        node.setHash(hash);
        node.setStorageSpaceId(storageSpaceId);
        node.setPath(pathName);
        node.setPathName(pathName);
        node.setMimeType(mimeType);
        node.setStatus(1);
        return node;
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private StorageSpace requireSpace(Long spaceId) {
        if (spaceId == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户未绑定存储空间");
        }
        StorageSpace space = storageSpaceMapper.selectById(spaceId);
        if (space == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        return space;
    }

}
