package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.StorageSpacePageQueryDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.StorageSpaceUpdateDto;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.fleyx.jcloud.common.exception.BusinessException;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储空间服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StorageSpaceServiceTest {

    @TempDir
    Path tempDir;

    @Autowired
    private StorageSpaceService storageSpaceService;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private SystemConfigService systemConfigService;

    @Test
    void shouldCreateStorageSpace() {
        StorageSpaceSaveDto dto = buildDto("默认空间", "default");

        StorageSpaceVo vo = storageSpaceService.save(dto);

        assertNotNull(vo.getId());
        assertEquals(dto.getName(), vo.getName());
        assertEquals(dto.getPath(), vo.getPath());
        assertTrue(vo.getCapacity() > 0);
        assertTrue(vo.getUsedSpace() >= 0);
        assertTrue(vo.getFreeSpace() >= 0);
        assertEquals(1, vo.getStatus());
    }

    @Test
    void shouldRejectDuplicatePath() {
        StorageSpaceSaveDto dto = buildDto("默认空间", "duplicate");
        storageSpaceService.save(dto);

        StorageSpaceSaveDto duplicate = buildDto("重复空间", "duplicate");
        assertThrows(BusinessException.class, () -> storageSpaceService.save(duplicate));
    }

    @Test
    void shouldPageStorageSpaces() {
        storageSpaceService.save(buildDto("空间 A", "a"));
        storageSpaceService.save(buildDto("空间 B", "b"));

        StorageSpacePageQueryDto query = new StorageSpacePageQueryDto();
        query.setPageNum(1L);
        query.setPageSize(10L);

        IPage<StorageSpaceVo> page = storageSpaceService.page(query);

        assertEquals(2L, page.getTotal());
        assertEquals(2, page.getRecords().size());
        page.getRecords().forEach(r -> assertTrue(r.getCapacity() > 0));
    }

    @Test
    void shouldUpdateStorageSpace() {
        StorageSpaceVo saved = storageSpaceService.save(buildDto("旧名称", "update"));

        StorageSpaceUpdateDto update = new StorageSpaceUpdateDto();
        update.setId(saved.getId());
        update.setName("新名称");
        update.setPath(tempDir.resolve("update-new").toString());
        update.setStatus(0);
        update.setRemark("更新后");

        StorageSpaceVo updated = storageSpaceService.update(update);

        assertEquals("新名称", updated.getName());
        assertEquals(0, updated.getStatus());
        assertEquals("更新后", updated.getRemark());
        assertTrue(updated.getCapacity() > 0);
    }

    @Test
    void shouldSetPrimarySpace() {
        StorageSpaceVo first = storageSpaceService.save(buildDto("空间一", "primary1"));
        StorageSpaceVo second = storageSpaceService.save(buildDto("空间二", "primary2"));

        StorageSpaceUpdateDto update = new StorageSpaceUpdateDto();
        update.setId(second.getId());
        update.setName(second.getName());
        update.setPath(second.getPath());
        update.setStatus(1);
        update.setIsPrimary(1);
        storageSpaceService.update(update);

        StorageSpaceVo refreshed = storageSpaceService.getById(first.getId());
        assertEquals(0, refreshed.getIsPrimary());
        StorageSpaceVo primary = storageSpaceService.getById(second.getId());
        assertEquals(1, primary.getIsPrimary());
    }

    @Test
    void shouldDeleteUnusedStorageSpace() {
        StorageSpaceVo saved = storageSpaceService.save(buildDto("待删除", "delete"));

        storageSpaceService.removeById(saved.getId());

        assertThrows(BusinessException.class, () -> storageSpaceService.getById(saved.getId()));
    }

    @Test
    void shouldRejectDeleteWhenBoundToUser() {
        StorageSpaceVo saved = storageSpaceService.save(buildDto("已绑定", "bound"));

        User user = new User();
        user.setUsername("boundUser");
        user.setPassword("123456");
        user.setStorageSpaceId(saved.getId());
        user.setQuota(10737418240L);
        userMapper.insert(user);

        assertThrows(BusinessException.class, () -> storageSpaceService.removeById(saved.getId()));
    }

    @Test
    void shouldRejectDeletePrimarySpace() {
        StorageSpaceVo saved = storageSpaceService.save(buildDto("主空间", "primary-delete"));
        StorageSpaceUpdateDto update = new StorageSpaceUpdateDto();
        update.setId(saved.getId());
        update.setName(saved.getName());
        update.setPath(saved.getPath());
        update.setStatus(1);
        update.setIsPrimary(1);
        storageSpaceService.update(update);

        assertThrows(BusinessException.class, () -> storageSpaceService.removeById(saved.getId()));
    }

    @Test
    void shouldRejectDeleteWhenConfiguredAsSystemSpace() {
        StorageSpaceVo saved = storageSpaceService.save(buildDto("系统目录空间", "system-configured"));
        systemConfigService.setValue("system.storage.space.id", String.valueOf(saved.getId()));

        assertThrows(BusinessException.class, () -> storageSpaceService.removeById(saved.getId()));
    }

    private StorageSpaceSaveDto buildDto(String name, String relativePath) {
        StorageSpaceSaveDto dto = new StorageSpaceSaveDto();
        dto.setName(name);
        dto.setPath(tempDir.resolve(relativePath).toString());
        dto.setRemark("测试存储空间");
        return dto;
    }
}
