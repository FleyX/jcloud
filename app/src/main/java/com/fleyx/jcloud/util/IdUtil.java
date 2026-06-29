package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.util.id.Base36IdGenerator;
import com.fleyx.jcloud.common.util.id.Base36Number;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 业务 ID 生成工具。
 * <p>
 * 提供定长 13 位 base36 字符串 ID 的生成能力。
 */
public final class IdUtil {

    private static final Base36IdGenerator GENERATOR = new Base36IdGenerator();

    private IdUtil() {
    }

    /**
     * 生成下一个 ID。
     *
     * @return 13 位 base36 字符串
     */
    public static String nextId() {
        return GENERATOR.nextId(null).toString();
    }

    /**
     * 批量生成 ID。
     *
     * @param n 数量
     * @return ID 列表
     */
    public static List<String> nextIds(int n) {
        if (n <= 0) {
            return List.of();
        }
        return IntStream.range(0, n)
                .mapToObj(i -> GENERATOR.nextId(null).toString())
                .toList();
    }

    /**
     * 将 long 编码为定长 base36 字符串。
     *
     * @param value 原始 long
     * @return 13 位 base36 字符串
     */
    public static String encode(long value) {
        return Base36Number.encode(value);
    }

    /**
     * 将 base36 字符串解码为 long。
     *
     * @param base36 13 位 base36 字符串
     * @return long 值
     */
    public static long decode(String base36) {
        return Base36Number.decode(base36);
    }
}
