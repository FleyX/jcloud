package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.config.MediaProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 基于 ffmpeg 的真实编码探测默认实现。
 * <p>
 * 对候选硬解方式执行一次最短真实编码（lavfi testsrc 单帧），退出码 0 视为可用；
 * 超时 30 秒，进程异常/超时/非零退出一律视为不可用，不抛异常。stderr 摘要打 debug 日志。
 * <p>
 * 命令形态：nvenc/qsv 直接选编码器（qsv 由 oneVPL 自动选设备）；vaapi 需显式
 * {@code -vaapi_device} 挂设备并先 {@code format=nv12,hwupload} 上传硬解帧。
 */
@Slf4j
@Component
public class FfmpegHwaccelProbeExecutor implements HwaccelProbeExecutor {

    /**
     * 单次探测超时时间（秒）。
     */
    private static final long TIMEOUT_SECONDS = 30;

    /**
     * stderr 摘要保留的最大长度（字符），避免探测日志刷屏。
     */
    private static final int STDERR_SUMMARY_LIMIT = 500;

    private final MediaProperties mediaProperties;
    private final TranscodeConfigResolver transcodeConfigResolver;

    public FfmpegHwaccelProbeExecutor(MediaProperties mediaProperties,
                                      TranscodeConfigResolver transcodeConfigResolver) {
        this.mediaProperties = mediaProperties;
        this.transcodeConfigResolver = transcodeConfigResolver;
    }

    @Override
    public boolean probe(String hwaccel, String device) {
        String resolvedDevice = device == null || device.isBlank()
                ? transcodeConfigResolver.resolveDevice() : device;
        Process process = null;
        try {
            List<String> command = buildCommand(hwaccel, resolvedDevice);
            log.info("执行硬解探测: {}", String.join(" ", command));
            process = new ProcessBuilder(command).start();
            // 先 waitFor 限时等待再读 stderr：先读流会在进程挂死时阻塞到 EOF，超时永不生效；
            // -loglevel error 输出极小，不存在管道缓冲撑满导致 ffmpeg 无法退出的风险
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.debug("硬解探测超时(>{}s)，视为不可用: {}", TIMEOUT_SECONDS, hwaccel);
                return false;
            }
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() == 0) {
                log.debug("硬解探测成功: {} (device={})", hwaccel, resolvedDevice);
                return true;
            }
            log.debug("硬解探测失败(exit={})，视为不可用: {}；stderr: {}",
                    process.exitValue(), hwaccel, summarize(stderr));
            return false;
        } catch (Exception e) {
            log.debug("硬解探测异常，视为不可用: {} - {}", hwaccel, e.getMessage());
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 构建最短真实编码探测命令：lavfi testsrc 单帧直接编码，输出 null muxer。
     */
    private List<String> buildCommand(String hwaccel, String device) {
        List<String> command = new ArrayList<>(List.of(
                mediaProperties.getFfmpegPath(),
                "-hide_banner", "-loglevel", "error",
                "-f", "lavfi",
                "-i", "testsrc=size=128x128:duration=1",
                "-frames:v", "1"));
        switch (hwaccel.toLowerCase(Locale.ROOT)) {
            case "nvenc" -> command.addAll(List.of("-c:v", "h264_nvenc"));
            case "qsv" -> command.addAll(List.of("-c:v", "h264_qsv"));
            case "vaapi" -> command.addAll(List.of(
                    "-vaapi_device", device,
                    "-vf", "format=nv12,hwupload",
                    "-c:v", "h264_vaapi"));
            default -> throw new IllegalArgumentException("未知的硬解方式: " + hwaccel);
        }
        command.addAll(List.of("-f", "null", "-"));
        return command;
    }

    private static String summarize(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "";
        }
        String singleLine = stderr.replace('\n', ' ').trim();
        return singleLine.length() <= STDERR_SUMMARY_LIMIT
                ? singleLine : singleLine.substring(0, STDERR_SUMMARY_LIMIT) + "...";
    }
}
