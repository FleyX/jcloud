package com.fleyx.jcloud.service.support;

import cn.hutool.crypto.digest.BCrypt;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.convert.UserConvert;
import com.fleyx.jcloud.model.dto.ChangePasswordDto;
import com.fleyx.jcloud.model.dto.UserProfileUpdateDto;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.UserProfileVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 个人中心支撑组件。
 * <p>
 * 承载用户资料查询/修改、密码修改与 WebDAV 开关。
 */
@Component
@RequiredArgsConstructor
public class UserProfileSupport {

    private final UserMapper userMapper;
    private final UserConvert userConvert;
    private final UserSpaceSupport userSpaceSupport;

    /**
     * 查询用户资料。
     *
     * @param userId 用户 ID
     * @return 用户资料视图
     */
    public UserProfileVo getUserProfile(String userId) {
        User user = userSpaceSupport.requireUser(userId);
        return userConvert.poToProfileVo(user);
    }

    /**
     * 更新用户资料（邮箱、昵称）。
     *
     * @param userId 用户 ID
     * @param dto    资料
     * @return 更新后的用户资料视图
     */
    public UserProfileVo updateUserProfile(String userId, UserProfileUpdateDto dto) {
        User user = userSpaceSupport.requireUser(userId);
        User update = new User();
        update.setId(user.getId());
        if (dto.getEmail() != null) {
            update.setEmail(dto.getEmail());
        }
        if (dto.getNickname() != null) {
            update.setNickname(dto.getNickname());
        }
        userMapper.updateById(update);
        User updated = userMapper.selectById(user.getId());
        return userConvert.poToProfileVo(updated);
    }

    /**
     * 修改密码。
     *
     * @param userId 用户 ID
     * @param dto    当前密码与新密码
     */
    public void changePassword(String userId, ChangePasswordDto dto) {
        if (!dto.getNewPassword().equals(dto.getConfirmPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "两次输入的新密码不一致");
        }
        User user = userSpaceSupport.requireUser(userId);
        if (!BCrypt.checkpw(dto.getCurrentPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前密码错误");
        }
        User update = new User();
        update.setId(user.getId());
        update.setPassword(BCrypt.hashpw(dto.getNewPassword(), BCrypt.gensalt()));
        userMapper.updateById(update);
    }

    /**
     * 开关 WebDAV 访问。
     *
     * @param userId  用户 ID
     * @param enabled 是否开启
     * @return 更新后的用户资料视图
     */
    @Transactional(rollbackFor = Exception.class)
    public UserProfileVo toggleWebDav(String userId, boolean enabled) {
        User user = userSpaceSupport.requireUser(userId);
        User update = new User();
        update.setId(user.getId());
        update.setWebdavEnabled(enabled);
        userMapper.updateById(update);
        User updated = userMapper.selectById(user.getId());
        return userConvert.poToProfileVo(updated);
    }
}
