package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.SystemException;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 字幕文件编码处理工具。
 * <p>
 * srt 等文本字幕常见 GBK 编码，ffmpeg 按 UTF-8 读取会乱码。
 * 采用最小启发式：内容可严格按 UTF-8 解码则原样使用，否则按 GBK 解码后改写为 UTF-8 临时文件。
 */
public final class SubtitleCharsetUtil {

    private static final Charset FALLBACK_CHARSET = Charset.forName("GBK");

    private SubtitleCharsetUtil() {
    }

    /**
     * 确保字幕文件为 UTF-8 编码。
     *
     * @param input 原始字幕文件
     * @return UTF-8 编码的文件路径；原文件已是 UTF-8 时返回原路径，否则返回新建的临时文件（调用方负责删除）
     */
    public static Path ensureUtf8(Path input) {
        try {
            byte[] bytes = Files.readAllBytes(input);
            if (isUtf8(bytes)) {
                return input;
            }
            // 保留原扩展名，便于 ffmpeg 按扩展名探测字幕格式
            String name = input.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".srt";
            Path temp = Files.createTempFile("jcloud-sub-utf8-", ext);
            Files.writeString(temp, new String(bytes, FALLBACK_CHARSET), StandardCharsets.UTF_8);
            return temp;
        } catch (IOException e) {
            throw new SystemException(ResultCode.SYSTEM_ERROR, "字幕编码转换失败", e);
        }
    }

    /**
     * 严格按 UTF-8 解码判断字节内容是否为合法 UTF-8。
     */
    public static boolean isUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }
}
