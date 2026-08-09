package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * ffmpeg 转码进程生命周期支撑：启动进程（含 stderr 排空与远程流管道）、
 * 会话目录定位与清理、早期失败监控（软解回退）。
 * <p>
 * 软解回退需要替换转码会话注册表中的会话，通过调用方传入的注册表 Map 解耦，
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
     */
    public Process startFfmpeg(Path outputDir, TranscodeCommandBuilder.TranscodeRequest request,
                               String encoder) throws IOException {
        List<String> command = commandBuilder.buildCommand(request, encoder, configResolver.resolveDevice(),
                configResolver.resolveThreads(), outputDir);
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
     * 监控 ffmpeg 早期失败：auto 模式自动回退软解；显式指定的硬解方式失败则标记失败并提示用户修改配置。
     * 软解与转封装会话无硬解回退诉求，直接跳过监控。
     *
     * @param session  被监控会话
     * @param autoMode 是否 auto 硬解模式（允许回退软解）
     * @param request  会话请求（回退时复用）
     * @param sessions 会话注册表（用于判断会话是否已被回收、回退时替换会话）
     */
    public void watchEarlyFailure(TranscodeSession session, boolean autoMode,
                                  TranscodeCommandBuilder.TranscodeRequest request,
                                  Map<String, TranscodeSession> sessions) {
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
            if (!autoMode) {
                log.warn("显式指定的硬解方式启动失败: session={}, encoder={}", session.id(), session.encoder());
                session.failed(true);
                process.destroy();
                return;
            }
            log.warn("硬件加速转码启动失败，回退软解: session={}", session.id());
            try {
                Process fallback = startFfmpeg(session.outputDir(), request, "libx264");
                sessions.put(session.id(), new TranscodeSession(session.id(), session.userId(),
                        session.outputDir(), fallback, "libx264", Instant.now()));
            } catch (IOException e) {
                log.error("软解回退启动失败: session={}", session.id(), e);
            }
        });
    }
}
