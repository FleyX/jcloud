package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class RoleUpdateDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "角色名称不能为空")
    @Size(max = 64, message = "角色名称长度不能超过 64")
    private String name;

    @Size(max = 255, message = "角色描述长度不能超过 255")
    private String description;

    private Integer status;

    private List<String> permissionCodes;
}
