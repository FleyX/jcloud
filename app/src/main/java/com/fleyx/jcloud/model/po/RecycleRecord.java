package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 回收站记录实体。
 * <p>
 * 记录用户主动删除的文件或文件夹节点，原文件表中的对应节点会被物理删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_recycle_bin")
public class RecycleRecord extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID。
     */
    private String userId;

    /**
     * 节点名称。
     */
    private String name;

    /**
     * 类型：file / folder。
     */
    private String type;

    /**
     * 原始路径快照（节点自身完整虚拟名称路径）。
     */
    private String originalPathName;

    /**
     * 该记录对应子树总字节数，用于配额扣减。
     */
    private Long totalSize;

    /**
     * 状态：1 启用，0 禁用。
     */
    private Integer status;
}
