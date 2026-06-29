package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文件分片上传记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_file_chunk")
public class FileChunk extends SoftDeleteEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 上传任务 ID。
     */
    private String uploadId;

    /**
     * 用户 ID。
     */
    private String userId;

    /**
     * 分片索引，从 0 开始。
     */
    private Integer chunkIndex;

    /**
     * 分片 hash（MD5）。
     */
    private String chunkHash;

    /**
     * 分片大小（字节）。
     */
    private Long size;

    /**
     * 状态：1 已上传，0 失效。
     */
    private Integer status;
}
