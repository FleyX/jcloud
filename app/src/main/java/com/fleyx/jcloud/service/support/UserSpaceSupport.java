package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 用户与存储空间共享支撑组件。
 * <p>
 * 收敛各文件服务中重复出现的用户、存储空间存在性校验逻辑。
 */
@Component
@RequiredArgsConstructor
public class UserSpaceSupport {

    private final UserMapper userMapper;
    private final StorageSpaceMapper storageSpaceMapper;

    /**
     * 查询用户，不存在抛出业务异常。
     *
     * @param userId 用户 ID
     * @return 用户
     */
    public User requireUser(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    /**
     * 按存储空间 ID 查询存储空间，不存在抛出业务异常。
     *
     * @param spaceId 存储空间 ID
     * @return 存储空间
     */
    public StorageSpace requireSpace(String spaceId) {
        if (spaceId == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户未绑定存储空间");
        }
        StorageSpace space = storageSpaceMapper.selectById(spaceId);
        if (space == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        return space;
    }

    /**
     * 查询用户绑定的存储空间，不存在抛出业务异常。
     *
     * @param user 用户
     * @return 存储空间
     */
    public StorageSpace requireSpace(User user) {
        StorageSpace space = storageSpaceMapper.selectById(user.getStorageSpaceId());
        if (space == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
        }
        return space;
    }

    /**
     * 按增量更新用户与存储空间的已用容量（结果不小于 0）。
     *
     * @param user  用户
     * @param space 存储空间
     * @param delta 容量增量（可为负）
     */
    public void updateUsedSpace(User user, StorageSpace space, long delta) {
        long newUserUsed = Math.max(0L, user.getUsedSpace() + delta);
        user.setUsedSpace(newUserUsed);
        userMapper.updateById(user);
        long newSpaceUsed = Math.max(0L, space.getUsedSpace() + delta);
        space.setUsedSpace(newSpaceUsed);
        storageSpaceMapper.updateById(space);
    }
}
