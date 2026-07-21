package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.model.dto.ChunkedUploadCompleteDto;
import com.fleyx.jcloud.model.dto.ChunkedUploadInitDto;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.BatchChunkedUploadInitItemVo;
import com.fleyx.jcloud.model.vo.ChunkedUploadChunkVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.service.ChunkedUploadService;
import com.fleyx.jcloud.service.support.ChunkedUploadChunkSupport;
import com.fleyx.jcloud.service.support.ChunkedUploadCompleteSupport;
import com.fleyx.jcloud.service.support.ChunkedUploadInitSupport;
import com.fleyx.jcloud.service.support.UserSpaceSupport;
import com.fleyx.jcloud.util.UserReadOnlyChecker;
import com.fleyx.jcloud.util.UserReadWriteLock;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 分片上传服务实现。
 * <p>
 * 仅负责入口编排（只读校验、写锁），具体逻辑委托给分片上传各支撑组件。
 */
@Service
@RequiredArgsConstructor
public class ChunkedUploadServiceImpl implements ChunkedUploadService {

    private final UserReadOnlyChecker userReadOnlyChecker;
    private final UserReadWriteLock userReadWriteLock;
    private final UserSpaceSupport userSpaceSupport;
    private final ChunkedUploadInitSupport chunkedUploadInitSupport;
    private final ChunkedUploadChunkSupport chunkedUploadChunkSupport;
    private final ChunkedUploadCompleteSupport chunkedUploadCompleteSupport;

    @Override
    public List<BatchChunkedUploadInitItemVo> init(String userId, List<ChunkedUploadInitDto> items) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        User user = userSpaceSupport.requireUser(userId);
        StorageSpace space = userSpaceSupport.requireSpace(user.getStorageSpaceId());

        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return chunkedUploadInitSupport.doBatchInit(items, userId, user, space);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ChunkedUploadChunkVo uploadChunk(String userId, String uploadId, Integer chunkIndex,
                                            MultipartFile chunk, String chunkHash) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        return chunkedUploadChunkSupport.uploadChunk(userId, uploadId, chunkIndex, chunk, chunkHash);
    }

    @Override
    public List<Integer> listUploadedChunks(String userId, String uploadId) {
        return chunkedUploadChunkSupport.listUploadedChunks(userId, uploadId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileNodeVo complete(String userId, String uploadId, ChunkedUploadCompleteDto dto) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return chunkedUploadCompleteSupport.doComplete(userId, uploadId, dto == null ? null : dto.getStrategy());
        } finally {
            lock.unlock();
        }
    }
}
