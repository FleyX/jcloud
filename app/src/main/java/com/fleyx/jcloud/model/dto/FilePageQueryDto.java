package com.fleyx.jcloud.model.dto;

import com.fleyx.jcloud.common.constant.CommonConstant;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文件节点分页查询 DTO。
 */
@Data
public class FilePageQueryDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 父节点 ID，根目录为 {@link FileNodeConstants#ROOT_ID}。
     */
    private String parentId = FileNodeConstants.ROOT_ID;

    /**
     * 名称关键字。
     */
    private String name;

    /**
     * 排序字段：name / size / createTime。
     */
    private String sortField;

    /**
     * 排序方向：asc / desc。
     */
    private String sortOrder;

    @Min(value = 1, message = "页码从 1 开始")
    private Long pageNum = CommonConstant.DEFAULT_PAGE_NUM;

    @Min(value = 1, message = "每页条数至少为 1")
    @Max(value = 500, message = "每页条数不能超过 500")
    private Long pageSize = CommonConstant.DEFAULT_PAGE_SIZE;
}
