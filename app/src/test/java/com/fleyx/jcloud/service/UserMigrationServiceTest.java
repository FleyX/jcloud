package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserMigrationSubmitDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserMigrationTaskVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户存储空间迁移服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserMigrationServiceTest {

    @Autowired
    private UserMigrationService userMigrationService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    @TempDir
    Path tempDir;

    @Test
    void shouldSubmitMigrationSuccessfully() throws Exception {
        UserWithSpaces prepared = prepareUserWithSpaces();

        UserMigrationSubmitDto dto = new UserMigrationSubmitDto();
        dto.setUserId(prepared.user().getId());
        dto.setTargetSpaceId(prepared.targetSpace().getId());
        dto.setNewQuota(10737418240L);

        UserMigrationTaskVo task = userMigrationService.submitMigration(dto);

        assertNotNull(task.getId());
        assertEquals("PENDING", task.getStatus());
        assertEquals(prepared.sourceSpace().getId(), task.getSourceSpaceId());
        assertEquals(prepared.targetSpace().getId(), task.getTargetSpaceId());

        User user = userMapper.selectById(prepared.user().getId());
        assertEquals(Integer.valueOf(1), user.getReadOnly());
    }

    @Test
    void shouldRejectMigrationWhenUserNotBoundToSpace() throws Exception {
        UserWithSpaces prepared = prepareUserWithSpaces();
        UserVo user = prepared.user();
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(User::getStorageSpaceId, null);
        wrapper.eq(User::getId, user.getId());
        userMapper.update(wrapper);

        StorageSpaceSaveDto spaceDto = buildSpaceDto("target-no-binding", Files.createTempDirectory(tempDir, "target-no-binding"));
        StorageSpaceVo space = storageSpaceService.save(spaceDto);

        UserMigrationSubmitDto dto = new UserMigrationSubmitDto();
        dto.setUserId(user.getId());
        dto.setTargetSpaceId(space.getId());
        dto.setNewQuota(10737418240L);

        assertThrows(BusinessException.class, () -> userMigrationService.submitMigration(dto));
    }

    @Test
    void shouldRejectMigrationWhenTargetSpaceSameAsSource() throws Exception {
        UserWithSpaces prepared = prepareUserWithSpaces();

        UserMigrationSubmitDto dto = new UserMigrationSubmitDto();
        dto.setUserId(prepared.user().getId());
        dto.setTargetSpaceId(prepared.sourceSpace().getId());
        dto.setNewQuota(10737418240L);

        assertThrows(BusinessException.class, () -> userMigrationService.submitMigration(dto));
    }

    @Test
    void shouldRejectMigrationWhenTargetSpaceInsufficient() throws Exception {
        UserWithSpaces prepared = prepareUserWithSpaces();

        StorageSpaceSaveDto smallSpaceDto = buildSpaceDto("small-space", Files.createTempDirectory(tempDir, "small-space"));
        StorageSpaceVo smallSpace = storageSpaceService.save(smallSpaceDto);

        LambdaUpdateWrapper<StorageSpace> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(StorageSpace::getCapacity, 1L);
        wrapper.set(StorageSpace::getUsedSpace, 0L);
        wrapper.set(StorageSpace::getFreeSpace, 1L);
        wrapper.eq(StorageSpace::getId, smallSpace.getId());
        storageSpaceMapper.update(wrapper);

        UserMigrationSubmitDto dto = new UserMigrationSubmitDto();
        dto.setUserId(prepared.user().getId());
        dto.setTargetSpaceId(smallSpace.getId());
        dto.setNewQuota(10737418240L);

        assertThrows(BusinessException.class, () -> userMigrationService.submitMigration(dto));
    }

    @Test
    void shouldRejectMigrationWhenPendingTaskExists() throws Exception {
        UserWithSpaces prepared = prepareUserWithSpaces();

        UserMigrationSubmitDto dto = new UserMigrationSubmitDto();
        dto.setUserId(prepared.user().getId());
        dto.setTargetSpaceId(prepared.targetSpace().getId());
        dto.setNewQuota(10737418240L);

        userMigrationService.submitMigration(dto);
        assertThrows(BusinessException.class, () -> userMigrationService.submitMigration(dto));
    }

    @Test
    void shouldReturnLatestTask() throws Exception {
        UserWithSpaces prepared = prepareUserWithSpaces();

        UserMigrationSubmitDto dto = new UserMigrationSubmitDto();
        dto.setUserId(prepared.user().getId());
        dto.setTargetSpaceId(prepared.targetSpace().getId());
        dto.setNewQuota(10737418240L);

        userMigrationService.submitMigration(dto);

        UserMigrationTaskVo latest = userMigrationService.getLatestTaskByUserId(prepared.user().getId());

        assertNotNull(latest);
        assertEquals("PENDING", latest.getStatus());
    }

    private UserWithSpaces prepareUserWithSpaces() throws Exception {
        StorageSpaceSaveDto sourceDto = buildSpaceDto("source-space", Files.createTempDirectory(tempDir, "source-space"));
        StorageSpaceVo sourceSpace = storageSpaceService.save(sourceDto);

        StorageSpaceSaveDto targetDto = buildSpaceDto("target-space", Files.createTempDirectory(tempDir, "target-space"));
        StorageSpaceVo targetSpace = storageSpaceService.save(targetDto);

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("user_" + Long.toUnsignedString(System.nanoTime(), 36));
        userDto.setPassword("123456");
        userDto.setStorageSpaceId(sourceSpace.getId());
        userDto.setQuota(10L);
        userDto.setQuotaUnit("GB");
        UserVo user = userService.saveUser(userDto);
        UserContext.set(new CurrentUser(user.getId(), user.getUsername()));

        return new UserWithSpaces(user, sourceSpace, targetSpace);
    }

    private StorageSpaceSaveDto buildSpaceDto(String name, Path path) {
        StorageSpaceSaveDto dto = new StorageSpaceSaveDto();
        dto.setName(name);
        dto.setPath(path.toString());
        return dto;
    }

    private record UserWithSpaces(UserVo user, StorageSpaceVo sourceSpace, StorageSpaceVo targetSpace) {
    }
}
