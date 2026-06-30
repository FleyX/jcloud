package com.fleyx.jcloud.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分享视图。
 */
@Data
public class ShareVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分享 ID。
     */
    private String id;

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
     * 是否需要密码。
     */
    private Boolean hasPassword;

    /**
     * 过期时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireAt;

    /**
     * 最大访问次数。
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
     * 创建时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
