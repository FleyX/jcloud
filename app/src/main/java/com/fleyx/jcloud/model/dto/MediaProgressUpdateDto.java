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
}
