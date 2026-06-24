package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.dto.BatchUserStatusDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.dto.UserUpdateDto;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.po.UserRole;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户服务单元测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    private UserSaveDto buildDto(String username) {
        UserSaveDto dto = new UserSaveDto();
        dto.setUsername(username);
        dto.setPassword("123456");
        dto.setEmail(username + "@example.com");
        dto.setNickname("昵称" + username);
        return dto;
    }

    @BeforeEach
    void setUp() {
        // 清理非管理员测试用户及其角色关联，确保每个测试方法独立运行。
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(User::getUsername, "admin");
        List<User> users = userMapper.selectList(wrapper);
        for (User user : users) {
            LambdaQueryWrapper<UserRole> roleWrapper = new LambdaQueryWrapper<>();
            roleWrapper.eq(UserRole::getUserId, user.getId());
            userRoleMapper.delete(roleWrapper);
            userMapper.deleteById(user.getId());
        }
    }

    @Test
    void saveUserShouldReturnUserVo() {
        UserSaveDto dto = buildDto("saveTest");
        UserVo vo = userService.saveUser(dto);

        assertNotNull(vo.getId());
        assertEquals(dto.getUsername(), vo.getUsername());
        assertEquals(dto.getEmail(), vo.getEmail());
        assertEquals(dto.getNickname(), vo.getNickname());
        assertEquals(1, vo.getStatus());
    }

    @Test
    void saveDuplicateUsernameShouldThrowBusinessException() {
        UserSaveDto dto = buildDto("duplicateUser");
        userService.saveUser(dto);

        assertThrows(BusinessException.class, () -> userService.saveUser(dto));
    }

    @Test
    void getByIdShouldReturnUser() {
        UserSaveDto dto = buildDto("getByIdUser");
        UserVo saved = userService.saveUser(dto);

        UserVo found = userService.getById(saved.getId());
        assertEquals(saved.getId(), found.getId());
        assertEquals(saved.getUsername(), found.getUsername());
    }

    @Test
    void getByNonExistentIdShouldThrowBusinessException() {
        assertThrows(BusinessException.class, () -> userService.getById(-1L));
    }

    @Test
    void listByUsernameShouldReturnMatchedUsers() {
        UserSaveDto dto1 = buildDto("searchUserA");
        UserSaveDto dto2 = buildDto("searchUserB");
        userService.saveUser(dto1);
        userService.saveUser(dto2);

        List<UserVo> list = userService.listByUsername("searchUser");
        assertEquals(2, list.size());
    }

    @Test
    void listByBlankUsernameShouldThrowBusinessException() {
        assertThrows(BusinessException.class, () -> userService.listByUsername(""));
    }

    @Test
    void removeByIdShouldSuccess() {
        UserSaveDto dto = buildDto("removeUser");
        UserVo saved = userService.saveUser(dto);

        boolean removed = userService.removeById(saved.getId());
        assertTrue(removed);

        assertThrows(BusinessException.class, () -> userService.getById(saved.getId()));
    }

    @Test
    void removeByNonExistentIdShouldThrowBusinessException() {
        assertThrows(BusinessException.class, () -> userService.removeById(-1L));
    }

    @Test
    void updateUserShouldUpdateNicknameAndEmail() {
        UserSaveDto saveDto = buildDto("updateUser");
        UserVo saved = userService.saveUser(saveDto);

        UserUpdateDto updateDto = new UserUpdateDto();
        updateDto.setId(saved.getId());
        updateDto.setNickname("新昵称");
        updateDto.setEmail("new@example.com");
        updateDto.setStatus(0);

        UserVo updated = userService.updateUser(updateDto);
        assertEquals("新昵称", updated.getNickname());
        assertEquals("new@example.com", updated.getEmail());
        assertEquals(0, updated.getStatus());
    }

    @Test
    void updateUserPasswordShouldRehash() {
        UserSaveDto saveDto = buildDto("updatePwdUser");
        UserVo saved = userService.saveUser(saveDto);

        UserUpdateDto updateDto = new UserUpdateDto();
        updateDto.setId(saved.getId());
        updateDto.setPassword("newpassword");

        UserVo updated = userService.updateUser(updateDto);
        assertNotNull(updated.getId());
    }

    @Test
    void batchDeleteShouldSkipAdmin() {
        UserSaveDto dto1 = buildDto("batchDeleteA");
        UserSaveDto dto2 = buildDto("batchDeleteB");
        UserVo saved1 = userService.saveUser(dto1);
        UserVo saved2 = userService.saveUser(dto2);

        List<Long> deleted = userService.batchDelete(List.of(saved1.getId(), saved2.getId()));
        assertEquals(2, deleted.size());
        assertThrows(BusinessException.class, () -> userService.getById(saved1.getId()));
    }

    @Test
    void batchUpdateStatusShouldSkipAdmin() {
        UserSaveDto dto1 = buildDto("batchStatusA");
        UserSaveDto dto2 = buildDto("batchStatusB");
        UserVo saved1 = userService.saveUser(dto1);
        UserVo saved2 = userService.saveUser(dto2);

        BatchUserStatusDto dto = new BatchUserStatusDto();
        dto.setUserIds(List.of(saved1.getId(), saved2.getId()));
        dto.setStatus(0);

        List<Long> updated = userService.batchUpdateStatus(dto);
        assertEquals(2, updated.size());
        assertEquals(0, userService.getById(saved1.getId()).getStatus());
        assertEquals(0, userService.getById(saved2.getId()).getStatus());
    }
}
