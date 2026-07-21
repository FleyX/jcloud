package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.constant.StorageConstant;
import com.fleyx.jcloud.common.enums.BatchUploadErrorCode;
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
import com.fleyx.jcloud.model.vo.BatchUploadPreCheckItemVo;
import com.fleyx.jcloud.model.vo.ConflictItemVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UploadPreCheckVo;
import com.fleyx.jcloud.service.FileService;
import com.fleyx.jcloud.service.FolderPathService;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.service.support.FileNodeSupport;
import com.fleyx.jcloud.service.support.FilePathSupport;
import com.fleyx.jcloud.service.support.UserSpaceSupport;
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.FileHashUtil;
import com.fleyx.jcloud.util.FileLinkUtil;
import com.fleyx.jcloud.util.FileNodeUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.BatchUploadHelper;
import com.fleyx.jcloud.util.FileConflictResolver;
import com.fleyx.jcloud.util.UploadConflictResolver;
import com.fleyx.jcloud.util.UserReadOnlyChecker;
import com.fleyx.jcloud.util.UserReadWriteLock;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final FolderPathService folderPathService;
    private final RemoteFileService remoteFileService;
    private final FileNodeSupport fileNodeSupport;
    private final FilePathSupport filePathSupport;
    private final UserSpaceSupport userSpaceSupport;

    @Lazy
    @Autowired
    private FileServiceImpl self;

    @Override
    public FileNodeVo upload(MultipartFile file, String userId, String parentId, String strategy) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return self.doUpload(file, userId, parentId, strategy);
        } finally {
            lock.unlock();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public FileNodeVo doUpload(MultipartFile file, String userId, String parentId, String strategy) {
        userReadOnlyChecker.checkWriteAllowed(userId);

        String fileName = normalizeFileName(file.getOriginalFilename());
        String resolvedParentId = FileNodeUtil.normalizeParentId(parentId);
        FileNode parentNode = resolveParentNode(resolvedParentId, userId);

        FileConflictResolver.ConflictResolution resolution =
                conflictResolver.resolve(userId, resolvedParentId, fileName, strategy);
        if (resolution.skipped()) {
            return null;
        }

        if (parentNode != null && FileNodeConstants.SOURCE_REMOTE.equals(parentNode.getSourceType())) {
            return remoteFileService.upload(file, parentNode, userId, resolution.finalName());
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

        user.setUsedSpace(usedSpace + fileSize);
        userMapper.updateById(user);

        FileNodeVo vo = fileConvert.poToVo(node);
        vo.setPhysicalPath(relativizePhysicalPath(space, username, physicalPath));
        return vo;
    }

    @Override
    public List<BatchUploadPreCheckItemVo> preCheckUpload(List<FileUploadPreCheckDto> items, String userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return doBatchPreCheckUpload(items, userId);
        } finally {
            lock.unlock();
        }
    }

    private List<BatchUploadPreCheckItemVo> doBatchPreCheckUpload(List<FileUploadPreCheckDto> items, String userId) {
        BatchUploadHelper.validateBatchItems(items, 150, "预检");
        checkBatchQuota(items, userId);

        List<BatchUploadPreCheckItemVo> results = new ArrayList<>(items.size());
        Set<String> clientFileIds = new HashSet<>();
        Set<String> processedPaths = new HashSet<>();

        for (FileUploadPreCheckDto item : items) {
            String clientFileId = item.getClientFileId();
            BatchUploadPreCheckItemVo result = new BatchUploadPreCheckItemVo();
            result.setClientFileId(clientFileId);

            BatchUploadErrorCode clientIdError = BatchUploadHelper.validateClientFileId(clientFileId, clientFileIds);
            if (clientIdError != null) {
                BatchUploadHelper.fillError(result, clientIdError);
                results.add(result);
                continue;
            }

            try {
                String pathKey = BatchUploadHelper.buildPathKey(item.getParentId(), item.getRelativePath(), item.getFileName());
                if (!processedPaths.add(pathKey)) {
                    BatchUploadHelper.fillError(result, BatchUploadErrorCode.DUPLICATE_FILE_IN_BATCH);
                    results.add(result);
                    continue;
                }

                UploadPreCheckVo data = doPreCheckUpload(item, userId);
                result.setStatus("success");
                result.setData(data);
            } catch (BusinessException e) {
                BatchUploadHelper.fillError(result, mapErrorCode(e), e.getMessage());
            } catch (Exception e) {
                BatchUploadHelper.fillError(result, BatchUploadErrorCode.SYSTEM_ERROR, e.getMessage());
            }
            results.add(result);
        }
        return results;
    }

    protected UploadPreCheckVo doPreCheckUpload(FileUploadPreCheckDto dto, String userId) {
        String parentId = FileNodeUtil.normalizeParentId(dto.getParentId());
        FileNode parentNode = resolveParentNode(parentId, userId);

        if (parentNode != null && FileNodeConstants.SOURCE_REMOTE.equals(parentNode.getSourceType())) {
            if (StringUtils.hasText(dto.getRelativePath())) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "远程目录暂不支持文件夹上传");
            }
            return remoteFileService.preCheckUpload(dto, parentNode, userId);
        }

        validateTargetParent(parentId, userId);

        String finalParentId = parentId;
        String finalFileName = dto.getFileName();
        if (StringUtils.hasText(dto.getRelativePath())) {
            finalParentId = folderPathService.resolveOrCreateFolderPath(userId, parentId, dto.getRelativePath());
            finalFileName = extractFileNameFromRelativePath(dto.getRelativePath());
        }

        UploadPreCheckVo result = new UploadPreCheckVo();
        result.setConflicts(buildConflictItems(finalFileName, dto.getSize(), userId, finalParentId));
        result.setCandidates(buildInstantCandidates(finalFileName, dto.getPartialHash(), userId));
        return result;
    }

    private List<ConflictItemVo> buildConflictItems(String fileName, Long size, String userId, String parentId) {
        FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, parentId, fileName);
        if (existing == null) {
            return List.of();
        }
        ConflictItemVo vo = new ConflictItemVo();
        vo.setNodeId(existing.getId());
        vo.setSourceName(fileName);
        vo.setExistingId(existing.getId());
        vo.setExistingName(existing.getName());
        vo.setExistingType(existing.getType());
        vo.setType(existing.getType());
        vo.setSuggestedStrategy(ConflictStrategy.KEEP.getCode());
        return List.of(vo);
    }

    private List<FileNodeVo> buildInstantCandidates(String fileName, String partialHash, String userId) {
        if (!StringUtils.hasText(partialHash)) {
            return List.of();
        }
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId);
        wrapper.eq(FileNode::getHash, partialHash);
        wrapper.eq(FileNode::getType, TYPE_FILE);
        wrapper.and(w -> w.eq(FileNode::getSourceType, FileNodeConstants.SOURCE_LOCAL)
                .or()
                .isNull(FileNode::getSourceType));
        wrapper.orderByDesc(FileNode::getCreateTime);
        return fileMapper.selectList(wrapper).stream()
                .map(fileConvert::poToVo)
                .toList();
    }

    private String extractFileNameFromRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        String normalized = relativePath.replace("\\", "/");
        int lastSlashIndex = normalized.lastIndexOf('/');
        return lastSlashIndex < 0 ? normalized : normalized.substring(lastSlashIndex + 1);
    }

    @Override
    public IPage<FileNodeVo> list(FilePageQueryDto dto, String userId) {
        if (StringUtils.hasText(dto.getName())) {
            return searchByName(dto, userId);
        }

        String parentId = FileNodeUtil.normalizeParentId(dto.getParentId());
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId);
        wrapper.eq(FileNode::getParentId, parentId);
        applySort(wrapper, dto);

        Page<FileNode> page = new Page<>(dto.getPageNum(), dto.getPageSize());
        IPage<FileNode> poPage = fileMapper.selectPage(page, wrapper);
        return poPage.convert(fileConvert::poToVo);
    }

    private IPage<FileNodeVo> searchByName(FilePageQueryDto dto, String userId) {
        String keyword = dto.getName().trim();
        String likePattern = escapeLikePattern(keyword);
        List<FileNode> records = fileMapper.searchByName(userId, keyword, likePattern);
        if (StringUtils.hasText(dto.getSortField())) {
            records.sort(buildComparator(dto));
        }
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

    private void applySort(LambdaQueryWrapper<FileNode> wrapper, FilePageQueryDto dto) {
        String field = dto.getSortField();
        boolean asc = "asc".equalsIgnoreCase(dto.getSortOrder());
        if ("name".equals(field)) {
            if (asc) {
                wrapper.orderByAsc(FileNode::getName);
            } else {
                wrapper.orderByDesc(FileNode::getName);
            }
        } else if ("size".equals(field)) {
            if (asc) {
                wrapper.orderByAsc(FileNode::getSize);
            } else {
                wrapper.orderByDesc(FileNode::getSize);
            }
        } else {
            if (asc) {
                wrapper.orderByAsc(FileNode::getCreateTime);
            } else {
                wrapper.orderByDesc(FileNode::getCreateTime);
            }
        }
    }

    private Comparator<FileNode> buildComparator(FilePageQueryDto dto) {
        String field = dto.getSortField();
        boolean asc = "asc".equalsIgnoreCase(dto.getSortOrder());
        Comparator<FileNode> comparator;
        if ("name".equals(field)) {
            comparator = Comparator.comparing(FileNode::getName, Comparator.nullsFirst(String::compareTo));
        } else if ("size".equals(field)) {
            comparator = Comparator.comparing(FileNode::getSize, Comparator.nullsFirst(Long::compareTo));
        } else {
            comparator = Comparator.comparing(FileNode::getCreateTime, Comparator.nullsFirst(Comparator.naturalOrder()));
        }
        return asc ? comparator : comparator.reversed();
    }

    @Override
    public FileDownloadResult download(String fileId, String userId) {
        User user = userSpaceSupport.requireUser(userId);
        FileNode node = fileMapper.selectById(fileId);
        if (node == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文件不存在");
        }
        if (!node.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该文件");
        }

        if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            return remoteFileService.download(node, userId);
        }

        StorageSpace space = storageSpaceMapper.selectById(node.getStorageSpaceId());
        if (space == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }

        FilePathUtil.ResolveContext ctx = buildResolveContext(node, user.getUsername(), space);
        Path physicalPath = FilePathUtil.resolvePhysicalPath(node, ctx);
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
    public List<FileNodeVo> listChildFolders(String parentId, String userId) {
        String normalizedParentId = FileNodeUtil.normalizeParentId(parentId);
        validateTargetParent(normalizedParentId, userId);
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileNode::getUserId, userId);
        wrapper.eq(FileNode::getParentId, normalizedParentId);
        wrapper.eq(FileNode::getType, TYPE_FOLDER);
        wrapper.orderByAsc(FileNode::getName);
        return fileMapper.selectList(wrapper).stream()
                .map(fileConvert::poToVo)
                .toList();
    }

    @Override
    public FileNodeVo instantUpload(FileInstantUploadDto dto, String userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return self.doInstantUpload(dto, userId);
        } finally {
            lock.unlock();
        }
    }

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
        FileNode parentNode = resolveParentNode(parentId, userId);
        if (parentNode != null && FileNodeConstants.SOURCE_REMOTE.equals(parentNode.getSourceType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "远程目录不支持秒传");
        }
        validateTargetParent(parentId, userId);
        String finalParentId = parentId;
        String finalFileName = dto.getFileName();
        if (StringUtils.hasText(dto.getRelativePath())) {
            finalParentId = folderPathService.resolveOrCreateFolderPath(userId, parentId, dto.getRelativePath());
            finalFileName = extractFileNameFromRelativePath(dto.getRelativePath());
        }

        parentNode = resolveParentNode(finalParentId, userId);
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
        FilePathUtil.ResolveContext ctx = buildResolveContext(candidate, username, space);
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

        user.setUsedSpace(usedSpace + fileSize);
        userMapper.updateById(user);

        FileNodeVo vo = fileConvert.poToVo(node);
        vo.setPhysicalPath(relativizePhysicalPath(space, username, targetPath));
        return vo;
    }

    private void validateTargetParent(String parentId, String userId) {
        if (parentId == null || FileNodeConstants.ROOT_ID.equals(parentId)) {
            return;
        }
        FileNode parent = fileMapper.selectById(parentId);
        if (parent == null || !parent.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "父目录不存在");
        }
        if (!TYPE_FOLDER.equals(parent.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标父节点不是文件夹");
        }
    }

    private FileNode resolveParentNode(String parentId, String userId) {
        if (FileNodeConstants.ROOT_ID.equals(parentId)) {
            return null;
        }
        FileNode parent = fileMapper.selectById(parentId);
        if (parent == null || !parent.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "父目录不存在");
        }
        if (!TYPE_FOLDER.equals(parent.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标父节点不是文件夹");
        }
        return parent;
    }

    private FilePathUtil.ResolveContext buildResolveContext(FileNode node, String username, StorageSpace space) {
        String userId = node.getUserId();
        Set<String> ancestorIds = FilePathUtil.extractAncestorIds(List.of(node));
        Map<String, String> cache = filePathSupport.queryAncestorNames(userId, ancestorIds);
        return FilePathUtil.contextOf(space, username, cache);
    }

    private String relativizePhysicalPath(StorageSpace space, String username, Path physicalPath) {
        Path base = Path.of(space.getPath(), StorageConstant.FILES_DIR, username);
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

    private void checkBatchQuota(List<FileUploadPreCheckDto> items, String userId) {
        long totalSize = items.stream()
                .filter(dto -> !isRemoteParent(FileNodeUtil.normalizeParentId(dto.getParentId()), userId))
                .mapToLong(dto -> dto.getSize() == null ? 0L : dto.getSize())
                .sum();
        if (totalSize <= 0) {
            return;
        }
        User user = userSpaceSupport.requireUser(userId);
        long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        if (quota > 0 && usedSpace + totalSize > quota) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户配额不足");
        }
    }

    private boolean isRemoteParent(String parentId, String userId) {
        if (FileNodeConstants.ROOT_ID.equals(parentId)) {
            return false;
        }
        FileNode parent = fileMapper.selectById(parentId);
        return parent != null && userId.equals(parent.getUserId())
                && FileNodeConstants.SOURCE_REMOTE.equals(parent.getSourceType());
    }

    private BatchUploadErrorCode mapErrorCode(BusinessException e) {
        String msg = e.getMessage();
        if (msg == null) {
            return BatchUploadErrorCode.BUSINESS_ERROR;
        }
        return switch (msg) {
            case "文件名包含非法字符" -> BatchUploadErrorCode.INVALID_FILE_NAME;
            case "目标父节点不是文件夹", "父目录不存在" -> BatchUploadErrorCode.PARENT_NOT_FOUND;
            case "相对路径不能以根分隔符开头", "相对路径包含非法的 '..' 段", "文件夹层级超过最大限制" ->
                    BatchUploadErrorCode.PATH_TRAVERSAL;
            case "用户配额不足" -> BatchUploadErrorCode.INSUFFICIENT_SPACE;
            default -> BatchUploadErrorCode.BUSINESS_ERROR;
        };
    }

}
