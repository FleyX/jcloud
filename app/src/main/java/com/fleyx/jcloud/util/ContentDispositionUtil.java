package com.fleyx.jcloud.util;

import org.springframework.http.ContentDisposition;

import java.nio.charset.StandardCharsets;

/**
 * Content-Disposition 响应头构造工具。
 * <p>
 * 用于下载/预览等场景，以标准方式生成下载文件名。
 * <p>
 * 为什么必须走 RFC 5987：header 值只能包含 ISO-8859-1（0x00~0xFF）字符，Tomcat 10.1
 * 对超出该范围的字符会静默丢弃整个 header——直接字符串拼接中文文件名
 * （如 {@code "attachment; filename=\"" + fileName + "\""}）会导致浏览器收不到
 * Content-Disposition，下载的文件退化为用 URL 末尾的 id 命名。
 * <p>
 * 本工具委托 {@link org.springframework.http.ContentDisposition}，以 UTF-8 编码调用
 * {@code filename(name, UTF_8)}，Spring 会同时输出两个参数：
 * <ul>
 *     <li>{@code filename="..."}：ISO-8859-1 兼容的旧式参数，非 ASCII 字符被替换为 '_'，供旧客户端降级使用；</li>
 *     <li>{@code filename*=UTF-8''<percent-encoded>}：RFC 5987 参数，中文等文件名完整无损。</li>
 * </ul>
 * 整条 header 仅含 ASCII 字符，Tomcat 不会丢弃。
 */
public final class ContentDispositionUtil {

    private ContentDispositionUtil() {
    }

    /**
     * 构造 attachment 类型的 Content-Disposition 头值（用于下载）。
     *
     * @param filename 下载文件名（中文等非 ASCII 文件名会自动走 RFC 5987 编码）
     * @return 可直接放入 Content-Disposition header 的完整值，如
     * {@code attachment; filename="a.txt"; filename*=UTF-8''a.txt}
     */
    public static String attachment(String filename) {
        return build("attachment", filename);
    }

    /**
     * 构造 inline 类型的 Content-Disposition 头值（用于预览）。
     *
     * @param filename 预览文件名（中文等非 ASCII 文件名会自动走 RFC 5987 编码）
     * @return 可直接放入 Content-Disposition header 的完整值，如
     * {@code inline; filename="a.txt"; filename*=UTF-8''a.txt}
     */
    public static String inline(String filename) {
        return build("inline", filename);
    }

    private static String build(String type, String filename) {
        return ContentDisposition.builder(type)
                .filename(filename, StandardCharsets.UTF_8)
                .build()
                .toString();
    }
}
