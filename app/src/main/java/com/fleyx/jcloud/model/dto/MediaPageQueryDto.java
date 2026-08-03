package com.fleyx.jcloud.model.dto;

import com.fleyx.jcloud.common.constant.CommonConstant;
import lombok.Data;

/**
 * 媒体列表分页查询入参（电影/电视/其他通用）。
 */
@Data
public class MediaPageQueryDto {

    /**
     * 排序字段：添加时间。
     */
    public static final String SORT_FIELD_ADDED = "added";

    /**
     * 排序字段：发行时间。
     */
    public static final String SORT_FIELD_RELEASE = "release";

    /**
     * 排序字段：评分。
     */
    public static final String SORT_FIELD_RATING = "rating";

    /**
     * 排序字段：标题。
     */
    public static final String SORT_FIELD_TITLE = "title";

    /**
     * 页码。
     */
    private Long pageNum = CommonConstant.DEFAULT_PAGE_NUM;

    /**
     * 每页条数。
     */
    private Long pageSize = 48L;

    /**
     * 搜索关键词（文件名/剧名/元数据标题/简介）。
     */
    private String keyword;

    /**
     * 类型筛选：匹配元数据 genres 拆分后包含该值的条目。
     */
    private String genre;

    /**
     * 媒体库 ID 过滤，可为空（为空表示跨库）。
     */
    private String directoryId;

    /**
     * 排序字段：added 添加时间 / release 发行时间 / rating 评分 / title 标题，默认 added。
     */
    private String sortField = SORT_FIELD_ADDED;

    /**
     * 排序方向：asc / desc，默认 desc。
     */
    private String sortOrder = "desc";

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
            return 48L;
        }
        return Math.min(pageSize, CommonConstant.MAX_PAGE_SIZE);
    }

    /**
     * 是否升序。
     */
    public boolean asc() {
        return "asc".equalsIgnoreCase(sortOrder);
    }

    /**
     * 是否按发行时间排序。
     */
    public boolean sortByRelease() {
        return SORT_FIELD_RELEASE.equalsIgnoreCase(sortField);
    }

    /**
     * 是否按评分排序。
     */
    public boolean sortByRating() {
        return SORT_FIELD_RATING.equalsIgnoreCase(sortField);
    }

    /**
     * 是否按标题排序。
     */
    public boolean sortByTitle() {
        return SORT_FIELD_TITLE.equalsIgnoreCase(sortField);
    }

    /**
     * 归一化排序字段：release / rating / title 透传，其余（含非法值）回退 added。
     * 供 SQL 映射层统一使用，避免各查询支撑组件各自解析。
     */
    public String resolveSortField() {
        if (sortByRelease()) {
            return SORT_FIELD_RELEASE;
        }
        if (sortByRating()) {
            return SORT_FIELD_RATING;
        }
        if (sortByTitle()) {
            return SORT_FIELD_TITLE;
        }
        return SORT_FIELD_ADDED;
    }
}
