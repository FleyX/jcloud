package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.enums.PreviewType;
import com.fleyx.jcloud.service.FilePreviewGenerator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文本预览生成器。
 * <p>
 * 将源文件内容按 UTF-8 读取后写入预览文件，限制最大 1MB。
 */
@Component
public class TextPreviewGenerator implements FilePreviewGenerator {

    private static final long MAX_PREVIEW_BYTES = 1024 * 1024;

    @Override
    public PreviewType supportedType() {
        return PreviewType.TEXT;
    }

    @Override
    public void generate(Path sourcePath, Path targetPath) throws Exception {
        long size = Files.size(sourcePath);
        long readBytes = Math.min(size, MAX_PREVIEW_BYTES);
        byte[] bytes = new byte[(int) readBytes];
        try (java.io.InputStream is = Files.newInputStream(sourcePath)) {
            //noinspection ResultOfMethodCallIgnored
            is.read(bytes);
        }
        // 简单校验：若包含空字节则视为二进制，拒绝生成文本预览
        for (byte b : bytes) {
            if (b == 0) {
                throw new IllegalArgumentException("文件包含二进制内容，无法文本预览");
            }
        }
        Files.createDirectories(targetPath.getParent());
        Files.write(targetPath, bytes);
    }
}
