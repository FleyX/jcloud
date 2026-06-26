package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 系统初始化状态视图。
 */
@Data
public class SystemInitStatusVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否已完成初始化。
     */
    private Boolean initialized;

    /**
     * 当前用户是否为管理员。
     */
    private Boolean admin;
}
