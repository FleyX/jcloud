package com.fleyx.jcloud.model.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 跨来源传输项快照（用户勾选的顶层节点）。
 */
@Data
public class TransferItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 源节点 ID。
     */
    private String nodeId;

    /**
     * 源节点名称。
     */
    private String name;

    /**
     * 节点类型：file / folder。
     */
    private String type;

    /**
     * 节点大小（字节）。
     */
    private Long size;

    /**
     * 冲突解决后的最终名称。
     */
    private String finalName;

    /**
     * 冲突策略：skip / overwrite / keep，空表示遇冲突失败。
     */
    private String strategy;

    /**
     * 覆盖策略下需替换的目标已存在节点 ID。
     */
    private String replaceExistingId;
}
