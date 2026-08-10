package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.vo.TransferTaskVo;
import com.fleyx.jcloud.service.TransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跨来源传输控制器单元测试（路由、DTO 透传与 R 包装）。
 */
class TransferControllerTest {

    private final TransferService transferService = mock(TransferService.class);

    private final TransferController controller = new TransferController(transferService);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void setUp() {
        UserContext.set(new CurrentUser("u1", "u1"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static TransferTaskVo task(String id, String status) {
        TransferTaskVo vo = new TransferTaskVo();
        vo.setId(id);
        vo.setOpType("move");
        vo.setSourceType("local");
        vo.setTargetType("remote");
        vo.setStatus(status);
        return vo;
    }

    /** POST /transfers/move：请求体 DTO 原样透传，opType=move，返回 R.ok 包装的传输任务。 */
    @Test
    void shouldCreateMoveTransferPassingDtoAndUser() throws Exception {
        ArgumentCaptor<FileExecuteOperationDto> dtoCaptor = ArgumentCaptor.forClass(FileExecuteOperationDto.class);
        when(transferService.createTransfer(dtoCaptor.capture(), eq("move"), eq("u1"))).thenReturn(task("t-1", "PENDING"));
        mockMvc.perform(post("/jcloud/api/transfers/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"move\",\"targetParentId\":\"tp-1\","
                                + "\"items\":[{\"id\":\"n1\",\"name\":\"a.txt\",\"strategy\":\"keep\",\"newName\":\"b.txt\"}],"
                                + "\"globalStrategy\":\"overwrite\"}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.id").value("t-1"), jsonPath("$.data.status").value("PENDING"));
        verify(transferService).createTransfer(dtoCaptor.capture(), eq("move"), eq("u1"));
        assertEquals("move", dtoCaptor.getValue().getType());
        assertEquals("tp-1", dtoCaptor.getValue().getTargetParentId());
        assertEquals("overwrite", dtoCaptor.getValue().getGlobalStrategy());
        assertEquals(1, dtoCaptor.getValue().getItems().size());
        assertEquals("n1", dtoCaptor.getValue().getItems().get(0).getId());
        assertEquals("b.txt", dtoCaptor.getValue().getItems().get(0).getNewName());
    }

    /** POST /transfers/copy：请求体 DTO 原样透传，opType=copy，返回 R.ok 包装的传输任务。 */
    @Test
    void shouldCreateCopyTransferPassingDtoAndUser() throws Exception {
        ArgumentCaptor<FileExecuteOperationDto> dtoCaptor = ArgumentCaptor.forClass(FileExecuteOperationDto.class);
        when(transferService.createTransfer(dtoCaptor.capture(), eq("copy"), eq("u1"))).thenReturn(task("t-2", "PENDING"));
        mockMvc.perform(post("/jcloud/api/transfers/copy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"copy\",\"targetParentId\":\"tp-2\","
                                + "\"items\":[{\"id\":\"n2\",\"name\":\"b.txt\",\"strategy\":\"skip\"}],"
                                + "\"globalStrategy\":\"keep\"}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.id").value("t-2"), jsonPath("$.data.status").value("PENDING"));
        verify(transferService).createTransfer(dtoCaptor.capture(), eq("copy"), eq("u1"));
        assertEquals("copy", dtoCaptor.getValue().getType());
        assertEquals("tp-2", dtoCaptor.getValue().getTargetParentId());
        assertEquals("skip", dtoCaptor.getValue().getItems().get(0).getStrategy());
    }

    /** GET /transfers/recent：透传当前用户 ID、limit=10，返回 R.ok 包装的任务列表。 */
    @Test
    void shouldListRecentTransfers() throws Exception {
        when(transferService.listRecent("u1", 10)).thenReturn(List.of(task("t-1", "RUNNING"), task("t-2", "SUCCESS")));
        mockMvc.perform(get("/jcloud/api/transfers/recent"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data[0].id").value("t-1"), jsonPath("$.data[0].status").value("RUNNING"),
                        jsonPath("$.data[1].status").value("SUCCESS"));
        verify(transferService).listRecent(eq("u1"), eq(10));
    }

    /** GET /transfers/{id}：透传任务 ID 与用户 ID，返回 R.ok 包装的任务详情。 */
    @Test
    void shouldReturnTransferDetail() throws Exception {
        TransferTaskVo vo = task("t-1", "RUNNING");
        vo.setTotalCount(5L);
        vo.setSuccessCount(2L);
        when(transferService.getTask("t-1", "u1")).thenReturn(vo);
        mockMvc.perform(get("/jcloud/api/transfers/t-1"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.id").value("t-1"), jsonPath("$.data.status").value("RUNNING"),
                        jsonPath("$.data.totalCount").value(5), jsonPath("$.data.successCount").value(2));
        verify(transferService).getTask(eq("t-1"), eq("u1"));
    }

    /** POST /transfers/{id}/cancel：透传任务 ID 与用户 ID，返回 R.ok（data 为 null）。 */
    @Test
    void shouldCancelTransferAndReturnOk() throws Exception {
        mockMvc.perform(post("/jcloud/api/transfers/t-1/cancel"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data").value(nullValue()));
        verify(transferService).cancel(eq("t-1"), eq("u1"));
    }

    /** POST /transfers/move 请求体无法解析：GlobalExceptionHandler 包装为 R（body code=400），service 不被调用。 */
    @Test
    void shouldReturnParamErrorWhenBodyMalformed() throws Exception {
        mockMvc.perform(post("/jcloud/api/transfers/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(400),
                        jsonPath("$.msg").value("请求体格式错误"), jsonPath("$.data").value(nullValue()));
        verify(transferService, never()).createTransfer(any(), any(), any());
    }

    /** POST /transfers/move 创建时 service 抛 BusinessException：包装为 R（body code=404，HTTP 状态仍 200）。 */
    @Test
    void shouldWrapBusinessExceptionWhenCreatingMove() throws Exception {
        when(transferService.createTransfer(any(), eq("move"), eq("u1")))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "目标目录不存在"));
        mockMvc.perform(post("/jcloud/api/transfers/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"move\",\"targetParentId\":\"tp-9\",\"items\":[{\"id\":\"n1\"}]}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(404),
                        jsonPath("$.msg").value("目标目录不存在"), jsonPath("$.data").value(nullValue()));
        verify(transferService).createTransfer(any(), eq("move"), eq("u1"));
    }

    /** GET /transfers/{id} 任务不存在：service 抛 BusinessException，包装为 R（body code=404）。 */
    @Test
    void shouldWrapBusinessExceptionOnDetail() throws Exception {
        when(transferService.getTask("t-9", "u1")).thenThrow(new BusinessException(ResultCode.NOT_FOUND, "任务不存在"));
        mockMvc.perform(get("/jcloud/api/transfers/t-9"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(404),
                        jsonPath("$.msg").value("任务不存在"), jsonPath("$.data").value(nullValue()));
        verify(transferService).getTask(eq("t-9"), eq("u1"));
    }

    /** POST /transfers/{id}/cancel 取消失败：service 抛 BusinessException，包装为 R（body code=500）。 */
    @Test
    void shouldWrapBusinessExceptionOnCancel() throws Exception {
        doThrow(new BusinessException(ResultCode.BUSINESS_ERROR, "任务已处于终态，无法取消"))
                .when(transferService).cancel("t-9", "u1");
        mockMvc.perform(post("/jcloud/api/transfers/t-9/cancel"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(500),
                        jsonPath("$.msg").value("任务已处于终态，无法取消"), jsonPath("$.data").value(nullValue()));
        verify(transferService).cancel(eq("t-9"), eq("u1"));
    }
}
