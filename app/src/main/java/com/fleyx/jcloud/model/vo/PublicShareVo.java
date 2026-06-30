package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 公开分享视图。
 */
@Data
public class PublicShareVo implements Serializable {

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
     * 是否需要密码。
     */
    private Boolean hasPassword;

    /**
     * 创建时间。
     */
    private String createTime;

    /**
     * 顶层文件节点列表。
     */
    private List<FileNodeVo> items;
}
