package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文件节点实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_file_node")
public class FileNode extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID。
     */
    private Long userId;

    /**
     * 父节点 ID，根目录为 0。
     */
    private Long parentId;

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
    private Long storageSpaceId;

    /**
     * 物化路径（祖先 ID 快照）。
     */
    private String path;

    /**
     * 虚拟路径快照。
     */
    private String pathName;

    /**
     * MIME 类型。
     */
    private String mimeType;

    /**
     * 状态：1 启用，0 禁用。
     */
    private Integer status;
}
