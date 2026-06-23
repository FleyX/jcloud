package com.fleyx.jcloud.common.constant;

/**
 * 全局常量。
 */
public final class CommonConstant {

    private CommonConstant() {
    }

    /**
     * 接口公共前缀
     */
    public static final String API = "/jcloud/api";

    /**
     * 请求头：链路追踪 ID。
     */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * MDC 中 traceId 的 key。
     */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    /**
     * 默认分页页码，从 1 开始。
     */
    public static final long DEFAULT_PAGE_NUM = 1L;

    /**
     * 默认分页大小。
     */
    public static final long DEFAULT_PAGE_SIZE = 20L;

    /**
     * 最大分页大小。
     */
    public static final long MAX_PAGE_SIZE = 500L;
}
