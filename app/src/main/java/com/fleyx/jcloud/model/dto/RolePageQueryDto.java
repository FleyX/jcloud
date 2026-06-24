package com.fleyx.jcloud.model.dto;

import com.fleyx.jcloud.common.constant.CommonConstant;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class RolePageQueryDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String code;

    private String name;

    private Integer status;

    @Min(value = 1, message = "页码从 1 开始")
    private Long pageNum = CommonConstant.DEFAULT_PAGE_NUM;

    @Min(value = 1, message = "每页条数至少为 1")
    @Max(value = 500, message = "每页条数不能超过 500")
    private Long pageSize = CommonConstant.DEFAULT_PAGE_SIZE;
}
