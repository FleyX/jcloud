package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.constant.StorageConstant;
import com.fleyx.jcloud.common.enums.FileChangeOperation;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.event.FileTreeChangedEvent;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileChunkMapper;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.convert.FileConvert;
import com.fleyx.jcloud.model.po.FileChunk;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.service.RemoteFileOperationService;
import com.fleyx.jcloud.service.RemoteProtocolAdapter;
import com.fleyx.jcloud.service.impl.RemoteProtocolAdapterFactory;
import com.fleyx.jcloud.util.FileConflictResolver;
import com.fleyx.jcloud.util.FileHashUtil;
import com.fleyx.jcloud.util.FilePathUtil;
import com.fleyx.jcloud.util.RemoteMountLock;
import com.fleyx.jcloud.util.UploadConflictResolver;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 分片上传合并完成支撑组件。
 */
@Component
@RequiredArgsConstructor
public class ChunkedUploadCompleteSupport {

    private static final String TYPE_FOLDER = "folder";

    private final FileMapper fileMapper;
    private final UserMapper userMapper;
    private final FileChunkMapper fileChunkMapper;
    private final FileConvert fileConvert;
    private final UploadConflictResolver conflictResolver;
    private final RemoteMountMapper remoteMountMapper;
    private final RemoteProtocolAdapterFactory adapterFactory;
    private final RemoteMountLock remoteMountLock;
    private final RemoteFileOperationService remoteFileOperationService;
    private final ChunkedUploadChunkSupport chunkedUploadChunkSupport;
    private final FileNodeSupport fileNodeSupport;
    private final FilePathSupport filePathSupport;
    private final FileChangeEventSupport fileChangeEventSupport;

    /**
     * 完成分片上传，合并分片并创建文件节点。
     *
     * @param userId   用户 ID
     * @param uploadId 上传任务 ID
     * @param strategy 冲突解决策略
     * @return 创建的文件节点视图；跳过返回 {@code null}
     */
    @Transactional(rollbackFor = Exception.class)
    public FileNodeVo doComplete(String userId, String uploadId, String strategy) {
        ChunkedUploadChunkSupport.UploadContext context = chunkedUploadChunkSupport.loadUploadContext(userId, uploadId);
        User user = context.user();
        String username = user.getUsername();

        List<FileChunk> chunks = fileChunkMapper.selectList(
                new LambdaQueryWrapper<FileChunk>()
                        .eq(FileChunk::getUploadId, uploadId)
                        .eq(FileChunk::getUserId, userId)
                        .orderByAsc(FileChunk::getChunkIndex));

        validateChunksComplete(chunks, context);

        FileNode parentNode = fileMapper.selectById(context.parentId());
        if (parentNode != null && FileNodeConstants.SOURCE_REMOTE.equals(parentNode.getSourceType())) {
            return doRemoteComplete(context, uploadId, chunks, parentNode, userId, strategy);
        }

        String parentPathName = resolveParentPathName(context.parentId(), userId);
        FileConflictResolver.ConflictResolution resolution =
                conflictResolver.resolve(userId, context.parentId(), context.fileName(), strategy);
        if (resolution.skipped()) {
            cleanupUpload(context.tempDir(), uploadId, userId);
            return null;
        }

        if (resolution.existingToReplace() != null) {
            conflictResolver.deleteExistingForOverwrite(resolution.existingToReplace(), user);
        }

        long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        long quota = user.getQuota() == null ? 0L : user.getQuota();
        if (quota > 0 && usedSpace + context.size() > quota) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户配额不足");
        }

        String filePathName = FilePathUtil.buildPathName(parentPathName, resolution.finalName());
        Path targetPath = FilePathUtil.resolvePhysicalPath(context.space(), username, filePathName);

        mergeChunks(context, chunks, targetPath);

        String hash;
        try {
            hash = FileHashUtil.identityHash(targetPath);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "计算文件 hash 失败");
        }
        String mimeType = probeContentType(targetPath);

        FileNode node = fileNodeSupport.buildFileNode(userId, context.parentId(), resolution.finalName(),
                context.size(), hash, context.space().getId(), mimeType);
        fileNodeSupport.setNodePath(node, context.parentId());
        fileMapper.insert(node);

        fileChangeEventSupport.publishAfterCommit(new FileTreeChangedEvent(this, FileChangeOperation.CREATE,
                userId, node.getId(), node.getType(), node.getName(), node.getSize(),
                null, context.parentId(), null, node.getPath()));

        user.setUsedSpace(usedSpace + context.size());
        userMapper.updateById(user);

        cleanupUpload(context.tempDir(), uploadId, userId);

        FileNodeVo vo = fileConvert.poToVo(node);
        vo.setPhysicalPath(buildPhysicalPath(context.space(), username, filePathName));
        return vo;
    }

    private FileNodeVo doRemoteComplete(ChunkedUploadChunkSupport.UploadContext context, String uploadId,
                                        List<FileChunk> chunks, FileNode parentNode,
                                        String userId, String strategy) {
        RemoteMount mount = remoteMountMapper.selectById(parentNode.getRemoteMountId());
        if (mount == null || !userId.equals(mount.getUserId()) || mount.getDeleteAt() != 0L) {
            throw new BusinessException(ResultCode.NOT_FOUND, "远程挂载不存在");
        }

        FileConflictResolver.ConflictResolution resolution =
                conflictResolver.resolve(userId, context.parentId(), context.fileName(), strategy);
        if (resolution.skipped()) {
            cleanupUpload(context.tempDir(), uploadId, userId);
            return null;
        }

        Path tempFile = context.tempDir().resolve(context.fileName());
        mergeChunks(context, chunks, tempFile);

        RLock lock = remoteMountLock.getLock(mount.getId());
        lock.lock();
        try {
            if (resolution.existingToReplace() != null) {
                fileMapper.deleteById(resolution.existingToReplace().getId());
            }

            String parentRemotePath = remoteFileOperationService.deriveRemotePath(parentNode, mount);
            String newRemotePath = FilePathUtil.buildPathName(parentRemotePath, resolution.finalName());
            String mimeType = probeContentType(tempFile);

            RemoteProtocolAdapter adapter = adapterFactory.create(mount);
            try (InputStream is = Files.newInputStream(tempFile)) {
                adapter.upload(newRemotePath, is, context.size(), mimeType);
            }

            FileNode node = new FileNode();
            node.setUserId(userId);
            node.setParentId(context.parentId());
            node.setName(resolution.finalName());
            node.setType("file");
            node.setSize(context.size());
            node.setHash(null);
            node.setSourceType(FileNodeConstants.SOURCE_REMOTE);
            node.setRemoteMountId(mount.getId());
            fileNodeSupport.setNodePath(node, context.parentId());
            node.setMimeType(mimeType);
            node.setStatus(1);
            fileMapper.insert(node);

            fileChangeEventSupport.publishAfterCommit(new FileTreeChangedEvent(this, FileChangeOperation.CREATE,
                    userId, node.getId(), node.getType(), node.getName(), node.getSize(),
                    null, context.parentId(), null, node.getPath()));

            cleanupUpload(context.tempDir(), uploadId, userId);
            return fileConvert.poToVo(node);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "读取合并文件失败", e);
        } finally {
            lock.unlock();
        }
    }

    private void validateChunksComplete(List<FileChunk> chunks, ChunkedUploadChunkSupport.UploadContext context) {
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

    private String resolveParentPathName(String parentId, String userId) {
        if (parentId == null || FileNodeConstants.ROOT_ID.equals(parentId)) {
            return "/";
        }
        FileNode parent = fileMapper.selectById(parentId);
        if (parent == null || !Objects.equals(parent.getUserId(), userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "父文件夹不存在");
        }
        if (!TYPE_FOLDER.equals(parent.getType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "父节点不是文件夹");
        }
        return filePathSupport.resolveNamePath(parent, userId);
    }

    private void mergeChunks(ChunkedUploadChunkSupport.UploadContext context, List<FileChunk> chunks, Path targetPath) {
        try {
            Files.createDirectories(targetPath.getParent());
            try (OutputStream out = Files.newOutputStream(targetPath, StandardOpenOption.CREATE_NEW)) {
                for (FileChunk chunk : chunks) {
                    Path chunkPath = context.tempDir().resolve("chunk-" + chunk.getChunkIndex());
                    Files.copy(chunkPath, out);
                }
            }
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

    private void cleanupUpload(Path tempDir, String uploadId, String userId) {
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

    private String buildPhysicalPath(StorageSpace space, String username, String pathName) {
        String base = space.getPath() + "/" + StorageConstant.FILES_DIR + "/" + username;
        String relative = FilePathUtil.stripLeadingSlash(pathName);
        return base + (relative.isEmpty() ? "" : "/" + relative);
    }
}
