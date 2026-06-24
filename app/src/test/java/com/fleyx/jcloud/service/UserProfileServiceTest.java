package com.fleyx.jcloud.service;

import cn.hutool.crypto.digest.BCrypt;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.ChangePasswordDto;
import com.fleyx.jcloud.model.dto.UserProfileUpdateDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.UserProfileVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 当前用户个人信息与密码修改服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserProfileServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    private UserVo createUser(String username) {
        UserSaveDto dto = new UserSaveDto();
        dto.setUsername(username);
        dto.setPassword("123456");
        dto.setEmail(username + "@example.com");
        dto.setNickname("昵称" + username);
        return userService.saveUser(dto);
    }

    @Test
    void getUserProfileShouldReturnProfile() {
        UserVo saved = createUser("profileUser");
        UserProfileVo profile = userService.getUserProfile(saved.getId());

        assertNotNull(profile);
        assertEquals(String.valueOf(saved.getId()), profile.getId());
        assertEquals(saved.getUsername(), profile.getUsername());
        assertEquals(saved.getEmail(), profile.getEmail());
        assertEquals(saved.getNickname(), profile.getNickname());
    }

    @Test
    void updateUserProfileShouldUpdateEmailAndNickname() {
        UserVo saved = createUser("updateProfileUser");
        UserProfileUpdateDto dto = new UserProfileUpdateDto();
        dto.setEmail("new@example.com");
        dto.setNickname("新昵称");

        UserProfileVo updated = userService.updateUserProfile(saved.getId(), dto);

        assertEquals("new@example.com", updated.getEmail());
        assertEquals("新昵称", updated.getNickname());
    }

    @Test
    void changePasswordShouldSucceedWithCorrectCurrentPassword() {
        UserVo saved = createUser("changePwdUser");
        ChangePasswordDto dto = new ChangePasswordDto();
        dto.setCurrentPassword("123456");
        dto.setNewPassword("newpass");
        dto.setConfirmPassword("newpass");

        userService.changePassword(saved.getId(), dto);

        User updated = userMapper.selectById(saved.getId());
        assertTrue(BCrypt.checkpw("newpass", updated.getPassword()));
    }

    @Test
    void changePasswordShouldFailWithWrongCurrentPassword() {
        UserVo saved = createUser("wrongPwdUser");
        ChangePasswordDto dto = new ChangePasswordDto();
        dto.setCurrentPassword("wrong");
        dto.setNewPassword("newpass");
        dto.setConfirmPassword("newpass");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(saved.getId(), dto));
        assertEquals("当前密码错误", ex.getMessage());
    }

    @Test
    void changePasswordShouldFailWhenConfirmPasswordMismatch() {
        UserVo saved = createUser("mismatchPwdUser");
        ChangePasswordDto dto = new ChangePasswordDto();
        dto.setCurrentPassword("123456");
        dto.setNewPassword("newpass");
        dto.setConfirmPassword("different");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(saved.getId(), dto));
        assertEquals("两次输入的新密码不一致", ex.getMessage());
    }
}
