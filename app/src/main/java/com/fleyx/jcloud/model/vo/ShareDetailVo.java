package com.fleyx.jcloud.model.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.util.List;

/**
 * 分享详情视图。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ShareDetailVo extends ShareVo {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分享项文件节点列表。
     */
    private List<FileNodeVo> items;
}
