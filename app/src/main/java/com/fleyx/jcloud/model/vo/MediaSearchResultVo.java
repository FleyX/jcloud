package com.fleyx.jcloud.model.vo;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

/**
 * 全局搜索聚合视图：跨全部媒体库搜索，按电影/剧集/其他分组返回（各组前 N 条 + 总数）。
 */
@Data
public class MediaSearchResultVo {

    /**
     * 电影组（分页包装，总数即命中电影数）。
     */
    private IPage<MediaItemVo> movies;

    /**
     * 剧集组（分页包装，总数即命中剧集数）。
     */
    private IPage<MediaSeriesVo> series;

    /**
     * 其他组（分页包装，总数即命中其他条数）。
     */
    private IPage<MediaItemVo> others;
}
