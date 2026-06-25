package com.fleyx.jcloud.common.enums;

import lombok.Getter;

/**
 * 文件操作冲突解决策略。
 */
@Getter
public enum ConflictStrategy {

    /**
     * 跳过，保留目标位置已有文件。
     */
    SKIP("skip", "跳过"),

    /**
     * 覆盖，删除目标位置已有文件并替换。
     */
    OVERWRITE("overwrite", "覆盖"),

    /**
     * 自动重命名，格式为 name.1.ext。
     */
    AUTO_RENAME("auto_rename", "自动重命名");

    private final String code;
    private final String label;

    ConflictStrategy(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 根据 code 获取枚举，大小写不敏感。
     *
     * @param code 策略编码
     * @return 策略枚举
     */
    public static ConflictStrategy fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ConflictStrategy value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return null;
    }
}
