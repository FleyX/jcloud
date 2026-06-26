package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 字节格式化与单位换算工具类。
 */
public final class ByteFormatUtil {

    private static final long B = 1L;
    private static final long KB = 1024L;
    private static final long MB = KB * 1024L;
    private static final long GB = MB * 1024L;
    private static final long TB = GB * 1024L;
    private static final long PB = TB * 1024L;

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    private ByteFormatUtil() {
        // 工具类禁止实例化
    }

    /**
     * 将字节数自动格式化为最易读的单位字符串（保留 2 位小数）。
     * <p>
     * 0 返回 "0 B"。
     *
     * @param bytes 字节数
     * @return 格式化后的字符串，如 "1.50 GB"
     */
    public static String format(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        int unitIndex = 0;
        double value = bytes;
        while (value >= 1024 && unitIndex < UNITS.length - 1) {
            value /= 1024;
            unitIndex++;
        }
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString() + " " + UNITS[unitIndex];
    }

    /**
     * 将指定单位的容量值转换为字节数。
     *
     * @param value 数值
     * @param unit  单位：B / MB / GB / TB（不区分大小写）
     * @return 字节数
     */
    public static long parse(long value, String unit) {
        if (value < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "容量值不能为负数");
        }
        if (value == 0) {
            return 0L;
        }
        return switch (normalizeUnit(unit)) {
            case "B" -> value * B;
            case "MB" -> value * MB;
            case "GB" -> value * GB;
            case "TB" -> value * TB;
            default -> throw new BusinessException(ResultCode.PARAM_ERROR, "不支持的容量单位: " + unit);
        };
    }

    private static String normalizeUnit(String unit) {
        if (unit == null || unit.isBlank()) {
            return "GB";
        }
        return unit.trim().toUpperCase();
    }
}
