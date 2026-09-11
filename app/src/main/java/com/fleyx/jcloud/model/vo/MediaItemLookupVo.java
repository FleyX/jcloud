package com.fleyx.jcloud.model.vo;

import lombok.Data;

/**
 * 媒体收录反查视图（按文件节点 ID 反查）。
 */
@Data
public class MediaItemLookupVo {

    /**
     * 条目 ID：命中其他行为其他行 ID，命中电影文件为电影行 ID，命中集文件为集行 ID。
     */
    private String itemId;

    /**
     * 版本明细行 ID：命中电影文件明细时为 t_media_movie_file 行 ID，
     * 命中集文件明细时为 t_media_episode_file 行 ID；命中其他行为 null。
     */
    private String versionId;
}
