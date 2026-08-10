package com.fleyx.jcloud.controller;

import com.fleyx.jcloud.common.R;
import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.enums.PreviewType;
import com.fleyx.jcloud.model.bo.BatchDownloadResult;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.bo.FileZipTask;
import com.fleyx.jcloud.model.bo.PreviewResult;
import com.fleyx.jcloud.model.dto.FileBatchDownloadDto;
import com.fleyx.jcloud.model.dto.ShareAccessDto;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.PublicShareVo;
import com.fleyx.jcloud.service.PublicShareService;
import com.fleyx.jcloud.util.ContentDispositionUtil;
import jakarta.validation.Valid;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 公开分享访问控制器。
 * 无需登录即可访问被分享的内容。
 */
@RestController
@RequestMapping(CommonConstant.API + "/s/{code}")
@RequiredArgsConstructor
public class PublicShareController {

    private final PublicShareService publicShareService;

    /**
     * 获取公开分享基础信息。
     */
    @GetMapping
    public R<PublicShareVo> getShare(@PathVariable String code) {
        return R.ok(publicShareService.getShare(code));
    }

    /**
     * 校验分享访问密码。
     */
    @PostMapping("/access")
    public R<String> validateAccess(@PathVariable String code,
                                    @Valid @RequestBody ShareAccessDto dto) {
        return R.ok(publicShareService.validateAccess(code, dto.getPassword()));
    }

    /**
     * 获取分享项列表。
     */
    @GetMapping("/items")
    public R<List<FileNodeVo>> listItems(@PathVariable String code,
                                         @RequestParam(required = false) String parentId,
                                         @RequestParam(required = false) String token) {
        return R.ok(publicShareService.listItems(code, parentId, token));
    }

    /**
     * 公开下载单个文件。
     */
    @GetMapping("/files/{fileId}/download")
    public ResponseEntity<InputStreamResource> downloadFile(@PathVariable String code,
                                                            @PathVariable String fileId,
                                                            @RequestParam(required = false) String token) {
        FileDownloadResult result = publicShareService.downloadFile(code, fileId, token);
        String contentType = result.getContentType() != null
                ? result.getContentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDispositionUtil.attachment(result.getFileName()))
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(result.getSize())
                .body(new InputStreamResource(result.getInputStream()));
    }

    /**
     * 公开预览文件。
     */
    @GetMapping("/files/{fileId}/preview")
    public ResponseEntity<?> previewFile(@PathVariable String code,
                                         @PathVariable String fileId,
                                         @RequestParam(defaultValue = "thumbnail") String type,
                                         @RequestParam(required = false) String token) throws IOException {
        PreviewType previewType = PreviewType.fromCode(type);
        if (previewType == null) {
            previewType = PreviewType.THUMBNAIL;
        }
        PreviewResult result = publicShareService.previewFile(code, fileId, previewType, token);
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
                        ContentDispositionUtil.inline(result.getFileName()))
                .contentType(MediaType.parseMediaType(result.getContentType()))
                .contentLength(result.getSize())
                .body(new InputStreamResource(result.getInputStream()));
    }

    /**
     * 公开批量下载。
     */
    @PostMapping("/batch-download")
    public R<BatchDownloadResult> downloadBatch(@PathVariable String code,
                                                @RequestBody FileBatchDownloadDto dto,
                                                @RequestParam(required = false) String token) {
        return R.ok(publicShareService.downloadBatch(code, dto, token));
    }

    /**
     * 查询批量下载任务状态。
     */
    @GetMapping("/batch-download/{taskId}/status")
    public R<FileZipTask> getBatchTask(@PathVariable String code,
                                       @PathVariable String taskId,
                                       @RequestParam(required = false) String token) {
        return R.ok(publicShareService.getBatchTask(code, taskId, token));
    }

    /**
     * 下载已完成的批量下载 ZIP。
     */
    @GetMapping("/batch-download/{taskId}")
    public ResponseEntity<InputStreamResource> downloadBatchResult(@PathVariable String code,
                                                                   @PathVariable String taskId,
                                                                   @RequestParam(required = false) String token) {
        FileDownloadResult result = publicShareService.downloadBatchResult(code, taskId, token);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDispositionUtil.attachment(result.getFileName()))
                .contentType(MediaType.parseMediaType(result.getContentType()))
                .contentLength(result.getSize())
                .body(new InputStreamResource(result.getInputStream()));
    }
}
