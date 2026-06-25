package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.PreviewType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.PreviewFileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.bo.PreviewResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.PreviewFile;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.service.FilePreviewGenerator;
import com.fleyx.jcloud.service.FilePreviewService;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    private final List<FilePreviewGenerator> generators;

    private volatile Map<PreviewType, FilePreviewGenerator> generatorMap;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PreviewResult preview(Long fileNodeId, Long userId, PreviewType type) {
        FileNode node = fileMapper.selectById(fileNodeId);
        if (node == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文件不存在");
        }
        if (!node.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该文件");
        }
        if (!"file".equals(node.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "仅支持文件预览");
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
        StorageSpace userSpace = getUserSpace(node);
        Path sourcePath = FilePathUtil.resolvePhysicalPath(node, userSpace);
        if (!Files.exists(sourcePath)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文件已丢失");
        }

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
        String ext = type == PreviewType.TEXT ? "txt" : "jpg";
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
}
