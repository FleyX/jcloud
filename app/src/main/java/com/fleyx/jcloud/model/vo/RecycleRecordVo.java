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
    private Long id;

    /**
     * 被删除节点 ID。
     */
    private Long nodeId;

    /**
     * 节点名称。
     */
    private String name;

    /**
     * 类型：file / folder。
     */
    private String type;

    /**
     * 原始父节点 ID。
     */
    private Long originalParentId;

    /**
     * 原始路径快照。
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
