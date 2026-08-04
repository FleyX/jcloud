package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 媒体收藏实体（用户对电影/剧集/季/集/其他五类实体的收藏记录，按用户隔离）。
 * <p>
 * 表按已确认 DDL 只有 create_time 无 update_time，故屏蔽父类 update_time 字段避免 MyBatis-Plus 生成该列 SQL。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_media_favorite")
public class MediaFavorite extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 收藏者用户 ID。
     */
    private String userId;

    /**
     * 归属实体类型：movie / series / season / episode / other。
     */
    private String ownerType;

    /**
     * 归属实体 ID。
     */
    private String ownerId;

    /**
     * 收藏表无更新时间列，屏蔽父类 update_time 映射。
     */
    @TableField(exist = false)
    private LocalDateTime updateTime;
}
