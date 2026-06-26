package com.fleyx.jcloud.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileChunkMapper;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.dto.ChunkedUploadInitDto;
import com.fleyx.jcloud.model.po.FileChunk;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.ChunkedUploadChunkVo;
import com.fleyx.jcloud.model.vo.ChunkedUploadInitVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.service.ChunkedUploadService;
import com.fleyx.jcloud.util.FileHashUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.UserReadOnlyChecker;
import com.fleyx.jcloud.util.UserReadWriteLock;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 分片上传服务实现。
 */
@Service
@RequiredArgsConstructor
public class ChunkedUploadServiceImpl implements ChunkedUploadService {

    /**
     * 固定分片大小：10MB。
     */
    public static final long CHUNK_SIZE = 10L * 1024 * 1024;

    private static final String META_FILE_NAME = ".upload";

    private final UserReadOnlyChecker userReadOnlyChecker;
    private final UserMapper userMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final FileChunkMapper fileChunkMapper;
    private final FileMapper fileMapper;
    private final FileConvert fileConvert;
    private final UserReadWriteLock userReadWriteLock;

    @Override
    public ChunkedUploadInitVo init(Long userId, ChunkedUploadInitDto dto) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        User user = requireUser(userId);
        StorageSpace space = requireSpace(user.getStorageSpaceId());

        if (!StringUtils.hasText(dto.getFileName())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件名不能为空");
        }
        if (dto.getSize() == null || dto.getSize() <= 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文件大小必须大于 0");
        }

        long fileSize = dto.getSize();
        long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        if (usedSpace + fileSize > quota) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户配额不足");
        }

        String uploadId = generateUploadId();
        Path tempDir = resolveTempDir(space, userId, uploadId);
        try {
            Files.createDirectories(tempDir);
            saveUploadMeta(tempDir, dto);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "创建上传临时目录失败");
        }

        int totalChunks = (int) ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
        ChunkedUploadInitVo vo = new ChunkedUploadInitVo();
        vo.setUploadId(uploadId);
        vo.setChunkSize(CHUNK_SIZE);
        vo.setTotalChunks(totalChunks);
        return vo;
    }

    @Override
    public ChunkedUploadChunkVo uploadChunk(Long userId, String uploadId, Integer chunkIndex,
                                            MultipartFile chunk, String chunkHash) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        UploadContext context = loadUploadContext(userId, uploadId);
        if (chunkIndex == null || chunkIndex < 0 || chunkIndex >= context.totalChunks()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "分片索引超出范围");
        }

        String actualHash;
        try (InputStream is = chunk.getInputStream()) {
            actualHash = DigestUtil.md5Hex(is);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "读取分片失败");
        }
        if (!actualHash.equals(chunkHash)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "分片 hash 校验失败");
        }

        Path chunkPath = context.tempDir().resolve(chunkFileName(chunkIndex));
        try {
            Files.copy(chunk.getInputStream(), chunkPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "保存分片失败");
        }

        FileChunk record = new FileChunk();
        record.setUploadId(uploadId);
        record.setUserId(userId);
        record.setChunkIndex(chunkIndex);
        record.setChunkHash(chunkHash);
        record.setSize(chunk.getSize());
        record.setStatus(1);
        fileChunkMapper.insert(record);

        ChunkedUploadChunkVo vo = new ChunkedUploadChunkVo();
        vo.setChunkIndex(chunkIndex);
        vo.setStatus("success");
        return vo;
    }

    @Override
    public List<Integer> listUploadedChunks(Long userId, String uploadId) {
        loadUploadContext(userId, uploadId);
        LambdaQueryWrapper<FileChunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileChunk::getUploadId, uploadId);
        wrapper.eq(FileChunk::getUserId, userId);
        wrapper.orderByAsc(FileChunk::getChunkIndex);
        return fileChunkMapper.selectList(wrapper).stream()
                .map(FileChunk::getChunkIndex)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileNodeVo complete(Long userId, String uploadId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return doComplete(userId, uploadId);
        } finally {
            lock.unlock();
        }
    }

    private FileNodeVo doComplete(Long userId, String uploadId) {
        UploadContext context = loadUploadContext(userId, uploadId);
        User user = context.user();

        List<FileChunk> chunks = fileChunkMapper.selectList(
                new LambdaQueryWrapper<FileChunk>()
                        .eq(FileChunk::getUploadId, uploadId)
                        .eq(FileChunk::getUserId, userId)
                        .orderByAsc(FileChunk::getChunkIndex));

        validateChunksComplete(chunks, context);

        long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        if (usedSpace + context.size() > quota) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户配额不足");
        }

        String pathName = resolvePathName(context.parentId(), userId);
        Path targetPath = FilePathUtil.resolvePhysicalPath(context.space(), userId, pathName, context.fileName());

        mergeChunks(context, chunks, targetPath);

        String hash;
        try {
            hash = FileHashUtil.identityHash(targetPath);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "计算文件 hash 失败");
        }
        String mimeType = probeContentType(targetPath);

        FileNode node = new FileNode();
        node.setUserId(userId);
        node.setParentId(context.parentId());
        node.setName(context.fileName());
        node.setType("file");
        node.setSize(context.size());
        node.setHash(hash);
        node.setStorageSpaceId(context.space().getId());
        node.setPath(pathName);
        node.setPathName(pathName);
        node.setMimeType(mimeType);
        node.setStatus(1);
        fileMapper.insert(node);

        user.setUsedSpace(usedSpace + context.size());
        userMapper.updateById(user);

        cleanupUpload(context.tempDir(), uploadId, userId);

        FileNodeVo vo = fileConvert.poToVo(node);
        vo.setPhysicalPath(buildPhysicalPath(context.space(), userId, pathName, context.fileName()));
        return vo;
    }

    private void validateChunksComplete(List<FileChunk> chunks, UploadContext context) {
        long uploadedSize = chunks.stream().mapToLong(FileChunk::getSize).sum();
        if (uploadedSize != context.size()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "分片总大小与文件大小不一致");
        }
        if (chunks.size() != context.totalChunks()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "分片尚未全部上传完成");
        }
        for (int i = 0; i < context.totalChunks(); i++) {
            if (chunks.get(i).getChunkIndex() != i) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "分片尚未全部上传完成");
            }
        }
    }

    private String resolvePathName(long parentId, Long userId) {
        if (parentId == 0L) {
            return "/";
        }
        FileNode parent = fileMapper.selectById(parentId);
        if (parent == null || !Objects.equals(parent.getUserId(), userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "父文件夹不存在");
        }
        if (!"folder".equals(parent.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "父节点不是文件夹");
        }
        return parent.getPathName();
    }

    private void mergeChunks(UploadContext context, List<FileChunk> chunks, Path targetPath) {
        try {
            Files.createDirectories(targetPath.getParent());
            try (OutputStream out = Files.newOutputStream(targetPath, StandardOpenOption.CREATE_NEW)) {
                for (FileChunk chunk : chunks) {
                    Path chunkPath = context.tempDir().resolve(chunkFileName(chunk.getChunkIndex()));
                    Files.copy(chunkPath, out);
                }
            }
        } catch (FileAlreadyExistsException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "目标文件已存在");
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "合并分片失败");
        }
    }

    private String probeContentType(Path path) {
        try {
            String mimeType = Files.probeContentType(path);
            return mimeType == null ? "application/octet-stream" : mimeType;
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    private void cleanupUpload(Path tempDir, String uploadId, Long userId) {
        fileChunkMapper.delete(
                new LambdaQueryWrapper<FileChunk>()
                        .eq(FileChunk::getUploadId, uploadId)
                        .eq(FileChunk::getUserId, userId));
        deleteDirectory(tempDir);
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException ignored) {
                            // ignore cleanup failures
                        }
                    });
        } catch (IOException ignored) {
            // ignore cleanup failures
        }
    }

    private String buildPhysicalPath(StorageSpace space, Long userId, String pathName, String fileName) {
        String base = space.getPath() + "/" + userId + "/files";
        String relative = "/".equals(pathName) ? "" : pathName;
        return base + relative + "/" + fileName;
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

    private String generateUploadId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private Path resolveTempDir(StorageSpace space, Long userId, String uploadId) {
        return Path.of(space.getPath(), userId.toString(), "tmp", uploadId);
    }

    private void saveUploadMeta(Path tempDir, ChunkedUploadInitDto dto) throws IOException {
        Properties props = new Properties();
        props.setProperty("fileName", dto.getFileName());
        props.setProperty("size", String.valueOf(dto.getSize()));
        props.setProperty("parentId", String.valueOf(dto.getParentId() == null ? 0L : dto.getParentId()));
        try (java.io.OutputStream out = Files.newOutputStream(tempDir.resolve(META_FILE_NAME))) {
            props.store(out, "chunked upload metadata");
        }
    }

    private UploadContext loadUploadContext(Long userId, String uploadId) {
        User user = requireUser(userId);
        StorageSpace space = requireSpace(user.getStorageSpaceId());
        Path tempDir = resolveTempDir(space, userId, uploadId);
        if (!Files.exists(tempDir)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "上传任务不存在");
        }
        Properties meta = readUploadMeta(tempDir);
        String fileName = meta.getProperty("fileName");
        long size = Long.parseLong(meta.getProperty("size"));
        long parentId = Long.parseLong(meta.getProperty("parentId", "0"));
        int totalChunks = (int) ((size + CHUNK_SIZE - 1) / CHUNK_SIZE);
        return new UploadContext(user, space, tempDir, fileName, size, parentId, totalChunks);
    }

    private Properties readUploadMeta(Path tempDir) {
        Path metaPath = tempDir.resolve(META_FILE_NAME);
        if (!Files.exists(metaPath)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "上传任务元数据丢失");
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(metaPath)) {
            props.load(in);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "读取上传任务元数据失败");
        }
        return props;
    }

    private String chunkFileName(int chunkIndex) {
        return "chunk-" + chunkIndex;
    }

    private record UploadContext(User user, StorageSpace space, Path tempDir,
                                 String fileName, long size, long parentId, int totalChunks) {
    }
}
