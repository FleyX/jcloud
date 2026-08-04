package com.fleyx.jcloud.model.dto;

import com.fleyx.jcloud.common.constant.CommonConstant;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 我的收藏分页查询入参（按归属实体类型独立分页）。
 */
@Data
public class MediaFavoriteQueryDto {

    /**
     * 归属实体类型编码：movie / series / season / episode / other。
     * <p>
     * 用 String 承载而非枚举：GET query 参数绑定对枚举走 {@code Enum.valueOf}（要求大写名），
     * 与本项目小写编码约定冲突；由查询支撑组件按 {@code MediaFavoriteOwnerType.of} 解析。
     */
    @NotBlank(message = "收藏类型不能为空")
    private String ownerType;

    /**
     * 媒体库 ID 过滤，可为空（为空表示全部收藏）。
     */
    private String directoryId;

    /**
     * 页码。
     */
    private Integer pageNum;

    /**
     * 每页条数。
     */
    private Integer pageSize;

    /**
     * 归一化页码。
     */
    public long normalizedPageNum() {
        return pageNum == null || pageNum < 1 ? CommonConstant.DEFAULT_PAGE_NUM : pageNum;
    }

    /**
     * 归一化每页条数。
     */
    public long normalizedPageSize() {
        if (pageSize == null || pageSize < 1) {
            return 24L;
        }
        return Math.min(pageSize, CommonConstant.MAX_PAGE_SIZE);
    }
}
