package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.mapper.RemoteSyncTaskMapper;
import com.fleyx.jcloud.model.dto.RemoteMountSaveDto;
import com.fleyx.jcloud.model.dto.RemoteMountSyncConfigUpdateDto;
import com.fleyx.jcloud.model.dto.RemoteSyncTaskPageQueryDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.po.RemoteSyncTask;
import com.fleyx.jcloud.model.vo.RemoteMountVo;
import com.fleyx.jcloud.model.vo.RemoteSyncTaskVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 远程挂载同步服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RemoteMountSyncServiceTest {

    @Autowired
    private RemoteMountSyncService remoteMountSyncService;

    @Autowired
    private RemoteMountService remoteMountService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private RemoteMountMapper remoteMountMapper;

    @Autowired
    private RemoteSyncTaskMapper remoteSyncTaskMapper;

    @Test
    void shouldSubmitImmediateAndReplacePendingTask() {
        UserVo user = prepareUser();
        RemoteMountVo mount = createMount(user.getId());

        RemoteSyncTaskVo second = remoteMountSyncService.submitImmediate(mount.getId(), user.getId());

        assertNotNull(second.getId());
        assertEquals("PENDING", second.getStatus());
        // save 时自动提交的旧 PENDING 任务应已被移除，仅保留新任务
        RemoteSyncTaskVo latest = remoteMountSyncService.getLatestTask(mount.getId(), user.getId());
        assertEquals(second.getId(), latest.getId());
        RemoteSyncTaskPageQueryDto query = new RemoteSyncTaskPageQueryDto();
        IPage<RemoteSyncTaskVo> page = remoteMountSyncService.pageTasks(mount.getId(), user.getId(), query);
        assertEquals(1, page.getTotal());
    }

    @Test
    void shouldRejectSubmitWhenTaskRunning() {
        UserVo user = prepareUser();
        RemoteMountVo mount = createMount(user.getId());
        RemoteSyncTaskVo task = remoteMountSyncService.getLatestTask(mount.getId(), user.getId());
        RemoteSyncTask update = new RemoteSyncTask();
        update.setId(task.getId());
        update.setStatus("RUNNING");
        remoteSyncTaskMapper.updateById(update);

        assertThrows(BusinessException.class,
                () -> remoteMountSyncService.submitImmediate(mount.getId(), user.getId()));
    }

    @Test
    void shouldPageTasksAcrossUserMounts() {
        UserVo user = prepareUser();
        createMount(user.getId());

        RemoteSyncTaskPageQueryDto query = new RemoteSyncTaskPageQueryDto();
        IPage<RemoteSyncTaskVo> page = remoteMountSyncService.pageTasks(null, user.getId(), query);

        assertEquals(1, page.getTotal());
        assertEquals("PENDING", page.getRecords().get(0).getStatus());
    }

    @Test
    void shouldReturnNullLatestTaskWhenNone() {
        UserVo user = prepareUser();
        RemoteMount mount = new RemoteMount();
        mount.setId(com.fleyx.jcloud.util.IdUtil.nextId());
        mount.setUserId(user.getId());
        mount.setName("idle-mount-" + System.nanoTime());
        mount.setType("webdav");
        mount.setEnabled(0);
        remoteMountMapper.insert(mount);

        assertNull(remoteMountSyncService.getLatestTask(mount.getId(), user.getId()));
    }

    @Test
    void shouldUpdateConfigAndNextSyncTime() {
        UserVo user = prepareUser();
        RemoteMountVo mount = createMount(user.getId());

        RemoteMountSyncConfigUpdateDto dto = new RemoteMountSyncConfigUpdateDto();
        dto.setCronExpr("0 0 2 * * *");
        dto.setEnabled(1);
        remoteMountSyncService.updateConfig(mount.getId(), dto, user.getId());

        RemoteMount updated = remoteMountMapper.selectById(mount.getId());
        assertNotNull(updated.getNextSyncTime());
        assertEquals("0 0 2 * * *", updated.getCronExpr());

        dto.setEnabled(0);
        remoteMountSyncService.updateConfig(mount.getId(), dto, user.getId());
        assertNull(remoteMountMapper.selectById(mount.getId()).getNextSyncTime());
    }

    @Test
    void shouldRejectInvalidCronExpr() {
        UserVo user = prepareUser();
        RemoteMountVo mount = createMount(user.getId());

        RemoteMountSyncConfigUpdateDto dto = new RemoteMountSyncConfigUpdateDto();
        dto.setCronExpr("not-a-cron");
        dto.setEnabled(1);

        assertThrows(BusinessException.class,
                () -> remoteMountSyncService.updateConfig(mount.getId(), dto, user.getId()));
    }

    private RemoteMountVo createMount(String userId) {
        RemoteMountSaveDto dto = new RemoteMountSaveDto();
        dto.setName("mount-" + System.nanoTime());
        dto.setType("webdav");
        dto.setUrl("http://example.com/dav");
        dto.setUsername("user");
        dto.setPassword("pass");
        dto.setEnabled(0);
        return remoteMountService.save(dto, userId);
    }

    private UserVo prepareUser() {
        try {
            Path spacePath = Files.createTempDirectory("remote-sync-space-");
            StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
            spaceDto.setName("remote-sync-space-" + System.nanoTime());
            spaceDto.setPath(spacePath.toString());
            StorageSpaceVo space = storageSpaceService.save(spaceDto);

            UserSaveDto dto = new UserSaveDto();
            dto.setUsername("remote_sync_svc_" + System.nanoTime());
            dto.setPassword("123456");
            dto.setStorageSpaceId(space.getId());
            dto.setQuota(1L);
            dto.setQuotaUnit("GB");
            return userService.saveUser(dto);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
