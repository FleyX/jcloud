package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分片上传响应。
 */
@Data
public class ChunkedUploadChunkVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分片索引。
     */
    private Integer chunkIndex;

    /**
     * 状态：success / failed。
     */
    private String status;
}
