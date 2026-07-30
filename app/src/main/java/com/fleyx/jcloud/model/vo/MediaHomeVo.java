package com.fleyx.jcloud.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 影视首页聚合视图（我的媒体 / 继续观看 / 接下来）。
 */
@Data
public class MediaHomeVo {

    /**
     * 我的媒体：当前用户全部媒体库（含封面）。
     */
    private List<MediaDirectoryVo> libraries;

    /**
     * 继续观看：未看完且有播放进度的条目，按最近播放时间倒序。
     */
    private List<MediaItemVo> continueWatching;

    /**
     * 接下来：有观看记录的剧的下一集（排除在播集），按剧最近播放时间倒序。
     */
    private List<MediaItemVo> nextUp;
}
