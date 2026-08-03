package com.fleyx.jcloud.model.vo;

import lombok.Data;

/**
 * 类型聚合视图（类型页卡片）。
 */
@Data
public class MediaGenreVo {

    /**
     * 类型名。
     */
    private String name;

    /**
     * 该类型下的条目数。
     */
    private Long itemCount;

    /**
     * 代表海报 URL（该类型下任一条目的元数据海报，无海报为 null）。
     */
    private String posterUrl;
}
