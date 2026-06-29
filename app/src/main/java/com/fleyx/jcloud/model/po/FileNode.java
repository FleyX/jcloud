package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 文件节点实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_file_node")
public class FileNode extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID。
     */
    private String userId;

    /**
     * 父节点 ID，根目录为虚拟根节点占位 ID。
     */
    private String parentId;

    /**
     * 节点名称。
     */
    private String name;

    /**
     * 类型：file / folder。
     */
    private String type;

    /**
     * 文件大小（字节）。
     */
    private Long size;

    /**
     * 文件身份 hash。
     */
    private String hash;

    /**
     * 存储空间 ID。
     */
    private String storageSpaceId;

    /**
     * 从虚拟根到父节点的 id 路径，使用 '.' 分割，不含自身 id。
     */
    private String path;

    /**
     * MIME 类型。
     */
    private String mimeType;

    /**
     * 状态：1 启用，0 禁用。
     */
    private Integer status;
}
