package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class PermissionTreeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String code;
    private String name;
    private Long parentId;
    private Integer status;
    private List<PermissionTreeVo> children;
    /** 仅前端渲染使用。 */
    private Integer level;
}
