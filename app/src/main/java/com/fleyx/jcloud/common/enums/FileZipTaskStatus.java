package com.fleyx.jcloud.common.enums;

/**
 * 批量 ZIP 下载任务状态。
 */
public enum FileZipTaskStatus {

    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String code;

    FileZipTaskStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static FileZipTaskStatus fromCode(String code) {
        for (FileZipTaskStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
