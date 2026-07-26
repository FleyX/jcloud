package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.config.PreviewProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Office 文档预览生成器测试。
 */
class OfficePdfPreviewGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRejectUnsupportedExtension() throws Exception {
        OfficePdfPreviewGenerator generator = newGenerator(1024 * 1024);
        Path source = tempDir.resolve("archive.zip");
        Files.write(source, new byte[100]);

        assertThrows(IllegalArgumentException.class,
                () -> generator.generate(source, tempDir.resolve("out.pdf")));
    }

    @Test
    void shouldRejectOversizedFile() throws Exception {
        OfficePdfPreviewGenerator generator = newGenerator(100);
        Path source = tempDir.resolve("big.docx");
        Files.write(source, new byte[200]);

        assertThrows(IllegalArgumentException.class,
                () -> generator.generate(source, tempDir.resolve("out.pdf")));
    }

    @Test
    void shouldConvertDocToPdfWhenSofficeAvailable() throws Exception {
        Assumptions.assumeTrue(isSofficeAvailable(), "soffice 不可用，跳过真实转换测试");
        OfficePdfPreviewGenerator generator = newGenerator(1024 * 1024);
        Path source = tempDir.resolve("note.doc");
        Files.write(source, "hello jcloud office preview".getBytes());
        Path target = tempDir.resolve("previews").resolve("note.pdf");

        generator.generate(source, target);

        assertTrue(Files.exists(target));
        byte[] bytes = Files.readAllBytes(target);
        assertTrue(bytes.length > 0);
        byte[] head = new byte[4];
        System.arraycopy(bytes, 0, head, 0, 4);
        assertArrayEquals("%PDF".getBytes(), head);
    }

    private OfficePdfPreviewGenerator newGenerator(long maxConvertSize) {
        PreviewProperties properties = new PreviewProperties();
        properties.setOfficeMaxConvertSize(maxConvertSize);
        OfficePdfPreviewGenerator generator = new OfficePdfPreviewGenerator(properties);
        generator.init();
        return generator;
    }

    private boolean isSofficeAvailable() {
        try {
            Process process = new ProcessBuilder("soffice", "--version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
