package com.fleyx.jcloud.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 已观看标记更新入参（电影/剧/季/集/其他标题级行）。
 */
@Data
public class MediaWatchedUpdateDto {

    /**
     * 目标标记：true 标记已观看（并清零播放进度），false 取消标记（不动进度）。
     */
    @NotNull(message = "观看标记不能为空")
    private Boolean watched;
}
