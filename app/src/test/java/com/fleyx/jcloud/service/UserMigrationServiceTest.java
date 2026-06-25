package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserMigrationSubmitDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.dto.UserStorageDto;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserMigrationTaskVo;
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

    @Test
    void shouldSubmitMigrationSuccessfully() {
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
    void shouldRejectMigrationWhenUserNotBoundToSpace() {
        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("migrateNoSpace");
        userDto.setPassword("123456");
        UserVo user = userService.saveUser(userDto);

        StorageSpaceSaveDto spaceDto = buildSpaceDto("target-no-binding", "/data/jcloud/target-no-binding");
        StorageSpaceVo space = storageSpaceService.save(spaceDto);

        UserMigrationSubmitDto dto = new UserMigrationSubmitDto();
        dto.setUserId(user.getId());
        dto.setTargetSpaceId(space.getId());
        dto.setNewQuota(10737418240L);

        assertThrows(BusinessException.class, () -> userMigrationService.submitMigration(dto));
    }

    @Test
    void shouldRejectMigrationWhenTargetSpaceSameAsSource() {
        UserWithSpaces prepared = prepareUserWithSpaces();

        UserMigrationSubmitDto dto = new UserMigrationSubmitDto();
        dto.setUserId(prepared.user().getId());
        dto.setTargetSpaceId(prepared.sourceSpace().getId());
        dto.setNewQuota(10737418240L);

        assertThrows(BusinessException.class, () -> userMigrationService.submitMigration(dto));
    }

    @Test
    void shouldRejectMigrationWhenTargetSpaceInsufficient() {
        UserWithSpaces prepared = prepareUserWithSpaces();

        StorageSpaceSaveDto smallSpaceDto = buildSpaceDto("small-space", "/data/jcloud/small-space");
        smallSpaceDto.setCapacity(1L);
        StorageSpaceVo smallSpace = storageSpaceService.save(smallSpaceDto);

        UserMigrationSubmitDto dto = new UserMigrationSubmitDto();
        dto.setUserId(prepared.user().getId());
        dto.setTargetSpaceId(smallSpace.getId());
        dto.setNewQuota(10737418240L);

        assertThrows(BusinessException.class, () -> userMigrationService.submitMigration(dto));
    }

    @Test
    void shouldRejectMigrationWhenPendingTaskExists() {
        UserWithSpaces prepared = prepareUserWithSpaces();

        UserMigrationSubmitDto dto = new UserMigrationSubmitDto();
        dto.setUserId(prepared.user().getId());
        dto.setTargetSpaceId(prepared.targetSpace().getId());
        dto.setNewQuota(10737418240L);

        userMigrationService.submitMigration(dto);
        assertThrows(BusinessException.class, () -> userMigrationService.submitMigration(dto));
    }

    @Test
    void shouldReturnLatestTask() {
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

    private UserWithSpaces prepareUserWithSpaces() {
        StorageSpaceSaveDto sourceDto = buildSpaceDto("source-space", "/data/jcloud/source-space");
        StorageSpaceVo sourceSpace = storageSpaceService.save(sourceDto);

        StorageSpaceSaveDto targetDto = buildSpaceDto("target-space", "/data/jcloud/target-space");
        targetDto.setCapacity(214748364800L);
        StorageSpaceVo targetSpace = storageSpaceService.save(targetDto);

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("migrateUser" + System.nanoTime());
        userDto.setPassword("123456");
        UserVo user = userService.saveUser(userDto);

        UserStorageDto bindDto = new UserStorageDto();
        bindDto.setUserId(user.getId());
        bindDto.setStorageSpaceId(sourceSpace.getId());
        bindDto.setQuota(10737418240L);
        userService.bindStorageSpace(bindDto);

        return new UserWithSpaces(user, sourceSpace, targetSpace);
    }

    private StorageSpaceSaveDto buildSpaceDto(String name, String path) {
        StorageSpaceSaveDto dto = new StorageSpaceSaveDto();
        dto.setName(name);
        dto.setPath(path);
        dto.setType("USER");
        dto.setCapacity(107374182400L);
        return dto;
    }

    private record UserWithSpaces(UserVo user, StorageSpaceVo sourceSpace, StorageSpaceVo targetSpace) {
    }
}
