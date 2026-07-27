package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 媒体条目手动匹配入参。
 */
@Data
public class MediaMatchUpdateDto {

    /**
     * TMDB 条目 ID。
     */
    @NotNull(message = "TMDB ID 不能为空")
    private Long tmdbId;

    /**
     * 类型：movie / tv。
     */
    @NotBlank(message = "类型不能为空")
    @Pattern(regexp = "movie|tv", message = "类型仅支持 movie/tv")
    private String mediaType;
}
