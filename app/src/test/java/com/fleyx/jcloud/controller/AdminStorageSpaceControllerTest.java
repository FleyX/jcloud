package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.dto.StorageSpacePageQueryDto;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.StorageSpaceUpdateDto;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.service.StorageSpaceService;
import com.fleyx.jcloud.service.SystemConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 存储空间管理控制器单元测试（路由、DTO 透传与 R 包装）。
 */
class AdminStorageSpaceControllerTest {

    private final StorageSpaceService storageSpaceService = mock(StorageSpaceService.class);
    private final SystemConfigService systemConfigService = mock(SystemConfigService.class);

    private final AdminStorageSpaceController controller =
            new AdminStorageSpaceController(storageSpaceService, systemConfigService);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void setUp() {
        UserContext.set(new CurrentUser("user-1", "user-1"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static StorageSpaceVo space(String id, String name, String path) {
        StorageSpaceVo vo = new StorageSpaceVo();
        vo.setId(id);
        vo.setName(name);
        vo.setPath(path);
        return vo;
    }

    /**
     * POST /admin/storage-spaces 创建：请求体 DTO 原样透传 service，返回 R.ok 包装的存储空间视图。
     */
    @Test
    void shouldSaveStorageSpacePassingDto() throws Exception {
        when(storageSpaceService.save(any(StorageSpaceSaveDto.class))).thenReturn(space("ss-1", "主存储", "/data"));

        mockMvc.perform(post("/jcloud/api/admin/storage-spaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"主存储\",\"path\":\"/data\",\"isPrimary\":1,\"remark\":\"系统数据目录\"}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.id").value("ss-1"), jsonPath("$.data.name").value("主存储"),
                        jsonPath("$.data.path").value("/data"));

        var captor = forClass(StorageSpaceSaveDto.class);
        verify(storageSpaceService).save(captor.capture());
        assertEquals("主存储", captor.getValue().getName());
        assertEquals("/data", captor.getValue().getPath());
        assertEquals(1, captor.getValue().getIsPrimary());
        assertEquals("系统数据目录", captor.getValue().getRemark());
    }

    /**
     * GET /admin/storage-spaces 透传分页查询条件，返回 R.ok 包装的分页数据。
     */
    @Test
    void shouldPageStorageSpacesWithQueryParams() throws Exception {
        Page<StorageSpaceVo> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(space("ss-1", "主存储", "/data")));
        when(storageSpaceService.page(any(StorageSpacePageQueryDto.class))).thenReturn(page);

        mockMvc.perform(get("/jcloud/api/admin/storage-spaces")
                        .param("name", "存储")
                        .param("status", "1")
                        .param("pageNum", "2")
                        .param("pageSize", "50"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.total").value(1),
                        jsonPath("$.data.records[0].id").value("ss-1"),
                        jsonPath("$.data.records[0].name").value("主存储"));

        var captor = forClass(StorageSpacePageQueryDto.class);
        verify(storageSpaceService).page(captor.capture());
        assertEquals("存储", captor.getValue().getName());
        assertEquals(1, captor.getValue().getStatus());
        assertEquals(2L, captor.getValue().getPageNum());
        assertEquals(50L, captor.getValue().getPageSize());
    }

    /**
     * GET /admin/storage-spaces/{id} 详情：透传 id，返回 R.ok 包装的存储空间视图。
     */
    @Test
    void shouldGetStorageSpaceById() throws Exception {
        when(storageSpaceService.getById("ss-1")).thenReturn(space("ss-1", "主存储", "/data"));

        mockMvc.perform(get("/jcloud/api/admin/storage-spaces/ss-1"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.id").value("ss-1"), jsonPath("$.data.name").value("主存储"));

        verify(storageSpaceService).getById(eq("ss-1"));
    }

    /**
     * PUT /admin/storage-spaces/{id} 更新：路径 id 在 @Valid 校验后覆盖 DTO 中的 id 再透传 service，返回 R.ok 包装的更新后视图。
     */
    @Test
    void shouldUpdateStorageSpaceWithPathVariableId() throws Exception {
        when(storageSpaceService.update(any(StorageSpaceUpdateDto.class))).thenReturn(space("ss-1", "备份存储", "/data2"));

        mockMvc.perform(put("/jcloud/api/admin/storage-spaces/ss-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"ignored\",\"name\":\"备份存储\",\"path\":\"/data2\","
                                + "\"isPrimary\":0,\"status\":1,\"remark\":\"更新后\"}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.id").value("ss-1"), jsonPath("$.data.name").value("备份存储"));

        var captor = forClass(StorageSpaceUpdateDto.class);
        verify(storageSpaceService).update(captor.capture());
        assertEquals("ss-1", captor.getValue().getId());
        assertEquals("备份存储", captor.getValue().getName());
        assertEquals("/data2", captor.getValue().getPath());
        assertEquals(1, captor.getValue().getStatus());
    }

    /**
     * DELETE /admin/storage-spaces/{id} 删除：透传 id，返回 R.ok（data 为 null）。
     */
    @Test
    void shouldDeleteStorageSpaceAndReturnOk() throws Exception {
        mockMvc.perform(delete("/jcloud/api/admin/storage-spaces/ss-1"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data").value(nullValue()));

        verify(storageSpaceService).removeById(eq("ss-1"));
    }

    /**
     * POST /admin/storage-spaces/{id}/refresh 刷新磁盘状态：透传 id，返回 R.ok 包装的刷新后视图。
     */
    @Test
    void shouldRefreshDiskSpaceById() throws Exception {
        when(storageSpaceService.refreshDiskSpace("ss-1")).thenReturn(space("ss-1", "主存储", "/data"));

        mockMvc.perform(post("/jcloud/api/admin/storage-spaces/ss-1/refresh"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.id").value("ss-1"));

        verify(storageSpaceService).refreshDiskSpace(eq("ss-1"));
    }

    /**
     * GET /admin/storage-spaces/system-config：从系统配置读取系统数据目录空间 ID，返回 R.ok 包装的配置视图。
     */
    @Test
    void shouldReturnSystemConfig() throws Exception {
        when(systemConfigService.getValue(eq("system.storage.space.id"), isNull())).thenReturn("ss-1");

        mockMvc.perform(get("/jcloud/api/admin/storage-spaces/system-config"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.systemSpaceId").value("ss-1"));

        verify(systemConfigService).getValue(eq("system.storage.space.id"), isNull());
    }

    /**
     * PUT /admin/storage-spaces/system-config 更新：先校验空间存在，再透传配置键值，返回 R.ok（data 为 null）。
     */
    @Test
    void shouldUpdateSystemConfigPassingSpaceId() throws Exception {
        when(storageSpaceService.getById("ss-1")).thenReturn(space("ss-1", "主存储", "/data"));

        mockMvc.perform(put("/jcloud/api/admin/storage-spaces/system-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemSpaceId\":\"ss-1\"}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data").value(nullValue()));

        verify(storageSpaceService).getById(eq("ss-1"));
        verify(systemConfigService).setValue(eq("system.storage.space.id"), eq("ss-1"));
    }

    /**
     * POST /admin/storage-spaces 请求体缺少必填字段：@Valid 校验失败经 GlobalExceptionHandler
     * 包装为 R（body code=400，msg 含校验消息），service 不被调用。
     */
    @Test
    void shouldRejectInvalidSaveDtoWithParamError() throws Exception {
        mockMvc.perform(post("/jcloud/api/admin/storage-spaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(400),
                        jsonPath("$.msg").value(containsString("存储空间名称不能为空")),
                        jsonPath("$.data").value(nullValue()));

        verify(storageSpaceService, never()).save(any());
    }

    /**
     * PUT /admin/storage-spaces/{id} 请求体缺少必填字段：@Valid 校验失败（含 DTO 上的 id 校验）经
     * GlobalExceptionHandler 包装为 R（body code=400），service 不被调用。
     */
    @Test
    void shouldRejectInvalidUpdateDtoWithParamError() throws Exception {
        mockMvc.perform(put("/jcloud/api/admin/storage-spaces/ss-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(400),
                        jsonPath("$.msg").value(containsString("ID 不能为空")),
                        jsonPath("$.data").value(nullValue()));

        verify(storageSpaceService, never()).update(any());
    }

    /**
     * PUT /admin/storage-spaces/system-config 请求体缺少必填字段：@Valid 校验失败经
     * GlobalExceptionHandler 包装为 R（body code=400），service 不被调用。
     */
    @Test
    void shouldRejectInvalidSystemConfigDtoWithParamError() throws Exception {
        mockMvc.perform(put("/jcloud/api/admin/storage-spaces/system-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(400),
                        jsonPath("$.msg").value(containsString("存储空间 ID 不能为空")),
                        jsonPath("$.data").value(nullValue()));

        verify(storageSpaceService, never()).getById(any());
        verify(systemConfigService, never()).setValue(any(), any());
    }

    /**
     * service 抛 BusinessException：经 GlobalExceptionHandler 包装为 R（body code=404，HTTP 状态仍 200）。
     */
    @Test
    void shouldWrapBusinessExceptionOnDetail() throws Exception {
        when(storageSpaceService.getById("ss-9"))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在"));

        mockMvc.perform(get("/jcloud/api/admin/storage-spaces/ss-9"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(404),
                        jsonPath("$.msg").value("存储空间不存在"));

        verify(storageSpaceService).getById(eq("ss-9"));
    }
}
