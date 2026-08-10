package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.SystemInitDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.SystemInitStatusVo;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统初始化服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SystemInitServiceTest {

    @TempDir
    Path tempDir;

    @Autowired
    private SystemInitService systemInitService;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private UserService userService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    @Autowired
    private UserMapper userMapper;

    @Test
    void shouldInitializeSystem() {
        UserVo user = prepareUser();
        SystemInitDto dto = buildDto();

        systemInitService.initialize(dto);

        SystemInitStatusVo status = systemInitService.getInitStatus();
        assertTrue(status.getInitialized());

        // 主空间被标记为 is_primary
        List<StorageSpace> spaces = storageSpaceMapper.selectList(
                new LambdaQueryWrapper<StorageSpace>()
                        .eq(StorageSpace::getPath, dto.getSpaces().get(0).getPath()));
        assertEquals(1, spaces.size());
        StorageSpace primarySpace = spaces.get(0);
        assertEquals(1, primarySpace.getIsPrimary());

        // 系统数据空间 ID 写入配置
        assertEquals(primarySpace.getId(), systemConfigService.getValue("system.storage.space.id", null));

        // 已存在用户被统一绑定到主空间
        User bound = userMapper.selectById(user.getId());
        assertEquals(primarySpace.getId(), bound.getStorageSpaceId());
        assertEquals(0L, bound.getQuota());
    }

    @Test
    void shouldRejectRepeatInitialization() {
        systemInitService.initialize(buildDto());

        assertThrows(BusinessException.class, () -> systemInitService.initialize(buildDto()));
    }

    @Test
    void shouldRejectInitializeWithoutSpaces() {
        SystemInitDto dto = new SystemInitDto();
        dto.setSpaces(List.of());
        dto.setPrimaryIndex(0);
        dto.setSystemDataIndex(0);

        assertThrows(BusinessException.class, () -> systemInitService.initialize(dto));
    }

    @Test
    void shouldRejectInvalidPrimaryIndex() {
        SystemInitDto dto = buildDto();
        dto.setPrimaryIndex(5);

        assertThrows(BusinessException.class, () -> systemInitService.initialize(dto));
    }

    @Test
    void shouldReturnNotInitializedBeforeInit() {
        SystemInitStatusVo status = systemInitService.getInitStatus();
        assertFalse(status.getInitialized());
        assertEquals(false, status.getAdmin());
    }

    private SystemInitDto buildDto() {
        SystemInitDto.InitSpaceItem item = new SystemInitDto.InitSpaceItem();
        item.setName("初始化空间");
        item.setPath(tempDir.resolve("init-space").toString());

        SystemInitDto dto = new SystemInitDto();
        dto.setSpaces(List.of(item));
        dto.setPrimaryIndex(0);
        dto.setSystemDataIndex(0);
        return dto;
    }

    /**
     * 初始化前先创建一个绑定到独立空间的用户，用于验证 initialize 的用户-主空间绑定副作用。
     */
    private UserVo prepareUser() {
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("用户空间");
        spaceDto.setPath(tempDir.resolve("user-space").toString());
        StorageSpaceVo space = storageSpaceService.save(spaceDto);

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("user_" + Long.toUnsignedString(System.nanoTime(), 36));
        userDto.setPassword("123456");
        userDto.setStorageSpaceId(space.getId());
        userDto.setQuota(1024L);
        userDto.setQuotaUnit("B");
        return userService.saveUser(userDto);
    }
}
