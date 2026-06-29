package com.fleyx.jcloud.model.dto;

import com.fleyx.jcloud.common.constant.CommonConstant;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 存储空间分页查询 DTO。
 */
@Data
public class StorageSpacePageQueryDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 名称关键字。
     */
    private String name;

    /**
     * 状态：1 启用，0 禁用。
     */
    private Integer status;

    @Min(value = 1, message = "页码从 1 开始")
    private Long pageNum = CommonConstant.DEFAULT_PAGE_NUM;

    @Min(value = 1, message = "每页条数至少为 1")
    @Max(value = 500, message = "每页条数不能超过 500")
    private Long pageSize = CommonConstant.DEFAULT_PAGE_SIZE;
}
