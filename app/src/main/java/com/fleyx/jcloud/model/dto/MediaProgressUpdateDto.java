package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 播放进度上报入参。
 */
@Data
public class MediaProgressUpdateDto {

    /**
     * 播放进度（毫秒）。
     */
    @NotNull(message = "进度不能为空")
    @Min(value = 0, message = "进度不能为负数")
    private Long progressMs;

    /**
     * 播放的文件明细行 ID（电影为 t_media_movie_file 明细行 ID，即版本 ID；issue #21 版本选择）。
     * 非空时校验该明细行属于此电影并记为该次播放的版本（last_play_file_id）；剧集/其他忽略。
     */
    private String versionId;
}
