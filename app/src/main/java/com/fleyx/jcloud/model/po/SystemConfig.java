package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统配置实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_system_config")
public class SystemConfig extends SoftDeleteEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 配置键。
     */
    private String configKey;

    /**
     * 配置值。
     */
    private String configValue;
}
