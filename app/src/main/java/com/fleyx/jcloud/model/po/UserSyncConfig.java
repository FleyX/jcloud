package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户存储空间同步配置实体。
 */
@Data
@TableName("t_user_sync_config")
public class UserSyncConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID，主键。
     */
    @TableId(type = IdType.INPUT)
    private String userId;

    /**
     * 定时同步 cron 表达式。
     */
    private String cronExpr;

    /**
     * 是否启用定时同步：1 启用，0 禁用。
     */
    private Integer enabled;

    /**
     * 下次定时同步时间。
     */
    private LocalDateTime nextSyncTime;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
