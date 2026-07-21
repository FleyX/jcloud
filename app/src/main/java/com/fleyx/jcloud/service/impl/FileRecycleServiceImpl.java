package com.fleyx.jcloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.enums.ConflictStrategy;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.model.convert.RecycleRecordConvert;
import com.fleyx.jcloud.model.dto.FileDeleteDto;
import com.fleyx.jcloud.model.dto.FileExecuteRestoreDto;
import com.fleyx.jcloud.model.dto.FilePermanentDeleteDto;
import com.fleyx.jcloud.model.dto.FilePreCheckRestoreDto;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.vo.ConflictItemVo;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import com.fleyx.jcloud.model.vo.RecycleRecordVo;
import com.fleyx.jcloud.service.FileRecycleService;
import com.fleyx.jcloud.service.support.TrashDeleteSupport;
import com.fleyx.jcloud.service.support.TrashPermanentDeleteSupport;
import com.fleyx.jcloud.service.support.TrashRestorePrecheckSupport;
import com.fleyx.jcloud.service.support.TrashRestoreSupport;
import com.fleyx.jcloud.util.UserReadOnlyChecker;
import com.fleyx.jcloud.util.UserReadWriteLock;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 文件回收站服务实现。
 * <p>
 * 仅负责入口编排（参数校验、只读校验、写锁），具体逻辑委托给回收站各支撑组件，
 * 事务边界由支撑组件的 {@code @Transactional} 方法承载。
 */
@Service
@RequiredArgsConstructor
public class FileRecycleServiceImpl implements FileRecycleService {

    private final RecycleRecordMapper recycleRecordMapper;
    private final RecycleRecordConvert recycleRecordConvert;
    private final UserReadWriteLock userReadWriteLock;
    private final UserReadOnlyChecker userReadOnlyChecker;
    private final TrashDeleteSupport trashDeleteSupport;
    private final TrashRestorePrecheckSupport trashRestorePrecheckSupport;
    private final TrashRestoreSupport trashRestoreSupport;
    private final TrashPermanentDeleteSupport trashPermanentDeleteSupport;

    @Override
    public List<OperationResultVo> deleteToTrash(FileDeleteDto dto, String userId) {
        if (dto == null || CollectionUtils.isEmpty(dto.getIds())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "删除节点 ID 不能为空");
        }
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return trashDeleteSupport.doDeleteToTrash(dto.getIds(), userId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public IPage<RecycleRecordVo> listTrash(Long pageNum, Long pageSize, String userId) {
        Page<RecycleRecord> pageParam = new Page<>(pageNum == null || pageNum < 1 ? 1 : pageNum,
                pageSize == null || pageSize < 1 ? 20 : pageSize);
        LambdaQueryWrapper<RecycleRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RecycleRecord::getUserId, userId);
        wrapper.orderByDesc(RecycleRecord::getCreateTime);
        IPage<RecycleRecord> page = recycleRecordMapper.selectPage(pageParam, wrapper);
        return page.convert(recycleRecordConvert::poToVo);
    }

    @Override
    public List<ConflictItemVo> preCheckRestore(FilePreCheckRestoreDto dto, String userId) {
        if (dto == null || CollectionUtils.isEmpty(dto.getIds())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "恢复记录 ID 不能为空");
        }
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return trashRestorePrecheckSupport.doPreCheckRestore(dto, userId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<OperationResultVo> restore(FileExecuteRestoreDto dto, String userId) {
        if (dto == null || CollectionUtils.isEmpty(dto.getItems())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "恢复项不能为空");
        }
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return trashRestoreSupport.doRestore(dto.getItems(), userId,
                    ConflictStrategy.fromCode(dto.getGlobalStrategy()));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<OperationResultVo> permanentDelete(FilePermanentDeleteDto dto, String userId) {
        if (dto == null || CollectionUtils.isEmpty(dto.getIds())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "删除记录 ID 不能为空");
        }
        userReadOnlyChecker.checkWriteAllowed(userId);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            return trashPermanentDeleteSupport.doPermanentDelete(dto.getIds(), userId);
        } finally {
            lock.unlock();
        }
    }
}
