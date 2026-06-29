package com.fleyx.jcloud.common.util.id;

import java.math.BigInteger;

/**
 * 包装一个 long 值，对外以 13 位定长 base36 字符串形式展示。
 * <p>
 * 用于 MyBatis-Plus 的 {@link com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator}：
 * 框架通过 {@link #toString()} 将返回值转换为实体主键字段类型（String）。
 */
public final class Base36Number extends Number implements Comparable<Base36Number> {

    private static final long serialVersionUID = 1L;

    /**
     * base36 定长长度。
     */
    public static final int FIXED_LENGTH = 13;

    /**
     * base36 进制。
     */
    private static final int RADIX = 36;

    private final long value;
    private final String base36;

    private Base36Number(long value) {
        this.value = value;
        this.base36 = encode(value);
    }

    /**
     * 从 long 值创建。
     *
     * @param value 原始 long，应为非负数
     * @return Base36Number
     */
    public static Base36Number of(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("base36 id 不支持负数: " + value);
        }
        return new Base36Number(value);
    }

    /**
     * 将 base36 字符串解码为 long。
     *
     * @param base36 13 位 base36 字符串
     * @return long 值
     */
    public static long decode(String base36) {
        if (base36 == null || base36.length() != FIXED_LENGTH) {
            throw new IllegalArgumentException("非法的 base36 id: " + base36);
        }
        return new BigInteger(base36, RADIX).longValue();
    }

    /**
     * 将非负 long 编码为 13 位定长 base36 字符串。
     *
     * @param value 原始 long
     * @return 13 位 base36 字符串
     */
    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("base36 id 不支持负数: " + value);
        }
        String raw = Long.toString(value, RADIX);
        if (raw.length() > FIXED_LENGTH) {
            throw new IllegalArgumentException("数值过大，无法编码为 " + FIXED_LENGTH + " 位 base36: " + value);
        }
        return "0".repeat(FIXED_LENGTH - raw.length()) + raw;
    }

    @Override
    public int intValue() {
        return (int) value;
    }

    @Override
    public long longValue() {
        return value;
    }

    @Override
    public float floatValue() {
        return value;
    }

    @Override
    public double doubleValue() {
        return value;
    }

    @Override
    public String toString() {
        return base36;
    }

    @Override
    public int compareTo(Base36Number other) {
        return Long.compare(this.value, other.value);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Base36Number other)) {
            return false;
        }
        return this.value == other.value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }
}
