package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 预览文件实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_preview_file")
public class PreviewFile extends SoftDeleteEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 源文件节点 ID。
     */
    private String fileNodeId;

    /**
     * 预览类型：thumbnail / poster / text。
     */
    private String type;

    /**
     * 预览文件在系统缓存目录中的相对路径。
     */
    private String relativePath;

    /**
     * 预览文件大小（字节）。
     */
    private Long size;

    /**
     * 状态：1 启用，0 禁用。
     */
    private Integer status;
}
