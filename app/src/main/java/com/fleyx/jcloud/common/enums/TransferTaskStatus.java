package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 跨来源传输任务状态。
 */
@Getter
public enum TransferTaskStatus {

    /**
     * 待执行。
     */
    PENDING("PENDING"),

    /**
     * 执行中。
     */
    RUNNING("RUNNING"),

    /**
     * 取消中（等待当前文件传完）。
     */
    CANCELLING("CANCELLING"),

    /**
     * 已取消。
     */
    CANCELED("CANCELED"),

    /**
     * 全部成功。
     */
    COMPLETED("COMPLETED"),

    /**
     * 全部失败。
     */
    FAILED("FAILED"),

    /**
     * 部分成功。
     */
    PARTIAL("PARTIAL");

    private final String value;

    TransferTaskStatus(String value) {
        this.value = value;
    }

    /**
     * 判断状态是否为终态。
     *
     * @param status 状态值
     * @return 是否终态
     */
    public static boolean isTerminal(String status) {
        return CANCELED.value.equals(status) || COMPLETED.value.equals(status)
                || FAILED.value.equals(status) || PARTIAL.value.equals(status);
    }
}
