package com.fleyx.jcloud.model.bo;

import lombok.Data;

/**
 * 远程文件条目元数据，协议适配器返回的统一对象。
 */
@Data
public class RemoteFileEntry {

    /**
     * 远端完整路径。
     */
    private String remotePath;

    /**
     * 节点名称。
     */
    private String name;

    /**
     * 是否为文件夹。
     */
    private boolean folder;

    /**
     * 文件大小（字节），文件夹为 0。
     */
    private Long size;

    /**
     * 最后修改时间（毫秒时间戳）。
     */
    private Long lastModified;

    /**
     * 远端内容标识符（如 etag）。
     */
    private String etag;
}
