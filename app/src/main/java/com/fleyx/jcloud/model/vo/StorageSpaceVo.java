package com.fleyx.jcloud.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 存储空间视图。
 */
@Data
public class StorageSpaceVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * ID。
     */
    private String id;

    /**
     * 名称。
     */
    private String name;

    /**
     * 物理路径。
     */
    private String path;

    /**
     * 容量（字节）。
     */
    private Long capacity;

    /**
     * 已用空间（字节）。
     */
    private Long usedSpace;

    /**
     * 剩余空间（字节）。
     */
    private Long freeSpace;

    /**
     * 是否主存储空间：1 是，0 否。
     */
    private Integer isPrimary;

    /**
     * 状态：1 启用，0 禁用。
     */
    private Integer status;

    /**
     * 备注。
     */
    private String remark;

    /**
     * 创建时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
