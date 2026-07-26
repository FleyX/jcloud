package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.hutool.core.io.FileUtil;
import com.fleyx.jcloud.common.enums.PreviewType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.PreviewFileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.bo.PreviewResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.model.po.PreviewFile;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.config.PreviewProperties;
import com.fleyx.jcloud.service.FilePreviewGenerator;
import com.fleyx.jcloud.service.FilePreviewService;
import com.fleyx.jcloud.service.RemoteFileService;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文件预览业务实现。
 */
@Service
@RequiredArgsConstructor
public class FilePreviewServiceImpl implements FilePreviewService {

    private final FileMapper fileMapper;
    private final PreviewFileMapper previewFileMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final SystemStorageSpaceProvider systemStorageSpaceProvider;
    private final RemoteFileService remoteFileService;
    private final List<FilePreviewGenerator> generators;
    private final PreviewProperties previewProperties;

    private volatile Map<PreviewType, FilePreviewGenerator> generatorMap;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PreviewResult preview(String fileNodeId, String userId, PreviewType type) {
        return previewByOwner(fileNodeId, userId, UserContext.requireUserCode(), type);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PreviewResult previewByOwner(String fileNodeId, String ownerUserId, String ownerUserCode, PreviewType type) {
        FileNode node = fileMapper.selectById(fileNodeId);
        if (node == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文件不存在");
        }
        if (!node.getUserId().equals(ownerUserId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该文件");
        }
        if (!"file".equals(node.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "仅支持文件预览");
        }

        if (type == PreviewType.OFFICE) {
            String ext = FileUtil.extName(node.getName()).toLowerCase();
            if ("pdf".equals(ext)) {
                // PDF 原文件不转换、不缓存，直接流式返回
                return streamOriginalPdf(node, ownerUserId, ownerUserCode);
            }
            checkOfficeConvertible(node, ext);
        }

        FilePreviewGenerator generator = getGeneratorMap().get(type);
        if (generator == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "不支持的预览类型");
        }

        PreviewFile cached = findCachedPreview(node, type);
        if (cached != null) {
            StorageSpace space = systemStorageSpaceProvider.getSystemSpace();
            Path previewPath = buildAbsolutePath(space, cached.getRelativePath());
            if (Files.exists(previewPath)) {
                return buildResult(cached, previewPath);
            }
        }

        StorageSpace space = systemStorageSpaceProvider.getSystemSpace();
        Path sourcePath = resolvePreviewSourcePath(node, ownerUserId, ownerUserCode);

        String relativePath = buildRelativePath(node, type);
        Path targetPath = buildAbsolutePath(space, relativePath);
        try {
            generator.generate(sourcePath, targetPath);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR,
                    "预览生成失败：" + e.getMessage());
        }

        PreviewFile previewFile = savePreviewRecord(node, type, space, relativePath);
        return buildResult(previewFile, targetPath);
    }

    /**
     * PDF 原文件直接流式返回，不生成预览缓存。
     */
    private PreviewResult streamOriginalPdf(FileNode node, String ownerUserId, String ownerUserCode) {
        Path sourcePath = resolvePreviewSourcePath(node, ownerUserId, ownerUserCode);
        try {
            return new PreviewResult(node.getName(), "application/pdf",
                    Files.size(sourcePath), Files.newInputStream(sourcePath));
        } catch (IOException e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "PDF 预览读取失败", e);
        }
    }

    /**
     * 校验 Office 文档是否可在线转换：格式支持与大小上限。
     */
    private void checkOfficeConvertible(FileNode node, String ext) {
        if (!OfficePdfPreviewGenerator.CONVERTIBLE_EXTENSIONS.contains(ext)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "该格式不支持在线预览");
        }
        if (node.getSize() != null && node.getSize() > previewProperties.getOfficeMaxConvertSize()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件过大，请下载后查看");
        }
    }

    private Path resolvePreviewSourcePath(FileNode node, String ownerUserId, String ownerUserCode) {
        if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            return downloadRemoteToTemp(node, ownerUserId);
        }
        StorageSpace userSpace = getUserSpace(node);
        Path sourcePath = FilePathUtil.resolvePhysicalPath(node, buildResolveContext(node, ownerUserCode, userSpace));
        if (!Files.exists(sourcePath)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文件已丢失");
        }
        return sourcePath;
    }

    private Path downloadRemoteToTemp(FileNode node, String ownerUserId) {
        FileDownloadResult result = remoteFileService.download(node, ownerUserId);
        try {
            Path tempFile = Files.createTempFile("jcloud-remote-preview-", "-" + node.getName());
            try (InputStream is = result.getInputStream()) {
                Files.copy(is, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return tempFile;
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "下载远程文件预览失败", e);
        }
    }

    private Map<PreviewType, FilePreviewGenerator> getGeneratorMap() {
        if (generatorMap == null) {
            generatorMap = generators.stream()
                    .collect(Collectors.toMap(FilePreviewGenerator::supportedType, Function.identity()));
        }
        return generatorMap;
    }

    private PreviewFile findCachedPreview(FileNode node, PreviewType type) {
        LambdaQueryWrapper<PreviewFile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PreviewFile::getFileNodeId, node.getId());
        wrapper.eq(PreviewFile::getType, type.getCode());
        wrapper.eq(PreviewFile::getStatus, 1);
        return previewFileMapper.selectOne(wrapper);
    }

    private StorageSpace getUserSpace(FileNode node) {
        StorageSpace space = storageSpaceMapper.selectById(node.getStorageSpaceId());
        if (space == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户存储空间不存在");
        }
        return space;
    }

    private String buildRelativePath(FileNode node, PreviewType type) {
        String hash = node.getHash();
        if (hash == null || hash.isBlank()) {
            hash = String.valueOf(node.getId());
        }
        String safeHash = hash.replaceAll("[^a-zA-Z0-9\\-_.]", "_");
        String ext = switch (type) {
            case TEXT -> "txt";
            case OFFICE -> "pdf";
            default -> "jpg";
        };
        return String.format("previews/%s/%s.%s",
                safeHash.substring(0, Math.min(2, safeHash.length())), safeHash, ext);
    }

    private Path buildAbsolutePath(StorageSpace space, String relativePath) {
        return Path.of(space.getPath()).resolve(relativePath);
    }

    private PreviewFile savePreviewRecord(FileNode node, PreviewType type,
                                          StorageSpace space, String relativePath) {
        Path previewPath = buildAbsolutePath(space, relativePath);
        long size;
        try {
            size = Files.size(previewPath);
        } catch (Exception e) {
            size = 0L;
        }

        PreviewFile existing = findCachedPreview(node, type);
        if (existing != null) {
            existing.setRelativePath(relativePath);
            existing.setStorageSpaceId(space.getId());
            existing.setSize(size);
            previewFileMapper.updateById(existing);
            return existing;
        }

        PreviewFile record = new PreviewFile();
        record.setFileNodeId(node.getId());
        record.setType(type.getCode());
        record.setStorageSpaceId(space.getId());
        record.setRelativePath(relativePath);
        record.setSize(size);
        record.setStatus(1);
        previewFileMapper.insert(record);
        return record;
    }

    private PreviewResult buildResult(PreviewFile previewFile, Path previewPath) {
        try {
            InputStream is = Files.newInputStream(previewPath);
            String contentType = Files.probeContentType(previewPath);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }
            String fileName = previewPath.getFileName().toString();
            return new PreviewResult(fileName, contentType, previewFile.getSize(), is);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "预览读取失败");
        }
    }

    private FilePathUtil.ResolveContext buildResolveContext(FileNode node, String username, StorageSpace space) {
        String userId = node.getUserId();
        Set<String> ancestorIds = FilePathUtil.extractAncestorIds(List.of(node));
        Map<String, String> cache = new HashMap<>();
        if (!ancestorIds.isEmpty()) {
            List<FileNode> ancestors = fileMapper.selectBatchIds(ancestorIds);
            for (FileNode ancestor : ancestors) {
                if (userId.equals(ancestor.getUserId())) {
                    cache.put(ancestor.getId(), ancestor.getName());
                }
            }
        }
        return FilePathUtil.contextOf(space, username, cache);
    }
}
