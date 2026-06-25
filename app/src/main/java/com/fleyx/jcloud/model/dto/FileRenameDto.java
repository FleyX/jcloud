package com.fleyx.jcloud.model.dto;

import lombok.Data;

/**
 * 文件/文件夹重命名请求。
 */
@Data
public class FileRenameDto {

    /**
     * 节点 ID。
     */
    private Long id;

    /**
     * 新名称。
     */
    private String newName;
}
