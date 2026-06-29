package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.dto.BatchUserStatusDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserPageQueryDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.dto.UserStatusDto;
import com.fleyx.jcloud.model.dto.UserStorageDto;
import com.fleyx.jcloud.model.dto.UserUpdateDto;
import com.fleyx.jcloud.model.dto.UserUpdateRolesDto;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.po.UserRole;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
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

    @TempDir
    Path tempDir;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private StorageSpaceService storageSpaceService;

    private StorageSpaceVo defaultSpace;

    private UserSaveDto buildDto(String username) {
        UserSaveDto dto = new UserSaveDto();
        dto.setUsername(username.toLowerCase());
        dto.setPassword("123456");
        dto.setEmail(username + "@example.com");
        dto.setNickname("昵称" + username);
        dto.setStorageSpaceId(defaultSpace.getId());
        dto.setQuota(10L);
        dto.setQuotaUnit("GB");
        return dto;
    }

    @BeforeEach
    void setUp() {
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("默认测试空间");
        spaceDto.setPath(tempDir.resolve("user-space").toString());
        defaultSpace = storageSpaceService.save(spaceDto);

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
        assertEquals(defaultSpace.getId(), vo.getStorageSpaceId());
        assertEquals(10L * 1024 * 1024 * 1024, vo.getQuota());
        assertEquals(1, vo.getStatus());
    }

    @Test
    void saveUserWithZeroQuotaShouldBeUnlimited() {
        UserSaveDto dto = buildDto("zeroQuotaUser");
        dto.setQuota(0L);
        dto.setQuotaUnit("GB");

        UserVo vo = userService.saveUser(dto);

        assertEquals(0L, vo.getQuota());
    }

    @Test
    void pageUsersShouldFillStorageSpaceName() {
        userService.saveUser(buildDto("pageUser"));

        UserPageQueryDto query = new UserPageQueryDto();
        query.setUsername("pageuser");
        query.setPageNum(1L);
        query.setPageSize(10L);

        IPage<UserVo> page = userService.pageUsers(query);

        assertEquals(1L, page.getTotal());
        UserVo vo = page.getRecords().get(0);
        assertEquals(defaultSpace.getName(), vo.getStorageSpaceName());
    }

    @Test
    void saveUserWithoutStorageSpaceShouldThrow() {
        UserSaveDto dto = buildDto("noSpaceUser");
        dto.setStorageSpaceId(null);

        assertThrows(BusinessException.class, () -> userService.saveUser(dto));
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
        assertThrows(BusinessException.class, () -> userService.getById("-1"));
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
        assertThrows(BusinessException.class, () -> userService.removeById("-1"));
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
        updateDto.setQuota(5L);
        updateDto.setQuotaUnit("GB");

        UserVo updated = userService.updateUser(updateDto);
        assertEquals("新昵称", updated.getNickname());
        assertEquals("new@example.com", updated.getEmail());
        assertEquals(0, updated.getStatus());
        assertEquals(5L * 1024 * 1024 * 1024, updated.getQuota());
    }

    @Test
    void updateUserShouldNotChangeStorageSpace() {
        UserSaveDto saveDto = buildDto("noSpaceChangeUser");
        UserVo saved = userService.saveUser(saveDto);

        UserUpdateDto updateDto = new UserUpdateDto();
        updateDto.setId(saved.getId());
        updateDto.setQuota(1L);
        updateDto.setQuotaUnit("TB");

        UserVo updated = userService.updateUser(updateDto);
        assertEquals(defaultSpace.getId(), updated.getStorageSpaceId());
        assertEquals(1L * 1024 * 1024 * 1024 * 1024, updated.getQuota());
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
    void updateBuiltInAdminShouldAllowCommonFields() {
        User admin = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, "admin"));
        assertNotNull(admin);

        UserUpdateDto updateDto = new UserUpdateDto();
        updateDto.setId(admin.getId());
        updateDto.setNickname("新昵称");
        updateDto.setEmail("new@example.com");
        updateDto.setPassword("newpassword");
        updateDto.setQuota(20L);
        updateDto.setQuotaUnit("GB");

        UserVo updated = userService.updateUser(updateDto);
        assertEquals("新昵称", updated.getNickname());
        assertEquals("new@example.com", updated.getEmail());
        assertEquals(20L * 1024 * 1024 * 1024, updated.getQuota());
    }

    @Test
    void updateBuiltInAdminShouldIgnoreStatusAndRoles() {
        User admin = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, "admin"));
        assertNotNull(admin);
        int originalStatus = admin.getStatus();
        List<String> originalRoleIds = userRoleMapper.selectRoleIdsByUserId(admin.getId());

        UserUpdateDto updateDto = new UserUpdateDto();
        updateDto.setId(admin.getId());
        updateDto.setStatus(originalStatus == 0 ? 1 : 0);
        updateDto.setRoleIds(List.of());

        userService.updateUser(updateDto);

        User refreshed = userMapper.selectById(admin.getId());
        assertEquals(originalStatus, refreshed.getStatus());
        List<String> currentRoleIds = userRoleMapper.selectRoleIdsByUserId(admin.getId());
        assertEquals(originalRoleIds, currentRoleIds);
    }

    @Test
    void updateBuiltInAdminStatusShouldThrowBusinessException() {
        User admin = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, "admin"));
        assertNotNull(admin);

        UserStatusDto dto = new UserStatusDto();
        dto.setUserId(admin.getId());
        dto.setStatus(0);

        assertThrows(BusinessException.class, () -> userService.updateStatus(dto));
    }

    @Test
    void updateBuiltInAdminRolesShouldThrowBusinessException() {
        User admin = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, "admin"));
        assertNotNull(admin);

        UserUpdateRolesDto dto = new UserUpdateRolesDto();
        dto.setUserId(admin.getId());
        dto.setRoleIds(List.of());

        assertThrows(BusinessException.class, () -> userService.updateRoles(dto));
    }

    @Test
    void updateUserRolesShouldBatchInsertWithGeneratedId() {
        UserSaveDto saveDto = buildDto("roleAssignUser");
        UserVo saved = userService.saveUser(saveDto);

        UserUpdateRolesDto dto = new UserUpdateRolesDto();
        dto.setUserId(saved.getId());
        dto.setRoleIds(List.of("0000000000002"));

        userService.updateRoles(dto);

        List<String> roleIds = userRoleMapper.selectRoleIdsByUserId(saved.getId());
        assertEquals(1, roleIds.size());
        assertEquals("0000000000002", roleIds.get(0));
    }

    @Test
    void batchDeleteShouldSkipAdmin() {
        UserSaveDto dto1 = buildDto("batchDeleteA");
        UserSaveDto dto2 = buildDto("batchDeleteB");
        UserVo saved1 = userService.saveUser(dto1);
        UserVo saved2 = userService.saveUser(dto2);

        List<String> deleted = userService.batchDelete(List.of(saved1.getId(), saved2.getId()));
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

        List<String> updated = userService.batchUpdateStatus(dto);
        assertEquals(2, updated.size());
        assertEquals(0, userService.getById(saved1.getId()).getStatus());
        assertEquals(0, userService.getById(saved2.getId()).getStatus());
    }

    @Test
    void shouldNotReuseUsernameWithinRetentionPeriodAfterDeletion() {
        UserSaveDto dto = buildDto("reuseusername");
        UserVo saved = userService.saveUser(dto);
        userService.removeById(saved.getId());

        assertThrows(BusinessException.class, () -> userService.saveUser(dto));
    }

    @Test
    void shouldBindStorageSpaceAndQuotaToUser() {
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("用户空间");
        spaceDto.setPath(tempDir.resolve("user-binding").toString());
        StorageSpaceVo space = storageSpaceService.save(spaceDto);

        UserSaveDto userDto = buildDto("bindStorageUser");
        UserVo user = userService.saveUser(userDto);

        UserStorageDto bindDto = new UserStorageDto();
        bindDto.setUserId(user.getId());
        bindDto.setStorageSpaceId(space.getId());
        bindDto.setQuota(10737418240L);

        userService.bindStorageSpace(bindDto);

        User updated = userMapper.selectById(user.getId());
        assertEquals(space.getId(), updated.getStorageSpaceId());
        assertEquals(10737418240L, updated.getQuota());
    }
}
