package com.fleyx.jcloud.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件节点视图。
 */
@Data
public class FileNodeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * ID。
     */
    private Long id;

    /**
     * 用户 ID。
     */
    private Long userId;

    /**
     * 父节点 ID。
     */
    private Long parentId;

    /**
     * 名称。
     */
    private String name;

    /**
     * 类型：file / folder。
     */
    private String type;

    /**
     * 大小（字节）。
     */
    private String size;

    /**
     * 文件完整 hash。
     */
    private String hash;

    /**
     * 存储空间 ID。
     */
    private Long storageSpaceId;

    /**
     * 虚拟路径快照。
     */
    private String pathName;

    /**
     * MIME 类型。
     */
    private String mimeType;

    /**
     * 状态。
     */
    private Integer status;

    /**
     * 物理相对路径（调试用，生产可移除）。
     */
    private String physicalPath;

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
