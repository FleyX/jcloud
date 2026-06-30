package com.fleyx.jcloud.model.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 分享项实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_share_item")
public class ShareItem extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属分享 ID。
     */
    private String shareId;

    /**
     * 文件节点 ID。
     */
    private String fileNodeId;
}
