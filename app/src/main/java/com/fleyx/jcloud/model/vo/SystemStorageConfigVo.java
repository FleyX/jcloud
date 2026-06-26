package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 系统数据目录配置视图。
 */
@Data
public class SystemStorageConfigVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前指定的系统数据目录所在用户存储空间 ID，未配置时为空。
     */
    private String systemSpaceId;
}
