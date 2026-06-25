package com.fleyx.jcloud.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 删除到回收站参数。
 */
@Data
public class FileDeleteDto {

    /**
     * 要删除的节点 ID 列表。
     */
    private List<Long> ids;
}
