package com.fleyx.jcloud.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.enums.PreviewType;
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
import com.fleyx.jcloud.model.dto.FileUploadPreCheckDto;
import com.fleyx.jcloud.model.dto.FileRenameDto;
import com.fleyx.jcloud.model.vo.ChunkedUploadChunkVo;
import com.fleyx.jcloud.model.vo.ChunkedUploadInitVo;
import com.fleyx.jcloud.model.vo.ConflictItemVo;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UploadPreCheckVo;
import com.fleyx.jcloud.model.vo.FileZipTaskVo;
import com.fleyx.jcloud.model.vo.OperationResultVo;
import com.fleyx.jcloud.model.vo.RecycleRecordVo;
import com.fleyx.jcloud.service.ChunkedUploadService;
import com.fleyx.jcloud.service.FileDownloadService;
import com.fleyx.jcloud.service.FileOperationService;
import com.fleyx.jcloud.service.FilePreviewService;
import com.fleyx.jcloud.service.FileRecycleService;
import com.fleyx.jcloud.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件管理控制器。
 * 提供个人文件的上传、列表查询、下载与组织操作能力。
 */
@RestController
@RequestMapping(CommonConstant.API + "/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final FileOperationService fileOperationService;
    private final FileRecycleService fileRecycleService;
    private final FilePreviewService filePreviewService;
    private final FileDownloadService fileDownloadService;
    private final ChunkedUploadService chunkedUploadService;

    /**
     * 上传文件到当前用户根目录。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<FileNodeVo> upload(@RequestParam("file") MultipartFile file,
                                @RequestParam(required = false, defaultValue = "0") Long parentId,
                                @RequestParam(required = false) String strategy) {
        return R.ok(fileService.upload(file, UserContext.get().id(), parentId, strategy));
    }

    /**
     * 上传前预检：检查目标目录是否存在同名冲突，并返回可用于秒传的候选文件。
     */
    @PostMapping("/upload/pre-check")
    public R<UploadPreCheckVo> preCheckUpload(@RequestBody FileUploadPreCheckDto dto) {
        return R.ok(fileService.preCheckUpload(dto, UserContext.get().id()));
    }

    /**
     * 分页查询当前用户文件列表。
     */
    @GetMapping
    public R<IPage<FileNodeVo>> list(FilePageQueryDto dto) {
        if (dto.getParentId() == null) {
            dto.setParentId(0L);
        }
        return R.ok(fileService.list(dto, UserContext.get().id()));
    }

    /**
     * 秒传：复用候选文件物理数据创建新文件节点。
     */
    @PostMapping("/instant")
    public R<FileNodeVo> instantUpload(@RequestBody FileInstantUploadDto dto) {
        return R.ok(fileService.instantUpload(dto, UserContext.get().id()));
    }

    /**
     * 初始化分片上传任务。
     */
    @PostMapping("/chunked-upload/init")
    public R<ChunkedUploadInitVo> initChunkedUpload(@RequestBody ChunkedUploadInitDto dto) {
        return R.ok(chunkedUploadService.init(UserContext.get().id(), dto));
    }

    /**
     * 上传单个分片。
     */
    @PostMapping(value = "/chunked-upload/{uploadId}/chunks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ChunkedUploadChunkVo> uploadChunk(@PathVariable String uploadId,
                                               @RequestParam("index") Integer index,
                                               @RequestParam("chunkHash") String chunkHash,
                                               @RequestParam("chunk") MultipartFile chunk) {
        return R.ok(chunkedUploadService.uploadChunk(UserContext.get().id(), uploadId, index, chunk, chunkHash));
    }

    /**
     * 查询已上传的分片索引列表。
     */
    @GetMapping("/chunked-upload/{uploadId}/chunks")
    public R<List<Integer>> listUploadedChunks(@PathVariable String uploadId) {
        return R.ok(chunkedUploadService.listUploadedChunks(UserContext.get().id(), uploadId));
    }

    /**
     * 完成分片上传并创建文件节点。
     */
    @PostMapping("/chunked-upload/{uploadId}/complete")
    public R<FileNodeVo> completeChunkedUpload(@PathVariable String uploadId,
                                               @RequestBody(required = false) ChunkedUploadCompleteDto dto) {
        return R.ok(chunkedUploadService.complete(UserContext.get().id(), uploadId, dto));
    }

    /**
     * 预览指定文件。
     */
    @GetMapping("/{id}/preview")
    public ResponseEntity<?> preview(@PathVariable Long id,
                                     @RequestParam(defaultValue = "thumbnail") String type) throws IOException {
        PreviewType previewType = PreviewType.fromCode(type);
        if (previewType == null) {
            previewType = PreviewType.THUMBNAIL;
        }
        PreviewResult result = filePreviewService.preview(id, UserContext.get().id(), previewType);
        if (previewType == PreviewType.TEXT) {
            String content = new String(result.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> body = new HashMap<>();
            body.put("content", content);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + result.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(result.getContentType()))
                .contentLength(result.getSize())
                .body(new InputStreamResource(result.getInputStream()));
    }

    /**
     * 下载指定文件。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long id) {
        FileDownloadResult result = fileService.download(id, UserContext.get().id());
        String contentType = result.getContentType() != null
                ? result.getContentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(result.getSize())
                .body(new InputStreamResource(result.getInputStream()));
    }

    /**
     * 重命名文件或文件夹。
     */
    @PostMapping("/rename")
    public R<FileNodeVo> rename(@RequestBody FileRenameDto dto) {
        return R.ok(fileOperationService.rename(dto, UserContext.get().id()));
    }

    /**
     * 创建文件夹。
     */
    @PostMapping("/folders")
    public R<FileNodeVo> createFolder(@RequestBody FileCreateFolderDto dto) {
        return R.ok(fileOperationService.createFolder(dto, UserContext.get().id()));
    }

    /**
     * 移动/复制操作预检：返回目标目录下的同名冲突列表。
     */
    @PostMapping("/operations/pre-check")
    public R<List<ConflictItemVo>> preCheckOperation(@RequestBody FilePreCheckOperationDto dto) {
        return R.ok(fileOperationService.preCheckOperation(dto, UserContext.get().id()));
    }

    /**
     * 批量移动文件/文件夹。
     */
    @PostMapping("/move")
    public R<List<OperationResultVo>> move(@RequestBody FileExecuteOperationDto dto) {
        return R.ok(fileOperationService.move(dto, UserContext.get().id()));
    }

    /**
     * 批量复制文件/文件夹。
     */
    @PostMapping("/copy")
    public R<List<OperationResultVo>> copy(@RequestBody FileExecuteOperationDto dto) {
        return R.ok(fileOperationService.copy(dto, UserContext.get().id()));
    }

    /**
     * 删除文件或文件夹到回收站。
     */
    @PostMapping("/delete")
    public R<List<OperationResultVo>> deleteToTrash(@RequestBody FileDeleteDto dto) {
        return R.ok(fileRecycleService.deleteToTrash(dto, UserContext.get().id()));
    }

    /**
     * 分页查询回收站列表。
     */
    @GetMapping("/trash")
    public R<IPage<RecycleRecordVo>> listTrash(@RequestParam(defaultValue = "1") Long pageNum,
                                               @RequestParam(defaultValue = "20") Long pageSize) {
        return R.ok(fileRecycleService.listTrash(pageNum, pageSize, UserContext.get().id()));
    }

    /**
     * 恢复前冲突预检。
     */
    @PostMapping("/trash/restore/pre-check")
    public R<List<ConflictItemVo>> preCheckRestore(@RequestBody FilePreCheckRestoreDto dto) {
        return R.ok(fileRecycleService.preCheckRestore(dto, UserContext.get().id()));
    }

    /**
     * 恢复文件/文件夹。
     */
    @PostMapping("/trash/restore")
    public R<List<OperationResultVo>> restore(@RequestBody FileExecuteRestoreDto dto) {
        return R.ok(fileRecycleService.restore(dto, UserContext.get().id()));
    }

    /**
     * 永久删除回收站中的文件/文件夹。
     */
    @PostMapping("/trash/permanent-delete")
    public R<List<OperationResultVo>> permanentDelete(@RequestBody FilePermanentDeleteDto dto) {
        return R.ok(fileRecycleService.permanentDelete(dto, UserContext.get().id()));
    }

    /**
     * 批量下载文件/文件夹。
     * 低于阈值时直接流式返回 ZIP；超过阈值时返回后台任务 ID。
     */
    @PostMapping("/batch-download")
    public ResponseEntity<?> batchDownload(@RequestBody FileBatchDownloadDto dto) {
        BatchDownloadResult result = fileDownloadService.downloadBatch(dto, UserContext.get().id());
        if (result instanceof BatchDownloadResult.StreamResult streamResult) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + streamResult.fileName() + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(streamResult.totalSize())
                    .body(new InputStreamResource(streamResult.inputStream()));
        }
        BatchDownloadResult.TaskResult taskResult = (BatchDownloadResult.TaskResult) result;
        return ResponseEntity.ok().body(R.ok(toTaskVo(taskResult)));
    }

    /**
     * 查询批量下载任务状态。
     */
    @GetMapping("/batch-download/{taskId}/status")
    public R<FileZipTaskVo> batchDownloadStatus(@PathVariable String taskId) {
        FileZipTask task = fileDownloadService.getTask(taskId, UserContext.get().id());
        return R.ok(toTaskVo(task));
    }

    /**
     * 下载已完成的批量下载 ZIP。
     */
    @GetMapping("/batch-download/{taskId}")
    public ResponseEntity<InputStreamResource> downloadBatchResult(@PathVariable String taskId) {
        FileDownloadResult result = fileDownloadService.downloadTaskResult(taskId, UserContext.get().id());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(result.getSize())
                .body(new InputStreamResource(result.getInputStream()));
    }

    private FileZipTaskVo toTaskVo(BatchDownloadResult.TaskResult result) {
        FileZipTaskVo vo = new FileZipTaskVo();
        vo.setTaskId(result.taskId());
        vo.setStatus(result.status().getCode());
        return vo;
    }

    private FileZipTaskVo toTaskVo(FileZipTask task) {
        FileZipTaskVo vo = new FileZipTaskVo();
        vo.setTaskId(task.taskId());
        vo.setStatus(task.status().getCode());
        vo.setTotalBytes(task.totalBytes());
        vo.setMessage(task.message());
        return vo;
    }
}
