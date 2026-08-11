package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 文件树变更操作类型（ADR 0025）。
 */
@Getter
public enum FileChangeOperation {

    /**
     * 创建（上传、新建文件夹、秒传、分片合并完成、传输落盘）。
     */
    CREATE("create", "创建"),

    /**
     * 更新（重命名）。
     */
    UPDATE("update", "更新"),

    /**
     * 移动。
     */
    MOVE("move", "移动"),

    /**
     * 复制。
     */
    COPY("copy", "复制"),

    /**
     * 删除到回收站。
     */
    DELETE("delete", "删除"),

    /**
     * 从回收站恢复。
     */
    RESTORE("restore", "恢复"),

    /**
     * 彻底删除（回收站物理删除）。
     */
    PERMANENT_DELETE("permanent_delete", "彻底删除");

    private final String code;
    private final String label;

    FileChangeOperation(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
