package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.StorageSpaceExpandDto;
import com.fleyx.jcloud.model.dto.StorageSpacePageQueryDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.StorageSpaceUpdateDto;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.fleyx.jcloud.common.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 存储空间服务测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StorageSpaceServiceTest {

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
        StorageSpaceSaveDto dto = buildDto("默认空间", "/data/jcloud/default");

        StorageSpaceVo vo = storageSpaceService.save(dto);

        assertNotNull(vo.getId());
        assertEquals(dto.getName(), vo.getName());
        assertEquals(dto.getPath(), vo.getPath());
        assertEquals(dto.getType(), vo.getType());
        assertEquals(dto.getCapacity(), vo.getCapacity());
        assertEquals(0L, vo.getUsedSpace());
        assertEquals(1, vo.getStatus());
    }

    @Test
    void shouldRejectDuplicatePath() {
        StorageSpaceSaveDto dto = buildDto("默认空间", "/data/jcloud/duplicate");
        storageSpaceService.save(dto);

        StorageSpaceSaveDto duplicate = buildDto("重复空间", "/data/jcloud/duplicate");
        assertThrows(BusinessException.class, () -> storageSpaceService.save(duplicate));
    }

    @Test
    void shouldPageStorageSpaces() {
        storageSpaceService.save(buildDto("空间 A", "/data/jcloud/a"));
        storageSpaceService.save(buildDto("空间 B", "/data/jcloud/b"));

        StorageSpacePageQueryDto query = new StorageSpacePageQueryDto();
        query.setPageNum(1L);
        query.setPageSize(10L);

        IPage<StorageSpaceVo> page = storageSpaceService.page(query);

        assertEquals(2L, page.getTotal());
        assertEquals(2, page.getRecords().size());
    }

    @Test
    void shouldUpdateStorageSpace() {
        StorageSpaceVo saved = storageSpaceService.save(buildDto("旧名称", "/data/jcloud/update"));

        StorageSpaceUpdateDto update = new StorageSpaceUpdateDto();
        update.setId(saved.getId());
        update.setName("新名称");
        update.setPath("/data/jcloud/update");
        update.setType("USER");
        update.setCapacity(214748364800L);
        update.setStatus(0);
        update.setRemark("更新后");

        StorageSpaceVo updated = storageSpaceService.update(update);

        assertEquals("新名称", updated.getName());
        assertEquals(214748364800L, updated.getCapacity());
        assertEquals(0, updated.getStatus());
        assertEquals("更新后", updated.getRemark());
    }

    @Test
    void shouldDeleteUnusedStorageSpace() {
        StorageSpaceVo saved = storageSpaceService.save(buildDto("待删除", "/data/jcloud/delete"));

        storageSpaceService.removeById(saved.getId());

        assertThrows(BusinessException.class, () -> storageSpaceService.getById(saved.getId()));
    }

    @Test
    void shouldRejectDeleteWhenBoundToUser() {
        StorageSpaceVo saved = storageSpaceService.save(buildDto("已绑定", "/data/jcloud/bound"));

        User user = new User();
        user.setUsername("boundUser");
        user.setPassword("123456");
        user.setStorageSpaceId(saved.getId());
        user.setQuota(10737418240L);
        userMapper.insert(user);

        assertThrows(BusinessException.class, () -> storageSpaceService.removeById(saved.getId()));
    }

    @Test
    void shouldExpandCapacity() {
        StorageSpaceVo saved = storageSpaceService.save(buildDto("扩容空间", "/data/jcloud/expand"));
        StorageSpaceExpandDto dto = new StorageSpaceExpandDto();
        dto.setId(saved.getId());
        dto.setCapacity(214748364800L);

        StorageSpaceVo expanded = storageSpaceService.expandCapacity(dto);

        assertEquals(214748364800L, expanded.getCapacity());
    }

    @Test
    void shouldRejectExpandBelowUsedSpace() {
        StorageSpaceVo saved = storageSpaceService.save(buildDto("已用空间", "/data/jcloud/used"));
        StorageSpace po = storageSpaceMapper.selectById(saved.getId());
        po.setUsedSpace(10737418240L);
        storageSpaceMapper.updateById(po);

        StorageSpaceExpandDto dto = new StorageSpaceExpandDto();
        dto.setId(saved.getId());
        dto.setCapacity(10737418239L);

        assertThrows(BusinessException.class, () -> storageSpaceService.expandCapacity(dto));
    }

    @Test
    void shouldRejectSystemTypeOnCreate() {
        StorageSpaceSaveDto dto = buildDto("系统空间", "/data/jcloud/system-create");
        dto.setType("SYSTEM");

        assertThrows(BusinessException.class, () -> storageSpaceService.save(dto));
    }

    @Test
    void shouldRejectSystemTypeOnUpdate() {
        StorageSpaceVo saved = storageSpaceService.save(buildDto("用户空间", "/data/jcloud/system-update"));

        StorageSpaceUpdateDto update = new StorageSpaceUpdateDto();
        update.setId(saved.getId());
        update.setName(saved.getName());
        update.setPath(saved.getPath());
        update.setType("SYSTEM");
        update.setCapacity(saved.getCapacity());
        update.setStatus(saved.getStatus());

        assertThrows(BusinessException.class, () -> storageSpaceService.update(update));
    }

    @Test
    void shouldRejectDeleteWhenConfiguredAsSystemSpace() {
        StorageSpaceVo saved = storageSpaceService.save(buildDto("系统目录空间", "/data/jcloud/system-configured"));
        systemConfigService.setValue("system.storage.space.id", String.valueOf(saved.getId()));

        assertThrows(BusinessException.class, () -> storageSpaceService.removeById(saved.getId()));
    }

    private StorageSpaceSaveDto buildDto(String name, String path) {
        StorageSpaceSaveDto dto = new StorageSpaceSaveDto();
        dto.setName(name);
        dto.setPath(path);
        dto.setType("USER");
        dto.setCapacity(107374182400L);
        dto.setRemark("测试存储空间");
        return dto;
    }
}
