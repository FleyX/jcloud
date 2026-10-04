package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 邮件分享收件人记忆视图：当前用户最近收件邮箱与发件邮箱是否可用。
 * <p>
 * {@code smtpConfigured} 为 false 时前端弹窗展示未配置引导并禁用提交。
 */
@Data
public class RecentRecipientsVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否已配置发件邮箱。
     */
    private Boolean smtpConfigured;

    /**
     * 最近收件邮箱，最多 5 个、最新在前。
     */
    private List<String> recipients;

    /**
     * 邮件分享附件直发大小上限（MB），供弹窗预检提示。
     */
    private Integer attachmentMaxSizeMb;
}
