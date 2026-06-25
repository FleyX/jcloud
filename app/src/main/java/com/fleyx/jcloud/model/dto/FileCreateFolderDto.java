package com.fleyx.jcloud.model.dto;

import lombok.Data;

/**
 * 创建文件夹请求。
 */
@Data
public class FileCreateFolderDto {

    /**
     * 父节点 ID，根目录为 0。
     */
    private Long parentId;

    /**
     * 文件夹名称。
     */
    private String name;
}
