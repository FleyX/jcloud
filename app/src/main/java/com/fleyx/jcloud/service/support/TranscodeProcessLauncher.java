package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * ffmpeg 转码进程生命周期支撑：启动进程（含 stderr 排空与远程流管道）、
 * 会话目录定位与清理、早期失败监控（失败标记）。
 * <p>
 * 早期失败监控通过调用方传入的注册表 Map 判断会话是否已被回收，
 * 本类不反向持有 TranscodeSessionManager。
 */
@Slf4j
@Component
public class TranscodeProcessLauncher {

    private static final String SESSION_ROOT = "media/transcode";

    private final SystemStorageSpaceProvider systemStorageSpaceProvider;
    private final TranscodeCommandBuilder commandBuilder;
    private final TranscodeConfigResolver configResolver;

    public TranscodeProcessLauncher(SystemStorageSpaceProvider systemStorageSpaceProvider,
                                    TranscodeCommandBuilder commandBuilder, TranscodeConfigResolver configResolver) {
        this.systemStorageSpaceProvider = systemStorageSpaceProvider;
        this.commandBuilder = commandBuilder;
        this.configResolver = configResolver;
    }

    /**
     * 启动 ffmpeg 进程并排空 stderr；远程输入流在独立虚拟线程中管道喂给进程 stdin。
     * 远程外挂位图字幕先物化到会话输出目录（destroy 时递归删除即自动清理），命令以物化文件为第二输入。
     */
    public Process startFfmpeg(Path outputDir, TranscodeCommandBuilder.TranscodeRequest request,
                               String encoder) throws IOException {
        TranscodeCommandBuilder.TranscodeRequest effective = request;
        if (request.externalSubtitleStream() != null) {
            effective = request.withExternalSubtitlePath(
                    materializeExternalSubtitle(outputDir, request.externalSubtitleStream()));
        }
        List<String> command = commandBuilder.buildCommand(effective, encoder, configResolver.resolveDevice(),
                configResolver.resolveThreads(), outputDir);
        log.info("启动转码进程: encoder={}, outputDir={}, command={}", encoder, outputDir, String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        Process process = builder.start();
        Thread.startVirtualThread(() -> {
            try {
                process.getErrorStream().transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {
            }
        });
        if (request.remoteStream() != null) {
            Thread.startVirtualThread(() -> {
                try (InputStream in = request.remoteStream().get(); var out = process.getOutputStream()) {
                    in.transferTo(out);
                } catch (Exception ignored) {
                    // ffmpeg 提前退出导致管道关闭，忽略
                }
            });
        }
        return process;
    }

    /**
     * 远程外挂位图字幕物化到会话输出目录：文件名 {主名安全化}.{ext}，
     * idx 成对时 .idx/.sub 用相同主名（VobSub demuxer 按同主名找 .sub）。下载失败清理会话目录并抛系统异常。
     */
    private Path materializeExternalSubtitle(Path outputDir,
                                             TranscodeCommandBuilder.ExternalSubtitleStream stream) {
        String stem = sanitizeFileStem(stream.mainName());
        try {
            Path main = outputDir.resolve(stem + "." + stream.extension());
            try (InputStream in = stream.stream().get()) {
                Files.copy(in, main, StandardCopyOption.REPLACE_EXISTING);
            }
            if (stream.subStream() != null) {
                Path sub = outputDir.resolve(stem + ".sub");
                try (InputStream in = stream.subStream().get()) {
                    Files.copy(in, sub, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return main;
        } catch (Exception e) {
            deleteQuietly(outputDir);
            throw new SystemException(ResultCode.SYSTEM_ERROR, "外部字幕下载失败", e);
        }
    }

    /**
     * 物化文件名主名安全化：替换路径分隔符与控制字符等危险字符，空结果回退固定名。
     */
    private String sanitizeFileStem(String mainName) {
        String safe = mainName == null ? "" : mainName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return safe.isBlank() ? "subtitle" : safe;
    }

    /**
     * 定位会话输出根目录。
     */
    public Path resolveSessionRoot() {
        StorageSpace space = systemStorageSpaceProvider.getSystemSpace();
        return Path.of(space.getPath(), "system", SESSION_ROOT);
    }

    /**
     * 递归删除目录（尽力而为，失败仅记录忽略）。
     */
    public void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> -a.compareTo(b)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /**
     * 监控 ffmpeg 早期失败：启动失败时标记会话失败并提示用户修改配置，不自动回退软解。
     * 软解与转封装会话无硬解失败诉求，直接跳过监控。
     *
     * @param session  被监控会话
     * @param sessions 会话注册表（用于判断会话是否已被回收）
     */
    public void watchEarlyFailure(TranscodeSession session, Map<String, TranscodeSession> sessions) {
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Process process = session.process();
            boolean producedOutput;
            try (var stream = Files.list(session.outputDir())) {
                producedOutput = stream.findAny().isPresent();
            } catch (IOException e) {
                producedOutput = false;
            }
            if (process.isAlive() || producedOutput || !sessions.containsKey(session.id())
                    || "libx264".equals(session.encoder())
                    || TranscodeCommandBuilder.ENCODER_COPY.equals(session.encoder())) {
                return;
            }
            log.warn("显式指定的硬解方式启动失败: session={}, encoder={}", session.id(), session.encoder());
            session.failed(true);
            process.destroy();
        });
    }
}
