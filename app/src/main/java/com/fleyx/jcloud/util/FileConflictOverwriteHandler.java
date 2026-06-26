package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
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
    private final UserMapper userMapper;

    /**
     * 删除被覆盖的旧文件节点及物理数据，并扣减用户已用配额。
     *
     * @param existing 待覆盖的旧文件节点
     * @param user     当前用户
     */
    public void deleteExistingForOverwrite(FileNode existing, User user) {
        StorageSpace space = storageSpaceMapper.selectById(existing.getStorageSpaceId());
        Path physicalPath = FilePathUtil.resolvePhysicalPath(existing, space);
        fileMapper.deleteById(existing);
        long usedSpace = user.getUsedSpace() == null ? 0L : user.getUsedSpace();
        user.setUsedSpace(Math.max(0L, usedSpace - existing.getSize()));
        userMapper.updateById(user);
        try {
            Files.deleteIfExists(physicalPath);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "删除旧文件失败", e);
        }
    }
}
