package com.fleyx.jcloud.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 系统初始化 DTO。
 */
@Data
public class SystemInitDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待创建的存储空间列表，至少一个。
     */
    @NotEmpty(message = "至少配置一个存储空间")
    @Valid
    private List<InitSpaceItem> spaces;

    /**
     * 主存储空间在 spaces 列表中的索引。
     */
    @NotNull(message = "必须指定主存储空间")
    private Integer primaryIndex;

    /**
     * 系统数据存放的存储空间在 spaces 列表中的索引。
     */
    @NotNull(message = "必须指定系统数据存放空间")
    private Integer systemDataIndex;

    /**
     * 初始化阶段单个存储空间项。
     */
    @Data
    public static class InitSpaceItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @NotBlank(message = "存储空间名称不能为空")
        @Size(max = 64, message = "存储空间名称长度不能超过 64")
        private String name;

        @NotBlank(message = "物理路径不能为空")
        @Size(max = 512, message = "物理路径长度不能超过 512")
        private String path;

        @Size(max = 255, message = "备注长度不能超过 255")
        private String remark;
    }
}
