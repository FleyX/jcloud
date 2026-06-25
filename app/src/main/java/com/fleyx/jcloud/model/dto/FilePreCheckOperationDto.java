package com.fleyx.jcloud.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 移动/复制操作预检请求。
 */
@Data
public class FilePreCheckOperationDto {

    /**
     * 操作类型：move / copy。
     */
    private String type;

    /**
     * 目标父节点 ID。
     */
    private Long targetParentId;

    /**
     * 待操作项。
     */
    private List<OperationItemDto> items;
}
