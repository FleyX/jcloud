package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 分享主表实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_share")
public class Share extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所有者用户 ID。
     */
    private String userId;

    /**
     * 分享名称。
     */
    private String name;

    /**
     * 分享描述。
     */
    private String description;

    /**
     * 公开访问短码。
     */
    private String shareCode;

    /**
     * 访问密码哈希，为空表示无密码。
     */
    private String passwordHash;

    /**
     * 过期时间，为空表示永久有效。
     */
    private LocalDateTime expireAt;

    /**
     * 最大访问次数，为空表示无限制。
     */
    private Long maxViews;

    /**
     * 已访问次数。
     */
    private Long viewCount;

    /**
     * 状态：1 启用，0 停用。
     */
    private Integer status;

    /**
     * 逻辑删除时间戳：0 表示未删除。
     */
    private Long deleteAt;
}
