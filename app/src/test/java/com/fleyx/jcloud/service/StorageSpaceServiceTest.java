package com.fleyx.jcloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.StorageSpacePageQueryDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.StorageSpaceUpdateDto;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
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
