package com.fleyx.jcloud.model.vo;

import lombok.Data;

/**
 * 媒体库来源目录视图。
 */
@Data
public class MediaDirectorySourceVo {

    /**
     * 来源目录 ID。
     */
    private String id;

    /**
     * 文件夹节点 ID。
     */
    private String fileNodeId;

    /**
     * 文件夹名称。
     */
    private String folderName;

    /**
     * 来源类型：local / remote。
     */
    private String sourceType;
}
