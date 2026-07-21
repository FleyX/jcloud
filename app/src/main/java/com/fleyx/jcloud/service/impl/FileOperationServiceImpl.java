package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.dto.FilePreCheckOperationDto;
import com.fleyx.jcloud.model.dto.FileRenameDto;
import com.fleyx.jcloud.model.vo.ConflictItemVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import com.fleyx.jcloud.service.FileOperationService;
import com.fleyx.jcloud.service.support.FileMoveCopySupport;
import com.fleyx.jcloud.service.support.FileNodeEditSupport;
import com.fleyx.jcloud.service.support.FileOperationPrecheckSupport;
import com.fleyx.jcloud.util.UserReadOnlyChecker;
import com.fleyx.jcloud.util.UserReadWriteLock;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文件组织操作服务实现。
 * <p>
 * 仅负责入口编排（只读校验、写锁），具体逻辑委托给文件操作各支撑组件，
 * 事务边界由支撑组件的 {@code @Transactional} 方法承载。
 */
@Service
@RequiredArgsConstructor
public class FileOperationServiceImpl implements FileOperationService {

    private final UserReadWriteLock userReadWriteLock;
    private final UserReadOnlyChecker userReadOnlyChecker;
    private final FileNodeEditSupport fileNodeEditSupport;
    private final FileMoveCopySupport fileMoveCopySupport;
    private final FileOperationPrecheckSupport fileOperationPrecheckSupport;

    @Override
    public FileNodeVo rename(FileRenameDto dto, String userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return fileNodeEditSupport.doRename(dto, userId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public FileNodeVo createFolder(FileCreateFolderDto dto, String userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return fileNodeEditSupport.doCreateFolder(dto, userId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<ConflictItemVo> preCheckOperation(FilePreCheckOperationDto dto, String userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return fileOperationPrecheckSupport.doPreCheck(dto, userId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<OperationResultVo> move(FileExecuteOperationDto dto, String userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return fileMoveCopySupport.doMove(dto, userId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<OperationResultVo> copy(FileExecuteOperationDto dto, String userId) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return fileMoveCopySupport.doCopy(dto, userId);
        } finally {
            lock.unlock();
        }
    }
}
