package com.fleyx.jcloud.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 回收站记录视图。
 */
@Data
public class RecycleRecordVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * ID。
     */
    private String id;

    /**
     * 节点名称。
     */
    private String name;

    /**
     * 类型：file / folder。
     */
    private String type;

    /**
     * 原始路径快照（节点自身完整虚拟路径）。
     */
    private String originalPathName;

    /**
     * 子树总字节数。
     */
    private Long totalSize;

    /**
     * 创建时间（即删除时间）。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
