package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class PermissionSaveDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "权限编码不能为空")
    @Size(max = 64, message = "权限编码长度不能超过 64")
    private String code;

    @NotBlank(message = "权限名称不能为空")
    @Size(max = 64, message = "权限名称长度不能超过 64")
    private String name;

    private String parentId;

    private Integer status;

    private List<String> resourceIds;
}
