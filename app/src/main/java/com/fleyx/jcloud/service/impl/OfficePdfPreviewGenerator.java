package com.fleyx.jcloud.service.impl;

import cn.hutool.core.io.FileUtil;
import com.fleyx.jcloud.common.enums.PreviewType;
import com.fleyx.jcloud.config.PreviewProperties;
import com.fleyx.jcloud.service.FilePreviewGenerator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Office 文档预览生成器。
 * <p>
 * 通过 LibreOffice（soffice）将 doc/docx/xls/xlsx/ppt/pptx 转换为 PDF。
 * 受大小上限、超时时间与全局并发数三重限制；
 * 每次转换使用独立的 soffice user profile 目录以支持并发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfficePdfPreviewGenerator implements FilePreviewGenerator {

    /**
     * 支持在线转换为 PDF 的 Office 扩展名。
     */
    public static final Set<String> CONVERTIBLE_EXTENSIONS =
            Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx");

    private final PreviewProperties previewProperties;

    private Semaphore convertSemaphore;

    @PostConstruct
    void init() {
        convertSemaphore = new Semaphore(previewProperties.getMaxConcurrentConversions());
    }

    @Override
    public PreviewType supportedType() {
        return PreviewType.OFFICE;
    }

    @Override
    public void generate(Path sourcePath, Path targetPath) throws Exception {
        String ext = FileUtil.extName(sourcePath.toFile()).toLowerCase();
        if (!CONVERTIBLE_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("不支持的 Office 格式：" + ext);
        }
        long size = Files.size(sourcePath);
        if (size > previewProperties.getOfficeMaxConvertSize()) {
            throw new IllegalArgumentException("文件过大，请下载后查看");
        }
        if (!convertSemaphore.tryAcquire()) {
            throw new IllegalStateException("当前转换任务繁忙，请稍后重试");
        }
        try {
            convertWithLibreOffice(sourcePath, targetPath);
        } finally {
            convertSemaphore.release();
        }
    }

    /**
     * 调用 soffice 执行转换，输出 PDF 到目标路径。
     */
    private void convertWithLibreOffice(Path sourcePath, Path targetPath) throws Exception {
        Path workDir = Files.createTempDirectory("jcloud-office-convert-");
        Path profileDir = Files.createTempDirectory("jcloud-soffice-profile-");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    previewProperties.getLibreofficePath(),
                    "--headless", "--norestore", "--nolockcheck",
                    "-env:UserInstallation=" + profileDir.toUri(),
                    "--convert-to", "pdf",
                    "--outdir", workDir.toString(),
                    sourcePath.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(previewProperties.getConvertTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("转换超时，请下载后查看");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                log.warn("LibreOffice 转换失败，exitValue={}，output={}", process.exitValue(), output);
                throw new IllegalStateException("文档转换失败，请下载后查看");
            }
            Path produced = findProducedPdf(workDir, sourcePath);
            Files.createDirectories(targetPath.getParent());
            Files.move(produced, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Office 文档转换完成：{} -> {}", sourcePath.getFileName(), targetPath.getFileName());
        } finally {
            FileUtil.del(workDir);
            FileUtil.del(profileDir);
        }
    }

    /**
     * 定位转换产物：soffice 输出文件名为源文件主名 + .pdf，找不到时兜底取目录下唯一 PDF。
     */
    private Path findProducedPdf(Path workDir, Path sourcePath) throws Exception {
        Path expected = workDir.resolve(FileUtil.mainName(sourcePath.toFile()) + ".pdf");
        if (Files.exists(expected)) {
            return expected;
        }
        try (Stream<Path> stream = Files.list(workDir)) {
            return stream.filter(p -> p.toString().endsWith(".pdf"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("文档转换失败，未生成 PDF 产物"));
        }
    }
}
