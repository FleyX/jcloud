package com.fleyx.jcloud.model.dto;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import lombok.Data;

/**
 * 创建文件夹请求。
 */
@Data
public class FileCreateFolderDto {

    /**
     * 父节点 ID，根目录为 {@link FileNodeConstants#ROOT_ID}。
     */
    private String parentId;

    /**
     * 文件夹名称。
     */
    private String name;
}
