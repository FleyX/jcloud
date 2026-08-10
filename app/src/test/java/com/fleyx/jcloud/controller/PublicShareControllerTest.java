package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.enums.FileZipTaskStatus;
import com.fleyx.jcloud.common.enums.PreviewType;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.bo.BatchDownloadResult;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.bo.FileZipTask;
import com.fleyx.jcloud.model.bo.PreviewResult;
import com.fleyx.jcloud.model.dto.FileBatchDownloadDto;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.PublicShareVo;
import com.fleyx.jcloud.service.PublicShareService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公开分享控制器单元测试（无需登录的分享路由、参数透传与响应包装）。
 */
class PublicShareControllerTest {

    private final PublicShareService publicShareService = mock(PublicShareService.class);

    private final PublicShareController controller = new PublicShareController(publicShareService);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    /**
     * GET /jcloud/api/s/{code} 返回 R.ok 包装：code=200，data 为分享视图字段。
     */
    @Test
    void shouldReturnShareInfo() throws Exception {
        PublicShareVo vo = new PublicShareVo();
        vo.setId("share-1");
        vo.setName("测试分享");
        vo.setHasPassword(false);
        vo.setItems(List.of());
        when(publicShareService.getShare("c1")).thenReturn(vo);

        mockMvc.perform(get("/jcloud/api/s/c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("share-1"))
                .andExpect(jsonPath("$.data.name").value("测试分享"))
                .andExpect(jsonPath("$.data.hasPassword").value(false))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        verify(publicShareService).getShare(eq("c1"));
    }

    /**
     * POST /{code}/access 校验密码：service 收到 (code, password)，返回 token 放入 data。
     */
    @Test
    void shouldValidateAccessPassword() throws Exception {
        when(publicShareService.validateAccess("c1", "pw")).thenReturn("token-abc");

        mockMvc.perform(post("/jcloud/api/s/c1/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("token-abc"));

        verify(publicShareService).validateAccess(eq("c1"), eq("pw"));
    }

    /**
     * GET /{code}/items 透传 parentId 与 token 到 service，返回文件节点列表。
     */
    @Test
    void shouldListItemsWithParentIdAndToken() throws Exception {
        FileNodeVo node = new FileNodeVo();
        node.setId("n1");
        node.setName("报告.docx");
        node.setType("file");
        when(publicShareService.listItems("c1", "p", "t")).thenReturn(List.of(node));

        mockMvc.perform(get("/jcloud/api/s/c1/items")
                        .param("parentId", "p")
                        .param("token", "t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value("n1"))
                .andExpect(jsonPath("$.data[0].name").value("报告.docx"))
                .andExpect(jsonPath("$.data[0].type").value("file"));

        verify(publicShareService).listItems(eq("c1"), eq("p"), eq("t"));
    }

    /**
     * GET /{code}/files/{fileId}/download 透传 token：Content-Disposition attachment、
     * Content-Length 与 Content-Type 均取自 result。
     */
    @Test
    void shouldDownloadFileWithAttachmentHeaders() throws Exception {
        FileDownloadResult result = new FileDownloadResult(
                "photo.png",
                new ByteArrayInputStream("hello-photo".getBytes(StandardCharsets.UTF_8)),
                "image/png",
                1024L);
        when(publicShareService.downloadFile("c1", "f1", "t")).thenReturn(result);

        mockMvc.perform(get("/jcloud/api/s/c1/files/f1/download").param("token", "t"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"photo.png\"; filename*=UTF-8''photo.png"))
                .andExpect(header().string("Content-Length", "1024"))
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().string("hello-photo"));

        verify(publicShareService).downloadFile(eq("c1"), eq("f1"), eq("t"));
    }

    /**
     * GET 下载：result.getContentType() 为 null 时 Content-Type 回退 application/octet-stream。
     */
    @Test
    void shouldFallbackDownloadContentTypeToOctetStream() throws Exception {
        FileDownloadResult result = new FileDownloadResult(
                "data.bin",
                new ByteArrayInputStream(new byte[]{1, 2, 3}),
                null,
                3L);
        when(publicShareService.downloadFile("c1", "f1", null)).thenReturn(result);

        mockMvc.perform(get("/jcloud/api/s/c1/files/f1/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"data.bin\"; filename*=UTF-8''data.bin"))
                .andExpect(header().string("Content-Length", "3"))
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));

        verify(publicShareService).downloadFile(eq("c1"), eq("f1"), isNull());
    }

    /**
     * GET /{code}/files/{fileId}/preview?type=text：返回 JSON body 含 content 字段（文本内容）。
     */
    @Test
    void shouldPreviewTextAsJsonBody() throws Exception {
        PreviewResult result = new PreviewResult(
                "notes.txt",
                "text/plain",
                5L,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(publicShareService.previewFile("c1", "f1", PreviewType.TEXT, null)).thenReturn(result);

        mockMvc.perform(get("/jcloud/api/s/c1/files/f1/preview").param("type", "text"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").value("hello"));

        verify(publicShareService).previewFile(eq("c1"), eq("f1"), eq(PreviewType.TEXT), isNull());
    }

    /**
     * GET 预览 type 为非法值：回退 THUMBNAIL 并走图片缩略图响应（inline 头），token 仍透传。
     */
    @Test
    void shouldFallbackInvalidPreviewTypeToThumbnail() throws Exception {
        PreviewResult result = new PreviewResult(
                "cover.jpg",
                "image/jpeg",
                100L,
                new ByteArrayInputStream("thumb".getBytes(StandardCharsets.UTF_8)));
        when(publicShareService.previewFile("c1", "f1", PreviewType.THUMBNAIL, "tok")).thenReturn(result);

        mockMvc.perform(get("/jcloud/api/s/c1/files/f1/preview")
                        .param("type", "bogus")
                        .param("token", "tok"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "inline; filename=\"cover.jpg\"; filename*=UTF-8''cover.jpg"))
                .andExpect(header().string("Content-Type", "image/jpeg"));

        verify(publicShareService).previewFile(eq("c1"), eq("f1"), eq(PreviewType.THUMBNAIL), eq("tok"));
    }

    /**
     * GET 预览 type=thumbnail：inline Content-Disposition 与 result 的 Content-Type/Content-Length。
     */
    @Test
    void shouldPreviewThumbnailWithInlineHeaders() throws Exception {
        PreviewResult result = new PreviewResult(
                "cover.jpg",
                "image/jpeg",
                100L,
                new ByteArrayInputStream("thumb".getBytes(StandardCharsets.UTF_8)));
        when(publicShareService.previewFile("c1", "f1", PreviewType.THUMBNAIL, null)).thenReturn(result);

        mockMvc.perform(get("/jcloud/api/s/c1/files/f1/preview").param("type", "thumbnail"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "inline; filename=\"cover.jpg\"; filename*=UTF-8''cover.jpg"))
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Content-Length", "100"));

        verify(publicShareService).previewFile(eq("c1"), eq("f1"), eq(PreviewType.THUMBNAIL), isNull());
    }

    /**
     * POST /{code}/batch-download 透传请求体 DTO 与 token，返回 R.ok 包装的批量下载结果。
     */
    @Test
    void shouldCreateBatchDownloadTask() throws Exception {
        FileBatchDownloadDto expectedDto = new FileBatchDownloadDto();
        expectedDto.setIds(List.of("f1", "f2"));
        when(publicShareService.downloadBatch("c1", expectedDto, "t"))
                .thenReturn(new BatchDownloadResult.TaskResult("task-1", FileZipTaskStatus.PENDING));

        mockMvc.perform(post("/jcloud/api/s/c1/batch-download")
                        .param("token", "t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"f1\",\"f2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").value("task-1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        verify(publicShareService).downloadBatch(eq("c1"), eq(expectedDto), eq("t"));
    }

    /**
     * GET /{code}/batch-download/{taskId}/status：透传 code/taskId/token，返回 R.ok 包装的任务对象。
     */
    @Test
    void shouldGetBatchTaskStatus() throws Exception {
        FileZipTask task = new FileZipTask(
                "task-1", null, null, FileZipTaskStatus.RUNNING, null, 2048L, "打包中", null);
        when(publicShareService.getBatchTask("c1", "task1", "t")).thenReturn(task);

        mockMvc.perform(get("/jcloud/api/s/c1/batch-download/task1/status").param("token", "t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").value("task-1"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.totalBytes").value(2048));

        verify(publicShareService).getBatchTask(eq("c1"), eq("task1"), eq("t"));
    }

    /**
     * GET /{code}/batch-download/{taskId}：透传 code/taskId/token，返回 attachment 下载响应头。
     */
    @Test
    void shouldDownloadBatchTaskResult() throws Exception {
        FileDownloadResult result = new FileDownloadResult(
                "archive.zip",
                new ByteArrayInputStream("zip".getBytes(StandardCharsets.UTF_8)),
                "application/zip",
                2048L);
        when(publicShareService.downloadBatchResult("c1", "task1", "t")).thenReturn(result);

        mockMvc.perform(get("/jcloud/api/s/c1/batch-download/task1").param("token", "t"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"archive.zip\"; filename*=UTF-8''archive.zip"))
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Length", "2048"))
                .andExpect(content().string("zip"));

        verify(publicShareService).downloadBatchResult(eq("c1"), eq("task1"), eq("t"));
    }

    /**
     * service 抛 BusinessException：经 GlobalExceptionHandler 包装为 R（body code=404，HTTP 状态仍 200）。
     */
    @Test
    void shouldWrapBusinessExceptionFromService() throws Exception {
        when(publicShareService.getShare("c1"))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "分享不存在"));

        mockMvc.perform(get("/jcloud/api/s/c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("分享不存在"));

        verify(publicShareService).getShare(eq("c1"));
    }
}
