package com.fleyx.jcloud.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;

/**
 * 媒体收藏归属实体类型枚举（t_media_favorite.owner_type），覆盖电影/剧集/季/集/其他五类实体。
 */
@Getter
public enum MediaFavoriteOwnerType {

    /**
     * 电影。
     */
    MOVIE("movie"),

    /**
     * 剧集。
     */
    SERIES("series"),

    /**
     * 季。
     */
    SEASON("season"),

    /**
     * 集。
     */
    EPISODE("episode"),

    /**
     * 其他条目。
     */
    OTHER("other");

    private final String code;

    MediaFavoriteOwnerType(String code) {
        this.code = code;
    }

    /**
     * 按编码解析枚举；未知或为空返回 null。
     *
     * @param code owner_type 编码，可为空
     * @return 枚举或 null
     */
    public static MediaFavoriteOwnerType of(String code) {
        if (code == null) {
            return null;
        }
        for (MediaFavoriteOwnerType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 反序列化：toggle 入参按小写编码（movie/series/season/episode/other）绑定枚举。
     */
    @JsonCreator
    public static MediaFavoriteOwnerType fromCode(String code) {
        return of(code);
    }
}
