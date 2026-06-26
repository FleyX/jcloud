package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 初始化分片上传响应。
 */
@Data
public class ChunkedUploadInitVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 上传任务 ID。
     */
    private String uploadId;

    /**
     * 分片大小（字节）。
     */
    private Integer chunkSize;

    /**
     * 总分片数。
     */
    private Integer totalChunks;
}
