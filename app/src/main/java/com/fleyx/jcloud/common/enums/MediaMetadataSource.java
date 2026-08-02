package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 媒体元数据来源枚举。
 */
@Getter
public enum MediaMetadataSource {

    /**
     * 本地 NFO 文件。
     */
    LOCAL_NFO("local_nfo"),

    /**
     * TMDB 在线削刮。
     */
    TMDB("tmdb");

    private final String code;

    MediaMetadataSource(String code) {
        this.code = code;
    }
}
