package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fleyx.jcloud.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 远程挂载配置实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_remote_mount")
public class RemoteMount extends SoftDeleteEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID。
     */
    private String userId;

    /**
     * 挂载点显示名，也是文件树根目录下的文件夹名称。
     */
    private String name;

    /**
     * 协议类型：webdav / s3 / nfs。
     */
    private String type;

    /**
     * 是否启用定时同步：1 启用，0 禁用。
     */
    private Integer enabled;

    /**
     * 定时同步 cron 表达式。
     */
    private String cronExpr;

    /**
     * 下次定时同步时间。
     */
    private LocalDateTime nextSyncTime;

    /**
     * 上次同步完成时间。
     */
    private LocalDateTime lastSyncTime;

    /**
     * 上次同步状态：COMPLETED / FAILED / PARTIAL。
     */
    private String lastSyncStatus;

    /**
     * 上次同步错误信息。
     */
    private String lastSyncError;

    /**
     * 协议配置 JSON，包含 URL、用户名、密码、根路径等。
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String config;
}
