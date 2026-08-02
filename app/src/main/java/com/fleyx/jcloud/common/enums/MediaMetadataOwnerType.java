package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 媒体元数据归属实体类型枚举（t_media_metadata.owner_type）。
 */
@Getter
public enum MediaMetadataOwnerType {

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
    EPISODE("episode");

    private final String code;

    MediaMetadataOwnerType(String code) {
        this.code = code;
    }

    /**
     * 按编码解析枚举（owner_type 读写统一走枚举，issue #21 审查遗留修复）；未知或为空返回 null。
     *
     * @param code owner_type 编码，可为空
     * @return 枚举或 null
     */
    public static MediaMetadataOwnerType of(String code) {
        if (code == null) {
            return null;
        }
        for (MediaMetadataOwnerType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
