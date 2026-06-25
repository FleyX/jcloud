package com.fleyx.jcloud.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量下载请求。
 */
@Data
public class FileBatchDownloadDto {

    /**
     * 要下载的文件/文件夹节点 ID 列表。
     */
    private List<Long> ids;
}
