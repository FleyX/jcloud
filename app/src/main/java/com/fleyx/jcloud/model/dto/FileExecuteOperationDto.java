package com.fleyx.jcloud.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 移动/复制操作执行请求。
 */
@Data
public class FileExecuteOperationDto {

    /**
     * 操作类型：move / copy。
     */
    private String type;

    /**
     * 目标父节点 ID。
     */
    private String targetParentId;

    /**
     * 待操作项及冲突策略。
     */
    private List<OperationItemDto> items;

    /**
     * 全局冲突解决策略，用户选择「统一全部」时使用。
     */
    private String globalStrategy;
}
