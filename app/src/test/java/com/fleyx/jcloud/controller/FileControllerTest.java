package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.FileZipTaskStatus;
import com.fleyx.jcloud.common.enums.PreviewType;
import com.fleyx.jcloud.common.exception.GlobalExceptionHandler;
import com.fleyx.jcloud.model.bo.BatchDownloadResult;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.bo.FileZipTask;
import com.fleyx.jcloud.model.bo.PreviewResult;
import com.fleyx.jcloud.model.dto.ChunkedUploadCompleteDto;
import com.fleyx.jcloud.model.dto.ChunkedUploadInitDto;
import com.fleyx.jcloud.model.dto.FileBatchDownloadDto;
import com.fleyx.jcloud.model.dto.FileCreateFolderDto;
import com.fleyx.jcloud.model.dto.FileDeleteDto;
import com.fleyx.jcloud.model.dto.FileExecuteOperationDto;
import com.fleyx.jcloud.model.dto.FileExecuteRestoreDto;
import com.fleyx.jcloud.model.dto.FileInstantUploadDto;
import com.fleyx.jcloud.model.dto.FilePageQueryDto;
import com.fleyx.jcloud.model.dto.FilePermanentDeleteDto;
import com.fleyx.jcloud.model.dto.FilePreCheckOperationDto;
import com.fleyx.jcloud.model.dto.FilePreCheckRestoreDto;
import com.fleyx.jcloud.model.dto.FileRenameDto;
import com.fleyx.jcloud.model.dto.FileUploadPreCheckDto;
import com.fleyx.jcloud.model.vo.BatchChunkedUploadInitItemVo;
import com.fleyx.jcloud.model.vo.BatchUploadPreCheckItemVo;
import com.fleyx.jcloud.model.vo.ChunkedUploadChunkVo;
import com.fleyx.jcloud.model.vo.ConflictItemVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import com.fleyx.jcloud.model.vo.RecycleRecordVo;
import com.fleyx.jcloud.service.ChunkedUploadService;
import com.fleyx.jcloud.service.FileDownloadService;
import com.fleyx.jcloud.service.FileOperationService;
import com.fleyx.jcloud.service.FilePreviewService;
import com.fleyx.jcloud.service.FileRecycleService;
import com.fleyx.jcloud.service.FileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件管理控制器单元测试（上传/列表/分片/预览/下载/组织操作/回收站/批量下载，参数透传与响应包装）。
 */
class FileControllerTest {

    private final FileService fileService = mock(FileService.class);
    private final FileOperationService fileOperationService = mock(FileOperationService.class);
    private final FileRecycleService fileRecycleService = mock(FileRecycleService.class);
    private final FilePreviewService filePreviewService = mock(FilePreviewService.class);
    private final FileDownloadService fileDownloadService = mock(FileDownloadService.class);
    private final ChunkedUploadService chunkedUploadService = mock(ChunkedUploadService.class);

    private final FileController controller = new FileController(fileService, fileOperationService,
            fileRecycleService, filePreviewService, fileDownloadService, chunkedUploadService);

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

    private static FileNodeVo node(String id, String name) {
        FileNodeVo vo = new FileNodeVo();
        vo.setId(id);
        vo.setName(name);
        return vo;
    }

    private static <T> IPage<T> pageOf(T record) {
        Page<T> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(record));
        return page;
    }

    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<List<T>> listCaptor() {
        return (ArgumentCaptor<List<T>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
    }

    /** POST /files/upload：multipart 文件透传，缺省 parentId 为根目录、strategy 为 null，返回 R.ok 包装节点。 */
    @Test
    void shouldUploadFileToRootByDefault() throws Exception {
        ArgumentCaptor<MultipartFile> fileCaptor = ArgumentCaptor.forClass(MultipartFile.class);
        when(fileService.upload(fileCaptor.capture(), eq("u1"), eq(FileNodeConstants.ROOT_ID), isNull()))
                .thenReturn(node("n1", "a.txt"));
        mockMvc.perform(multipart("/jcloud/api/files/upload")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8))))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data.id").value("n1"));
        verify(fileService).upload(fileCaptor.capture(), eq("u1"), eq(FileNodeConstants.ROOT_ID), isNull());
        assertEquals("a.txt", fileCaptor.getValue().getOriginalFilename());
    }
    /** POST /files/upload/pre-check：items 列表反序列化并原样透传。 */
    @Test
    void shouldPreCheckUploadPassingItems() throws Exception {
        ArgumentCaptor<List<FileUploadPreCheckDto>> itemsCaptor = listCaptor();
        BatchUploadPreCheckItemVo vo = new BatchUploadPreCheckItemVo();
        vo.setClientFileId("f1");
        vo.setStatus("success");
        when(fileService.preCheckUpload(itemsCaptor.capture(), eq("u1"))).thenReturn(List.of(vo));
        mockMvc.perform(post("/jcloud/api/files/upload/pre-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"clientFileId\":\"f1\",\"fileName\":\"a.txt\",\"size\":1024,"
                                + "\"parentId\":\"p1\",\"relativePath\":\"dir/a.txt\",\"partialHash\":\"h1\"}]}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data[0].clientFileId").value("f1"));
        verify(fileService).preCheckUpload(itemsCaptor.capture(), eq("u1"));
        assertEquals("dir/a.txt", itemsCaptor.getValue().get(0).getRelativePath());
        assertEquals("h1", itemsCaptor.getValue().get(0).getPartialHash());
    }

    /** GET /files：parentId 缺省为根目录、分页为默认值，返回 R.ok 包装的分页结果。 */
    @Test
    void shouldListFilesUsingRootAndDefaultPage() throws Exception {
        ArgumentCaptor<FilePageQueryDto> dtoCaptor = ArgumentCaptor.forClass(FilePageQueryDto.class);
        when(fileService.list(dtoCaptor.capture(), eq("u1"))).thenReturn(pageOf(node("n1", "报告.docx")));
        mockMvc.perform(get("/jcloud/api/files"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data.records[0].name").value("报告.docx"));
        verify(fileService).list(dtoCaptor.capture(), eq("u1"));
        assertEquals(FileNodeConstants.ROOT_ID, dtoCaptor.getValue().getParentId());
        assertEquals(1L, dtoCaptor.getValue().getPageNum());
        assertEquals(20L, dtoCaptor.getValue().getPageSize());
    }
    /** POST /files/instant：秒传 DTO 全字段反序列化并原样透传。 */
    @Test
    void shouldInstantUploadPassingDto() throws Exception {
        ArgumentCaptor<FileInstantUploadDto> dtoCaptor = ArgumentCaptor.forClass(FileInstantUploadDto.class);
        when(fileService.instantUpload(dtoCaptor.capture(), eq("u1"))).thenReturn(node("n3", "a.txt"));
        mockMvc.perform(post("/jcloud/api/files/instant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"c1\",\"fullHash\":\"h1\",\"fileName\":\"a.txt\","
                                + "\"parentId\":\"p1\",\"relativePath\":\"dir/a.txt\",\"strategy\":\"overwrite\"}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data.id").value("n3"));
        verify(fileService).instantUpload(dtoCaptor.capture(), eq("u1"));
        assertEquals("c1", dtoCaptor.getValue().getCandidateId());
        assertEquals("dir/a.txt", dtoCaptor.getValue().getRelativePath());
        assertEquals("overwrite", dtoCaptor.getValue().getStrategy());
    }
    /** GET /files/folders：parentId 参数透传，返回 R.ok 包装的子文件夹列表。 */
    @Test
    void shouldListChildFoldersPassingParentId() throws Exception {
        when(fileService.listChildFolders("p2", "u1")).thenReturn(List.of(node("f1", "子目录")));
        mockMvc.perform(get("/jcloud/api/files/folders").param("parentId", "p2"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data[0].name").value("子目录"));
        verify(fileService).listChildFolders(eq("p2"), eq("u1"));
    }

    /** POST /files/chunked-upload/init：items 列表反序列化并原样透传。 */
    @Test
    void shouldInitChunkedUploadPassingItems() throws Exception {
        ArgumentCaptor<List<ChunkedUploadInitDto>> itemsCaptor = listCaptor();
        BatchChunkedUploadInitItemVo vo = new BatchChunkedUploadInitItemVo();
        vo.setClientFileId("f1");
        vo.setStatus("success");
        when(chunkedUploadService.init(eq("u1"), itemsCaptor.capture())).thenReturn(List.of(vo));
        mockMvc.perform(post("/jcloud/api/files/chunked-upload/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"clientFileId\":\"f1\",\"fileName\":\"big.bin\",\"size\":2048,"
                                + "\"parentId\":\"p1\",\"relativePath\":\"dir/big.bin\"}]}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data[0].status").value("success"));
        verify(chunkedUploadService).init(eq("u1"), itemsCaptor.capture());
        assertEquals("dir/big.bin", itemsCaptor.getValue().get(0).getRelativePath());
    }

    /** POST /files/chunked-upload/{uploadId}/chunks：分片文件、索引与 hash 透传。 */
    @Test
    void shouldUploadChunkPassingIndexHashAndChunk() throws Exception {
        ArgumentCaptor<MultipartFile> chunkCaptor = ArgumentCaptor.forClass(MultipartFile.class);
        ChunkedUploadChunkVo chunkResult = new ChunkedUploadChunkVo();
        chunkResult.setChunkIndex(0);
        chunkResult.setStatus("success");
        when(chunkedUploadService.uploadChunk(eq("u1"), eq("up1"), eq(0), chunkCaptor.capture(), eq("h1")))
                .thenReturn(chunkResult);
        mockMvc.perform(multipart("/jcloud/api/files/chunked-upload/up1/chunks")
                        .file(new MockMultipartFile("chunk", "part0", "application/octet-stream", new byte[]{1, 2}))
                        .param("index", "0")
                        .param("chunkHash", "h1"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.chunkIndex").value(0), jsonPath("$.data.status").value("success"));
        verify(chunkedUploadService).uploadChunk(eq("u1"), eq("up1"), eq(0), chunkCaptor.capture(), eq("h1"));
        assertEquals("part0", chunkCaptor.getValue().getOriginalFilename());
    }

    /** GET /files/chunked-upload/{uploadId}/chunks：路径变量透传，返回已上传分片索引列表。 */
    @Test
    void shouldListUploadedChunksByUploadId() throws Exception {
        when(chunkedUploadService.listUploadedChunks("u1", "up1")).thenReturn(List.of(0, 1, 2));
        mockMvc.perform(get("/jcloud/api/files/chunked-upload/up1/chunks"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data[0]").value(0));
        verify(chunkedUploadService).listUploadedChunks(eq("u1"), eq("up1"));
    }

    /** POST /files/chunked-upload/{uploadId}/complete：完成 DTO（冲突策略）反序列化并透传。 */
    @Test
    void shouldCompleteChunkedUploadPassingStrategy() throws Exception {
        ArgumentCaptor<ChunkedUploadCompleteDto> dtoCaptor = ArgumentCaptor.forClass(ChunkedUploadCompleteDto.class);
        when(chunkedUploadService.complete(eq("u1"), eq("up1"), dtoCaptor.capture())).thenReturn(node("n9", "merged.bin"));
        mockMvc.perform(post("/jcloud/api/files/chunked-upload/up1/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\":\"overwrite\"}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data.id").value("n9"));
        verify(chunkedUploadService).complete(eq("u1"), eq("up1"), dtoCaptor.capture());
        assertEquals("overwrite", dtoCaptor.getValue().getStrategy());
    }

    /** GET /files/{id}/preview 缺省 type=thumbnail：inline 头与流 body。 */
    @Test
    void shouldPreviewThumbnailWithInlineHeaders() throws Exception {
        PreviewResult result = new PreviewResult("cover.jpg", "image/jpeg", 100L,
                new ByteArrayInputStream("thumb".getBytes(StandardCharsets.UTF_8)));
        when(filePreviewService.preview("f1", "u1", PreviewType.THUMBNAIL)).thenReturn(result);
        mockMvc.perform(get("/jcloud/api/files/f1/preview"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "inline; filename=\"cover.jpg\"; filename*=UTF-8''cover.jpg"))
                .andExpectAll(header().string("Content-Type", "image/jpeg"), header().string("Content-Length", "100"))
                .andExpect(content().string("thumb"));
        verify(filePreviewService).preview(eq("f1"), eq("u1"), eq(PreviewType.THUMBNAIL));
    }

    /** GET /files/{id}/preview?type=text：文本预览返回 JSON body 含 content 字段。 */
    @Test
    void shouldPreviewTextAsJsonBody() throws Exception {
        PreviewResult result = new PreviewResult("notes.txt", "text/plain", 5L,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(filePreviewService.preview("f1", "u1", PreviewType.TEXT)).thenReturn(result);
        mockMvc.perform(get("/jcloud/api/files/f1/preview").param("type", "text"))
                .andExpectAll(status().isOk(), content().contentType(MediaType.APPLICATION_JSON), jsonPath("$.content").value("hello"));
        verify(filePreviewService).preview(eq("f1"), eq("u1"), eq(PreviewType.TEXT));
    }

    /** GET /files/{id}/download：attachment 头、Content-Length/Content-Type 与流 body。 */
    @Test
    void shouldDownloadWithAttachmentHeaders() throws Exception {
        FileDownloadResult result = new FileDownloadResult("photo.png",
                new ByteArrayInputStream("hello-photo".getBytes(StandardCharsets.UTF_8)), "image/png", 1024L);
        when(fileService.download("f1", "u1")).thenReturn(result);
        mockMvc.perform(get("/jcloud/api/files/f1/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"photo.png\"; filename*=UTF-8''photo.png"))
                .andExpectAll(header().string("Content-Type", "image/png"), header().string("Content-Length", "1024"))
                .andExpect(content().string("hello-photo"));
        verify(fileService).download(eq("f1"), eq("u1"));
    }

    /** POST /files/rename：id/newName 反序列化并透传，返回 R.ok 包装的重命名节点。 */
    @Test
    void shouldRenamePassingDto() throws Exception {
        ArgumentCaptor<FileRenameDto> dtoCaptor = ArgumentCaptor.forClass(FileRenameDto.class);
        when(fileOperationService.rename(dtoCaptor.capture(), eq("u1"))).thenReturn(node("n1", "b.txt"));
        mockMvc.perform(post("/jcloud/api/files/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"n1\",\"newName\":\"b.txt\"}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data.name").value("b.txt"));
        verify(fileOperationService).rename(dtoCaptor.capture(), eq("u1"));
        assertEquals("b.txt", dtoCaptor.getValue().getNewName());
    }

    /** POST /files/folders：parentId/name 反序列化并透传，返回 R.ok 包装的文件夹节点。 */
    @Test
    void shouldCreateFolderPassingDto() throws Exception {
        ArgumentCaptor<FileCreateFolderDto> dtoCaptor = ArgumentCaptor.forClass(FileCreateFolderDto.class);
        when(fileOperationService.createFolder(dtoCaptor.capture(), eq("u1"))).thenReturn(node("f2", "new-dir"));
        mockMvc.perform(post("/jcloud/api/files/folders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"p1\",\"name\":\"new-dir\"}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data.id").value("f2"));
        verify(fileOperationService).createFolder(dtoCaptor.capture(), eq("u1"));
        assertEquals("new-dir", dtoCaptor.getValue().getName());
    }

    /** POST /files/operations/pre-check：type/targetParentId/嵌套 items 反序列化并透传。 */
    @Test
    void shouldPreCheckOperationPassingDto() throws Exception {
        ArgumentCaptor<FilePreCheckOperationDto> dtoCaptor = ArgumentCaptor.forClass(FilePreCheckOperationDto.class);
        ConflictItemVo conflict = new ConflictItemVo();
        conflict.setSourceId("n1");
        conflict.setExistingName("a.txt");
        when(fileOperationService.preCheckOperation(dtoCaptor.capture(), eq("u1"))).thenReturn(List.of(conflict));
        mockMvc.perform(post("/jcloud/api/files/operations/pre-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"move\",\"targetParentId\":\"tp\","
                                + "\"items\":[{\"id\":\"n1\",\"name\":\"a.txt\",\"strategy\":\"keep\"}]}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data[0].sourceId").value("n1"));
        verify(fileOperationService).preCheckOperation(dtoCaptor.capture(), eq("u1"));
        assertEquals("n1", dtoCaptor.getValue().getItems().get(0).getId());
    }

    /** POST /files/move：执行 DTO（嵌套 items 与全局策略）反序列化并透传。 */
    @Test
    void shouldMovePassingDtoWithItemsAndStrategy() throws Exception {
        ArgumentCaptor<FileExecuteOperationDto> dtoCaptor = ArgumentCaptor.forClass(FileExecuteOperationDto.class);
        OperationResultVo result = new OperationResultVo();
        result.setSourceId("n1");
        result.setStatus("success");
        when(fileOperationService.move(dtoCaptor.capture(), eq("u1"))).thenReturn(List.of(result));
        mockMvc.perform(post("/jcloud/api/files/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"move\",\"targetParentId\":\"tp\","
                                + "\"items\":[{\"id\":\"n1\",\"name\":\"a.txt\",\"strategy\":\"keep\",\"newName\":\"c.txt\"}],"
                                + "\"globalStrategy\":\"overwrite\"}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data[0].status").value("success"));
        verify(fileOperationService).move(dtoCaptor.capture(), eq("u1"));
        assertEquals("move", dtoCaptor.getValue().getType());
        assertEquals("c.txt", dtoCaptor.getValue().getItems().get(0).getNewName());
        assertEquals("overwrite", dtoCaptor.getValue().getGlobalStrategy());
    }

    /** POST /files/copy：执行 DTO 反序列化并透传，走 copy 服务。 */
    @Test
    void shouldCopyPassingDtoWithItemsAndStrategy() throws Exception {
        ArgumentCaptor<FileExecuteOperationDto> dtoCaptor = ArgumentCaptor.forClass(FileExecuteOperationDto.class);
        OperationResultVo result = new OperationResultVo();
        result.setSourceId("n1");
        result.setStatus("success");
        when(fileOperationService.copy(dtoCaptor.capture(), eq("u1"))).thenReturn(List.of(result));
        mockMvc.perform(post("/jcloud/api/files/copy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"copy\",\"targetParentId\":\"tp\","
                                + "\"items\":[{\"id\":\"n1\",\"name\":\"a.txt\"}],\"globalStrategy\":\"keep\"}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data[0].status").value("success"));
        verify(fileOperationService).copy(dtoCaptor.capture(), eq("u1"));
        assertEquals("copy", dtoCaptor.getValue().getType());
    }

    /** POST /files/delete：ids 列表反序列化并透传，走回收站删除服务。 */
    @Test
    void shouldDeleteToTrashPassingIds() throws Exception {
        ArgumentCaptor<FileDeleteDto> dtoCaptor = ArgumentCaptor.forClass(FileDeleteDto.class);
        OperationResultVo result = new OperationResultVo();
        result.setSourceId("n1");
        result.setStatus("success");
        when(fileRecycleService.deleteToTrash(dtoCaptor.capture(), eq("u1"))).thenReturn(List.of(result));
        mockMvc.perform(post("/jcloud/api/files/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"n1\",\"n2\"]}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data[0].sourceId").value("n1"));
        verify(fileRecycleService).deleteToTrash(dtoCaptor.capture(), eq("u1"));
        assertEquals(2, dtoCaptor.getValue().getIds().size());
    }

    /** GET /files/trash：pageNum/pageSize 绑定并透传，返回 R.ok 包装的回收站分页。 */
    @Test
    void shouldListTrashBindingPageParams() throws Exception {
        RecycleRecordVo record = new RecycleRecordVo();
        record.setId("r1");
        record.setName("旧文件.txt");
        when(fileRecycleService.listTrash(2L, 50L, "u1")).thenReturn(pageOf(record));
        mockMvc.perform(get("/jcloud/api/files/trash").param("pageNum", "2").param("pageSize", "50"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data.records[0].name").value("旧文件.txt"));
        verify(fileRecycleService).listTrash(eq(2L), eq(50L), eq("u1"));
    }

    /** POST /files/trash/restore/pre-check：ids 列表反序列化并透传，返回冲突列表。 */
    @Test
    void shouldPreCheckRestorePassingIds() throws Exception {
        ArgumentCaptor<FilePreCheckRestoreDto> dtoCaptor = ArgumentCaptor.forClass(FilePreCheckRestoreDto.class);
        ConflictItemVo conflict = new ConflictItemVo();
        conflict.setSourceId("r1");
        conflict.setExistingName("同名.txt");
        when(fileRecycleService.preCheckRestore(dtoCaptor.capture(), eq("u1"))).thenReturn(List.of(conflict));
        mockMvc.perform(post("/jcloud/api/files/trash/restore/pre-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"r1\"]}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data[0].sourceId").value("r1"));
        verify(fileRecycleService).preCheckRestore(dtoCaptor.capture(), eq("u1"));
        assertEquals("r1", dtoCaptor.getValue().getIds().get(0));
    }

    /** POST /files/trash/restore：恢复项列表与全局策略反序列化并透传。 */
    @Test
    void shouldRestorePassingDtoWithItemsAndStrategy() throws Exception {
        ArgumentCaptor<FileExecuteRestoreDto> dtoCaptor = ArgumentCaptor.forClass(FileExecuteRestoreDto.class);
        OperationResultVo result = new OperationResultVo();
        result.setSourceId("r1");
        result.setStatus("success");
        when(fileRecycleService.restore(dtoCaptor.capture(), eq("u1"))).thenReturn(List.of(result));
        mockMvc.perform(post("/jcloud/api/files/trash/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":\"r1\",\"strategy\":\"overwrite\"}],\"globalStrategy\":\"overwrite\"}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data[0].status").value("success"));
        verify(fileRecycleService).restore(dtoCaptor.capture(), eq("u1"));
        assertEquals("overwrite", dtoCaptor.getValue().getItems().get(0).getStrategy());
    }

    /** POST /files/trash/permanent-delete：ids 列表反序列化并透传。 */
    @Test
    void shouldPermanentDeletePassingIds() throws Exception {
        ArgumentCaptor<FilePermanentDeleteDto> dtoCaptor = ArgumentCaptor.forClass(FilePermanentDeleteDto.class);
        OperationResultVo result = new OperationResultVo();
        result.setSourceId("r1");
        result.setStatus("success");
        when(fileRecycleService.permanentDelete(dtoCaptor.capture(), eq("u1"))).thenReturn(List.of(result));
        mockMvc.perform(post("/jcloud/api/files/trash/permanent-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"r1\"]}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data[0].sourceId").value("r1"));
        verify(fileRecycleService).permanentDelete(dtoCaptor.capture(), eq("u1"));
        assertEquals("r1", dtoCaptor.getValue().getIds().get(0));
    }

    /** POST /files/batch-download 低于阈值：流式返回 ZIP（attachment 头 + octet-stream + Content-Length）。 */
    @Test
    void shouldBatchDownloadStreamZip() throws Exception {
        ArgumentCaptor<FileBatchDownloadDto> dtoCaptor = ArgumentCaptor.forClass(FileBatchDownloadDto.class);
        when(fileDownloadService.downloadBatch(dtoCaptor.capture(), eq("u1")))
                .thenReturn(new BatchDownloadResult.StreamResult("files.zip",
                        new ByteArrayInputStream("zip".getBytes(StandardCharsets.UTF_8)), 2048L));
        mockMvc.perform(post("/jcloud/api/files/batch-download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"n1\",\"n2\"]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"files.zip\"; filename*=UTF-8''files.zip"))
                .andExpectAll(header().string("Content-Type", "application/octet-stream"), header().string("Content-Length", "2048"))
                .andExpect(content().string("zip"));
        verify(fileDownloadService).downloadBatch(dtoCaptor.capture(), eq("u1"));
        assertEquals(2, dtoCaptor.getValue().getIds().size());
    }

    /** POST /files/batch-download 超过阈值：返回 R.ok 包装的后台任务（taskId + 小写状态码）。 */
    @Test
    void shouldBatchDownloadReturnTaskResult() throws Exception {
        ArgumentCaptor<FileBatchDownloadDto> dtoCaptor = ArgumentCaptor.forClass(FileBatchDownloadDto.class);
        when(fileDownloadService.downloadBatch(dtoCaptor.capture(), eq("u1")))
                .thenReturn(new BatchDownloadResult.TaskResult("task-1", FileZipTaskStatus.PENDING));
        mockMvc.perform(post("/jcloud/api/files/batch-download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"n1\"]}"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200),
                        jsonPath("$.data.taskId").value("task-1"), jsonPath("$.data.status").value("pending"));
        verify(fileDownloadService).downloadBatch(dtoCaptor.capture(), eq("u1"));
    }

    /** GET /files/batch-download/{taskId}/status：透传 taskId，返回任务视图。 */
    @Test
    void shouldGetBatchTaskStatus() throws Exception {
        FileZipTask task = new FileZipTask("task-1", "u1", "u1", FileZipTaskStatus.RUNNING, null, 2048L, "打包中", null);
        when(fileDownloadService.getTask("task-1", "u1")).thenReturn(task);
        mockMvc.perform(get("/jcloud/api/files/batch-download/task-1/status"))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(200), jsonPath("$.data.taskId").value("task-1"),
                        jsonPath("$.data.status").value("running"), jsonPath("$.data.totalBytes").value(2048),
                        jsonPath("$.data.message").value("打包中"));
        verify(fileDownloadService).getTask(eq("task-1"), eq("u1"));
    }

    /** GET /files/batch-download/{taskId}：下载已生成 ZIP，attachment 头与流 body。 */
    @Test
    void shouldDownloadBatchTaskResult() throws Exception {
        FileDownloadResult result = new FileDownloadResult("archive.zip",
                new ByteArrayInputStream("zip".getBytes(StandardCharsets.UTF_8)), "application/zip", 2048L);
        when(fileDownloadService.downloadTaskResult("task-1", "u1")).thenReturn(result);
        mockMvc.perform(get("/jcloud/api/files/batch-download/task-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"archive.zip\"; filename*=UTF-8''archive.zip"))
                .andExpectAll(header().string("Content-Type", "application/octet-stream"), content().string("zip"));
        verify(fileDownloadService).downloadTaskResult(eq("task-1"), eq("u1"));
    }

    /** POST /files/chunked-upload/{uploadId}/chunks 缺 index：GlobalExceptionHandler 包装为 code=400。 */
    @Test
    void shouldReturnParamErrorWhenIndexMissing() throws Exception {
        mockMvc.perform(multipart("/jcloud/api/files/chunked-upload/up1/chunks")
                        .file(new MockMultipartFile("chunk", "part0", "application/octet-stream", new byte[]{1})))
                .andExpectAll(status().isOk(), jsonPath("$.code").value(400), jsonPath("$.msg").value("缺少请求参数：index"));
    }
}
