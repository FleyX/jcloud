package com.fleyx.jcloud.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 预览类型。
 */
@Getter
@RequiredArgsConstructor
public enum PreviewType {

    /**
     * 图片缩略图。
     */
    THUMBNAIL("thumbnail", "图片缩略图"),

    /**
     * 视频海报帧。
     */
    POSTER("poster", "视频海报帧"),

    /**
     * 文本预览。
     */
    TEXT("text", "文本预览"),

    /**
     * Office 文档预览（统一转换为 PDF）。
     */
    OFFICE("office", "Office 文档预览");

    private final String code;
    private final String desc;

    /**
     * 根据编码获取枚举。
     *
     * @param code 编码
     * @return 枚举值，找不到时返回 null
     */
    public static PreviewType fromCode(String code) {
        for (PreviewType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
