package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.enums.FileChangeOperation;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.event.FileTreeChangedEvent;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import com.fleyx.jcloud.util.FilePathUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 回收站彻底删除支撑组件。
 */
@Component
@RequiredArgsConstructor
public class TrashPermanentDeleteSupport {

    private final RecycleRecordMapper recycleRecordMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final UserSpaceSupport userSpaceSupport;
    private final UserUsedSpaceSupport userUsedSpaceSupport;
    private final FileChangeEventSupport fileChangeEventSupport;

    /**
     * 批量彻底删除回收站记录，整个列表在一个事务内执行。
     *
     * @param ids    回收站记录 ID 列表
     * @param userId 用户 ID
     * @return 操作结果列表
     */
    @Transactional(rollbackFor = Exception.class)
    public List<OperationResultVo> doPermanentDelete(List<String> ids, String userId) {
        List<OperationResultVo> results = new ArrayList<>();
        for (String id : ids) {
            results.add(permanentDeleteOne(id, userId));
        }
        return results;
    }

    private OperationResultVo permanentDeleteOne(String recordId, String userId) {
        RecycleRecord record = recycleRecordMapper.selectById(recordId);
        if (record == null || !record.getUserId().equals(userId)) {
            return failedResult(recordId, null, "记录不存在或无权限");
        }
        User user = userSpaceSupport.requireUser(userId);
        StorageSpace space = storageSpaceMapper.selectById(user.getStorageSpaceId());
        if (space == null) {
            return failedResult(recordId, record.getName(), "用户未绑定存储空间");
        }
        Path trashBase = FilePathUtil.resolveTrashRoot(space, user.getUsername(), record.getId());
        try {
            if (Files.exists(trashBase)) {
                deleteRecursively(trashBase);
            }
            long freed = record.getTotalSize() == null ? 0L : record.getTotalSize();
            userUsedSpaceSupport.addUsedSpace(userId, -freed);
            recycleRecordMapper.physicalDeleteById(record.getId());
            // 彻底删除只有回收站记录可用：nodeId 用记录 ID，parentId/path 为空
            fileChangeEventSupport.publishAfterCommit(new FileTreeChangedEvent(this,
                    FileChangeOperation.PERMANENT_DELETE, userId, record.getId(), record.getType(),
                    record.getName(), record.getTotalSize(), null, null, null, null));
            return successResult(record.getId(), record.getName(), "已永久删除");
        } catch (IOException e) {
            return failedResult(record.getId(), record.getName(), "物理文件删除失败: " + e.getMessage());
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new BusinessException(ResultCode.BUSINESS_ERROR,
                                    "删除物理文件失败: " + p);
                        }
                    });
        }
    }

    private OperationResultVo successResult(String sourceId, String sourceName, String message) {
        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(sourceId);
        vo.setSourceName(sourceName);
        vo.setStatus(FileNodeConstants.STATUS_SUCCESS);
        vo.setMessage(message);
        return vo;
    }

    private OperationResultVo failedResult(String sourceId, String sourceName, String message) {
        OperationResultVo vo = new OperationResultVo();
        vo.setSourceId(sourceId);
        vo.setSourceName(sourceName);
        vo.setStatus(FileNodeConstants.STATUS_FAILED);
        vo.setMessage(message);
        return vo;
    }
}
