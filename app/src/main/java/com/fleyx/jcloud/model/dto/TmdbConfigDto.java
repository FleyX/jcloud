package com.fleyx.jcloud.model.dto;

import lombok.Data;

/**
 * TMDB 全局配置入参。
 */
@Data
public class TmdbConfigDto {

    /**
     * TMDB API Key。
     */
    private String apiKey;

    /**
     * HTTP 代理（host:port），可为空。
     */
    private String proxy;
}
