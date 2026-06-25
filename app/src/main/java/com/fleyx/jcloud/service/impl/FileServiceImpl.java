package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.dto.FileInstantUploadDto;
import com.fleyx.jcloud.model.dto.FilePageQueryDto;
import com.fleyx.jcloud.model.dto.FilePreCheckDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.service.FileService;
import com.fleyx.jcloud.util.FileHashUtil;
import com.fleyx.jcloud.util.FileLinkUtil;
import com.fleyx.jcloud.util.FilePathUtil;
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

    private final FileMapper fileMapper;
    private final UserMapper userMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final FileConvert fileConvert;
    private final UserReadWriteLock userReadWriteLock;
    private final UserReadOnlyChecker userReadOnlyChecker;

    @Override
    public FileNodeVo upload(MultipartFile file, Long userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return doUpload(file, userId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected FileNodeVo doUpload(MultipartFile file, Long userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        if (user.getStorageSpaceId() == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户未绑定存储空间");
        }
        StorageSpace space = storageSpaceMapper.selectById(user.getStorageSpaceId());
        if (space == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }

        long fileSize = file.getSize();
        long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        if (usedSpace + fileSize > quota) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户配额不足");
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            fileName = "unnamed";
        }

        Long parentId = 0L;
        String pathName = "/";
        Path physicalPath = resolvePhysicalPath(space, userId, pathName, fileName);

        try {
            Files.createDirectories(physicalPath.getParent());
            Files.copy(file.getInputStream(), physicalPath);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件保存失败");
        }

        String hash;
        try {
            hash = FileHashUtil.identityHash(physicalPath);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件 hash 计算失败");
        }

        FileNode node = buildFileNode(userId, parentId, fileName, fileSize, hash, space.getId(),
                pathName, pathName, file.getContentType());
        fileMapper.insert(node);

        user.setUsedSpace(usedSpace + fileSize);
        userMapper.updateById(user);

        FileNodeVo vo = fileConvert.poToVo(node);
        vo.setPhysicalPath(relativizePhysicalPath(space, userId, physicalPath));
        return vo;
    }

    @Override
    public IPage<FileNodeVo> list(FilePageQueryDto dto, Long userId) {
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId);
        wrapper.eq(FileNode::getParentId, dto.getParentId());
        wrapper.like(StringUtils.hasText(dto.getName()), FileNode::getName, dto.getName());
        wrapper.orderByDesc(FileNode::getCreateTime);

        Page<FileNode> page = new Page<>(dto.getPageNum(), dto.getPageSize());
        IPage<FileNode> poPage = fileMapper.selectPage(page, wrapper);
        return poPage.convert(fileConvert::poToVo);
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
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件读取失败");
        }
    }

    @Override
    public List<FileNodeVo> preCheck(FilePreCheckDto dto, Long userId) {
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId);
        wrapper.eq(FileNode::getHash, dto.getPartialHash());
        wrapper.eq(FileNode::getType, "file");
        wrapper.orderByDesc(FileNode::getCreateTime);
        return fileMapper.selectList(wrapper).stream()
                .map(fileConvert::poToVo)
                .toList();
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
        if (!"file".equals(candidate.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "候选文件类型错误");
        }

        User user = userMapper.selectById(userId);
        StorageSpace space = storageSpaceMapper.selectById(candidate.getStorageSpaceId());
        if (space == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }

        long fileSize = candidate.getSize();
        long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        if (usedSpace + fileSize > quota) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户配额不足");
        }

        Long parentId = dto.getParentId() == null ? 0L : dto.getParentId();
        String pathName = resolvePathName(parentId, userId);
        Path sourcePath = FilePathUtil.resolvePhysicalPath(candidate, space);
        Path targetPath = resolvePhysicalPath(space, userId, pathName, dto.getFileName());

        if (!Files.exists(sourcePath)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "候选文件物理数据已丢失");
        }

        String actualFullHash;
        try {
            actualFullHash = FileHashUtil.fullHash(sourcePath);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "候选文件完整 hash 计算失败");
        }
        if (!dto.getFullHash().equals(actualFullHash)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "完整 hash 校验失败");
        }

        if (Files.exists(targetPath)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标文件已存在");
        }

        try {
            Files.createDirectories(targetPath.getParent());
            FileLinkUtil.linkOrCopy(sourcePath, targetPath);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "秒传文件复制失败");
        }

        FileNode node = buildFileNode(userId, parentId, dto.getFileName(), fileSize,
                candidate.getHash(), space.getId(), pathName, pathName, candidate.getMimeType());
        fileMapper.insert(node);

        user.setUsedSpace(usedSpace + fileSize);
        userMapper.updateById(user);

        FileNodeVo vo = fileConvert.poToVo(node);
        vo.setPhysicalPath(relativizePhysicalPath(space, userId, targetPath));
        return vo;
    }

    private String resolvePathName(Long parentId, Long userId) {
        if (parentId == null || parentId == 0L) {
            return "/";
        }
        FileNode parent = fileMapper.selectById(parentId);
        if (parent == null || !parent.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "父目录不存在");
        }
        return parent.getPathName();
    }

    private Path resolvePhysicalPath(StorageSpace space, Long userId,
                                     String pathName, String fileName) {
        Path base = Path.of(space.getPath(), userId.toString(), "files");
        String relative = pathName == null || "/".equals(pathName) ? "" : pathName;
        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (relative.isEmpty()) {
            return base.resolve(fileName);
        }
        return base.resolve(relative).resolve(fileName);
    }

    private String relativizePhysicalPath(StorageSpace space, Long userId, Path physicalPath) {
        Path base = Path.of(space.getPath(), userId.toString(), "files");
        return base.relativize(physicalPath).toString();
    }

    private FileNode buildFileNode(Long userId, Long parentId, String name, long size,
                                   String hash, Long storageSpaceId, String path,
                                   String pathName, String mimeType) {
        FileNode node = new FileNode();
        node.setUserId(userId);
        node.setParentId(parentId);
        node.setName(name);
        node.setType("file");
        node.setSize(size);
        node.setHash(hash);
        node.setStorageSpaceId(storageSpaceId);
        node.setPath(path);
        node.setPathName(pathName);
        node.setMimeType(mimeType);
        node.setStatus(1);
        return node;
    }
}
