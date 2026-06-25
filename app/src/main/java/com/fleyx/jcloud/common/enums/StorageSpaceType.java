package com.fleyx.jcloud.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 存储空间类型。
 */
@Getter
@RequiredArgsConstructor
public enum StorageSpaceType {

    /**
     * 用户存储空间，用于存放用户文件数据。
     */
    USER("USER", "用户存储空间"),

    /**
     * 系统存储空间，用于存放预览图、配置、缓存等系统数据。
     */
    SYSTEM("SYSTEM", "系统存储空间");

    private final String code;
    private final String desc;

    /**
     * 根据编码获取枚举。
     *
     * @param code 编码
     * @return 枚举值，找不到时返回 null
     */
    public static StorageSpaceType fromCode(String code) {
        for (StorageSpaceType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
