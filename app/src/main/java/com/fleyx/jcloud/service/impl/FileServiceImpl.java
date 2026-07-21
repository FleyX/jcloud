package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.dto.FileInstantUploadDto;
import com.fleyx.jcloud.model.dto.FilePageQueryDto;
import com.fleyx.jcloud.model.dto.FileUploadPreCheckDto;
import com.fleyx.jcloud.model.vo.BatchUploadPreCheckItemVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.service.FileService;
import com.fleyx.jcloud.service.support.FileQuerySupport;
import com.fleyx.jcloud.service.support.FileUploadPrecheckSupport;
import com.fleyx.jcloud.service.support.FileUploadSupport;
import com.fleyx.jcloud.util.UserReadOnlyChecker;
import com.fleyx.jcloud.util.UserReadWriteLock;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件业务实现。
 * <p>
 * 仅负责入口编排（只读校验、写锁），具体逻辑委托给文件各支撑组件，
 * 事务边界由支撑组件的 {@code @Transactional} 方法承载。
 */
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final UserReadWriteLock userReadWriteLock;
    private final UserReadOnlyChecker userReadOnlyChecker;
    private final FileUploadSupport fileUploadSupport;
    private final FileUploadPrecheckSupport fileUploadPrecheckSupport;
    private final FileQuerySupport fileQuerySupport;

    @Override
    public FileNodeVo upload(MultipartFile file, String userId, String parentId, String strategy) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return fileUploadSupport.doUpload(file, userId, parentId, strategy);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<BatchUploadPreCheckItemVo> preCheckUpload(List<FileUploadPreCheckDto> items, String userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return fileUploadPrecheckSupport.doBatchPreCheckUpload(items, userId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public IPage<FileNodeVo> list(FilePageQueryDto dto, String userId) {
        return fileQuerySupport.list(dto, userId);
    }

    @Override
    public FileDownloadResult download(String fileId, String userId) {
        return fileQuerySupport.download(fileId, userId);
    }

    @Override
    public List<FileNodeVo> listChildFolders(String parentId, String userId) {
        return fileQuerySupport.listChildFolders(parentId, userId);
    }

    @Override
    public FileNodeVo instantUpload(FileInstantUploadDto dto, String userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return fileUploadSupport.doInstantUpload(dto, userId);
        } finally {
            lock.unlock();
        }
    }
}
