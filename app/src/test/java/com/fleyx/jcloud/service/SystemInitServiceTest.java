package com.fleyx.jcloud.service;

import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.model.dto.SystemInitDto;
import com.fleyx.jcloud.model.vo.SystemInitStatusVo;
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

    @Test
    void shouldInitializeSystem() {
        SystemInitDto dto = buildDto();

        systemInitService.initialize(dto);

        SystemInitStatusVo status = systemInitService.getInitStatus();
        assertTrue(status.getInitialized());
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
}
