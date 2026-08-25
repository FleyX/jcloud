package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.enums.PreviewType;
import com.fleyx.jcloud.config.MediaProperties;
import com.fleyx.jcloud.service.FilePreviewGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 视频海报帧生成器。
 * <p>
 * 使用 ffmpeg（路径走 {@link MediaProperties#getFfmpegPath()}，默认从 PATH 查找）抽取视频第 1 秒的关键帧作为海报。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoPosterGenerator implements FilePreviewGenerator {

    private final MediaProperties mediaProperties;

    @Override
    public PreviewType supportedType() {
        return PreviewType.POSTER;
    }

    @Override
    public void generate(Path sourcePath, Path targetPath) throws Exception {
        java.nio.file.Files.createDirectories(targetPath.getParent());
        List<String> command = List.of(
                mediaProperties.getFfmpegPath(),
                "-y",
                "-ss", "00:00:01",
                "-i", sourcePath.toAbsolutePath().toString(),
                "-frames:v", "1",
                "-q:v", "2",
                "-update", "1",
                targetPath.toAbsolutePath().toString()
        );
        log.info("生成视频海报帧: {}", String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command);
        // 合并 stderr 后整体丢弃，避免 ffmpeg 的媒体信息刷屏应用日志
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("ffmpeg 生成海报超时");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("ffmpeg 生成海报失败");
        }
    }
}
