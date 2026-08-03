package com.fleyx.jcloud.model.dto;

import com.fleyx.jcloud.common.enums.MediaFavoriteOwnerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 媒体收藏切换入参。
 */
@Data
public class MediaFavoriteToggleDto {

    /**
     * 归属实体类型：movie / series / season / episode / other。
     */
    @NotNull(message = "收藏类型不能为空")
    private MediaFavoriteOwnerType ownerType;

    /**
     * 归属实体 ID。
     */
    @NotBlank(message = "收藏实体 ID 不能为空")
    private String ownerId;
}
