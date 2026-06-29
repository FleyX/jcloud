package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.User;
import org.springframework.stereotype.Component;

/**
 * 用户只读状态校验器。
 * <p>
 * 用户存储空间迁移期间会被标记为只读，此时应禁止上传、删除、移动、重命名等写操作。
 */
@Component
public class UserReadOnlyChecker {

    private final UserMapper userMapper;

    public UserReadOnlyChecker(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 校验指定用户当前是否允许执行写操作。
     *
     * @param userId 用户 ID
     * @throws BusinessException 用户处于只读状态时抛出
     */
    public void checkWriteAllowed(String userId) {
        User user = userMapper.selectById(userId);
        if (user != null && Integer.valueOf(1).equals(user.getReadOnly())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "用户存储空间迁移中，暂时禁止写操作");
        }
    }
}
