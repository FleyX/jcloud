package com.fleyx.jcloud.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件复用工具：优先硬链接，失败时退化为复制。
 */
public final class FileLinkUtil {

    private FileLinkUtil() {
    }

    /**
     * 链接器函数式接口，便于测试注入异常场景。
     */
    @FunctionalInterface
    public interface Linker {
        void link(Path source, Path target) throws IOException;
    }

    /**
     * 创建硬链接，失败则复制文件。
     *
     * @param source 源文件
     * @param target 目标文件
     * @throws IOException 复制也失败时抛出
     */
    public static void linkOrCopy(Path source, Path target) throws IOException {
        // Files.createLink(link, existing) 参数顺序与 Linker(source, target) 相反，需交换
        linkOrCopy(source, target, (s, t) -> Files.createLink(t, s));
    }

    /**
     * 使用指定链接器创建硬链接，失败则复制文件。
     *
     * @param source 源文件
     * @param target 目标文件
     * @param linker 链接器
     * @throws IOException 复制也失败时抛出
     */
    public static void linkOrCopy(Path source, Path target, Linker linker) throws IOException {
        try {
            linker.link(source, target);
        } catch (IOException | UnsupportedOperationException e) {
            Files.copy(source, target);
        }
    }
}
