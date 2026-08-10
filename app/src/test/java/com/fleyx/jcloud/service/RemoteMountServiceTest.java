package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.model.dto.RemoteMountSaveDto;
import com.fleyx.jcloud.model.dto.RemoteMountUpdateDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.vo.RemoteMountDetailVo;
import com.fleyx.jcloud.model.vo.RemoteMountVo;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.util.IdUtil;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 远程挂载服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RemoteMountServiceTest {

    @Autowired
    private RemoteMountService remoteMountService;

    @Autowired
    private UserService userService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private RemoteMountMapper remoteMountMapper;

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateRemoteMount() throws Exception {
        UserVo user = prepareUser();
        RemoteMountSaveDto dto = buildSaveDto();

        RemoteMountVo mount = remoteMountService.save(dto, user.getId());

        assertNotNull(mount.getId());
        assertEquals(dto.getName(), mount.getName());
        assertEquals("webdav", mount.getType());
    }

    @Test
    void shouldRejectDuplicateMountName() throws Exception {
        UserVo user = prepareUser();
        RemoteMountSaveDto dto = buildSaveDto();
        remoteMountService.save(dto, user.getId());

        assertThrows(BusinessException.class, () -> remoteMountService.save(dto, user.getId()));
    }

    @Test
    void shouldNotReturnPlainPasswordInDetail() throws Exception {
        UserVo user = prepareUser();
        RemoteMountVo mount = remoteMountService.save(buildSaveDto(), user.getId());

        RemoteMountDetailVo detail = remoteMountService.detail(mount.getId(), user.getId());

        assertEquals("http://example.com/dav", detail.getUrl());
        assertEquals("user", detail.getUsername());
        assertNull(detail.getPassword());
    }

    @Test
    void shouldUpdateMountName() throws Exception {
        UserVo user = prepareUser();
        RemoteMountVo mount = remoteMountService.save(buildSaveDto(), user.getId());

        RemoteMountUpdateDto updateDto = new RemoteMountUpdateDto();
        updateDto.setId(mount.getId());
        updateDto.setName("new-name");
        updateDto.setType("webdav");
        updateDto.setUrl("http://example.com/dav");
        updateDto.setUsername("user");
        updateDto.setPassword("pass");
        updateDto.setEnabled(0);

        RemoteMountVo updated = remoteMountService.update(updateDto, user.getId());

        assertEquals("new-name", updated.getName());
        RemoteMountDetailVo detail = remoteMountService.detail(mount.getId(), user.getId());
        assertEquals("new-name", detail.getName());
    }

    @Test
    void shouldParseLegacyConfigWithUnknownRootPathField() throws Exception {
        UserVo user = prepareUser();
        RemoteMount mount = new RemoteMount();
        mount.setId(IdUtil.nextId());
        mount.setUserId(user.getId());
        mount.setName("legacy-mount");
        mount.setType("webdav");
        mount.setEnabled(0);
        mount.setConfig("{\"url\":\"http://legacy.example.com/dav\",\"username\":\"legacy-user\",\"rootPath\":\"/old\",\"password\":null}");
        remoteMountMapper.insert(mount);

        RemoteMountDetailVo detail = remoteMountService.detail(mount.getId(), user.getId());

        assertEquals("http://legacy.example.com/dav", detail.getUrl());
        assertEquals("legacy-user", detail.getUsername());
    }

    @Test
    void shouldRejectInvalidCronOnSave() throws Exception {
        UserVo user = prepareUser();
        RemoteMountSaveDto dto = buildSaveDto();
        dto.setCronExpr("not-a-cron");

        assertThrows(BusinessException.class, () -> remoteMountService.save(dto, user.getId()));
    }

    @Test
    void shouldRejectInvalidCronOnUpdate() throws Exception {
        UserVo user = prepareUser();
        RemoteMountVo mount = remoteMountService.save(buildSaveDto(), user.getId());

        RemoteMountUpdateDto updateDto = new RemoteMountUpdateDto();
        updateDto.setId(mount.getId());
        updateDto.setName(mount.getName());
        updateDto.setType("webdav");
        updateDto.setUrl("http://example.com/dav");
        updateDto.setUsername("user");
        updateDto.setPassword("pass");
        updateDto.setEnabled(1);
        updateDto.setCronExpr("not-a-cron");

        assertThrows(BusinessException.class, () -> remoteMountService.update(updateDto, user.getId()));
    }

    private UserVo prepareUser() throws Exception {
        Path spacePath = Files.createTempDirectory(tempDir, "mount-space-");
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("mount-space-" + System.nanoTime());
        spaceDto.setPath(spacePath.toString());
        StorageSpaceVo space = storageSpaceService.save(spaceDto);

        UserSaveDto dto = new UserSaveDto();
        dto.setUsername("mount_user_" + System.nanoTime());
        dto.setPassword("123456");
        dto.setStorageSpaceId(space.getId());
        dto.setQuota(1L);
        dto.setQuotaUnit("GB");
        return userService.saveUser(dto);
    }

    private RemoteMountSaveDto buildSaveDto() {
        RemoteMountSaveDto dto = new RemoteMountSaveDto();
        dto.setName("my-webdav-" + System.nanoTime());
        dto.setType("webdav");
        dto.setUrl("http://example.com/dav");
        dto.setUsername("user");
        dto.setPassword("pass");
        dto.setCronExpr("0 0 2 * * *");
        dto.setEnabled(1);
        return dto;
    }
}
