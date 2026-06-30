package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.dto.UserSyncConfigUpdateDto;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserSyncConfigVo;
import com.fleyx.jcloud.model.vo.UserSyncTaskVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 用户存储空间同步服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserSyncServiceTest {

    @Autowired
    private UserSyncService userSyncService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private UserMapper userMapper;

    @Test
    void shouldSubmitImmediateSyncTask() throws Exception {
        UserVo user = prepareUser();

        UserSyncTaskVo task = userSyncService.submitImmediate(user.getId());

        assertNotNull(task.getId());
        assertEquals(user.getId(), task.getUserId());
        assertEquals("PENDING", task.getStatus());
        assertEquals("manual", task.getType());
    }

    @Test
    void shouldRejectImmediateSyncWhenUserNotBoundToSpace() throws Exception {
        UserVo user = prepareUser();
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.fleyx.jcloud.model.po.User> wrapper =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        wrapper.eq(com.fleyx.jcloud.model.po.User::getId, user.getId());
        wrapper.set(com.fleyx.jcloud.model.po.User::getStorageSpaceId, null);
        userMapper.update(wrapper);

        assertThrows(BusinessException.class, () -> userSyncService.submitImmediate(user.getId()));
    }

    @Test
    void shouldReturnDefaultConfigWhenNotExists() throws Exception {
        UserVo user = prepareUser();

        UserSyncConfigVo config = userSyncService.getConfig(user.getId());

        assertEquals(user.getId(), config.getUserId());
        assertEquals(0, config.getEnabled());
        assertEquals("", config.getCronExpr());
    }

    @Test
    void shouldUpdateConfigAndComputeNextSyncTime() throws Exception {
        UserVo user = prepareUser();
        UserSyncConfigUpdateDto dto = new UserSyncConfigUpdateDto();
        dto.setUserId(user.getId());
        dto.setCronExpr("0 0 2 * * *");
        dto.setEnabled(1);

        UserSyncConfigVo config = userSyncService.updateConfig(dto);

        assertEquals(user.getId(), config.getUserId());
        assertEquals(1, config.getEnabled());
        assertEquals("0 0 2 * * *", config.getCronExpr());
        assertNotNull(config.getNextSyncTime());
    }

    @Test
    void shouldRejectInvalidCronExpression() throws Exception {
        UserVo user = prepareUser();
        UserSyncConfigUpdateDto dto = new UserSyncConfigUpdateDto();
        dto.setUserId(user.getId());
        dto.setCronExpr("invalid cron");
        dto.setEnabled(1);

        assertThrows(BusinessException.class, () -> userSyncService.updateConfig(dto));
    }

    private UserVo prepareUser() throws Exception {
        Path spacePath = Files.createTempDirectory("sync-space-");
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("sync-space-" + System.nanoTime());
        spaceDto.setPath(spacePath.toString());
        StorageSpaceVo space = storageSpaceService.save(spaceDto);

        UserSaveDto dto = new UserSaveDto();
        dto.setUsername("sync_user_" + System.nanoTime());
        dto.setPassword("123456");
        dto.setStorageSpaceId(space.getId());
        dto.setQuota(1L);
        dto.setQuotaUnit("GB");
        return userService.saveUser(dto);
    }
}
