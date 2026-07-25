package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 权限树节点视图（只读，数据源为内存 PermissionRegistry）。
 */
@Data
public class PermissionTreeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 权限编码。 */
    private String code;

    /** 权限名称。 */
    private String name;

    /** 父权限编码。 */
    private String parentCode;

    /** 权限下挂的资源列表。 */
    private List<ResourceVo> resources;

    /** 子权限。 */
    private List<PermissionTreeVo> children;
}
