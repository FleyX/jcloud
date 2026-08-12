package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.service.support.UserUsedSpaceSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件冲突覆盖统一副作用处理器。
 * <p>
 * 负责覆盖场景下的物理文件删除、节点删除与配额扣减，确保上传、移动、复制、恢复行为一致。
 */
@Component
@RequiredArgsConstructor
public class FileConflictOverwriteHandler {

    private final FileMapper fileMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final UserUsedSpaceSupport userUsedSpaceSupport;

    /**
     * 删除被覆盖的旧文件节点及物理数据，并扣减用户已用配额。
     *
     * @param existing 待覆盖的旧文件节点
     * @param user     当前用户
     */
    public void deleteExistingForOverwrite(FileNode existing, User user) {
        StorageSpace space = storageSpaceMapper.selectById(existing.getStorageSpaceId());
        FilePathUtil.ResolveContext ctx = FilePathUtil.contextOf(space, user.getUsername());
        Path physicalPath = FilePathUtil.resolvePhysicalPath(existing, ctx);
        fileMapper.deleteById(existing);
        userUsedSpaceSupport.addUsedSpace(user.getId(), -(existing.getSize() == null ? 0L : existing.getSize()));
        try {
            Files.deleteIfExists(physicalPath);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "删除旧文件失败", e);
        }
    }
}
