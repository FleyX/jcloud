package com.fleyx.jcloud.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 永久删除回收站记录参数。
 */
@Data
public class FilePermanentDeleteDto {

    /**
     * 回收站记录 ID 列表。
     */
    private List<String> ids;
}
