package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.SystemException;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
     * ffmpeg stderr 尾部环形缓冲：固定保留最后若干行，仅用于早期失败时输出诊断日志，
     * 正常路径只覆盖写、开销可忽略。线程安全（排空线程写、监控线程读）。
     */
    static final class StderrTail {

        private static final int MAX_LINES = 10;

        private final java.util.Deque<String> lines = new java.util.ArrayDeque<>(MAX_LINES + 1);

        synchronized void append(String line) {
            if (lines.size() == MAX_LINES) {
                lines.pollFirst();
            }
            lines.addLast(line);
        }

        synchronized String tail() {
            return String.join(" | ", lines);
        }
    }

    /**
     * 启动 ffmpeg 进程并排空 stderr（尾部行存入 stderrTail 供失败诊断）；远程输入流在独立虚拟线程中管道喂给进程 stdin。
     * 远程外挂位图字幕先物化到会话输出目录（destroy 时递归删除即自动清理），命令以物化文件为第二输入。
     *
     * @param hwDecode 是否硬解码（透传给 {@link TranscodeCommandBuilder#buildCommand}；降级重试时为 false）
     */
    public Process startFfmpeg(Path outputDir, TranscodeCommandBuilder.TranscodeRequest request,
                               String encoder, StderrTail stderrTail, boolean hwDecode) throws IOException {
        TranscodeCommandBuilder.TranscodeRequest effective = request;
        if (request.externalSubtitleStream() != null) {
            effective = request.withExternalSubtitlePath(
                    materializeExternalSubtitle(outputDir, request.externalSubtitleStream()));
        }
        List<String> command = commandBuilder.buildCommand(effective, encoder, configResolver.resolveDevice(),
                configResolver.resolveThreads(), outputDir, hwDecode);
        log.info("启动转码进程: encoder={}, outputDir={}, command={}", encoder, outputDir, String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        Process process = builder.start();
        Thread.startVirtualThread(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderrTail.append(line);
                }
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
     * 早期失败监控默认窗口（毫秒）：启动后窗口内进程退出且无有效产出视为早期失败。
     */
    static final long EARLY_FAILURE_WINDOW_MS = 10_000;

    /**
     * 可触发降级软解重试的硬件编码器集合（三种纯硬解路径：cuda/qsv/vaapi；黑名单 key 的 encoder 段天然区分）。
     */
    private static final Set<String> FALLBACK_ELIGIBLE_ENCODERS =
            Set.of("h264_nvenc", "h264_qsv", "h264_vaapi");

    /**
     * 有效产出文件判定：只认 m3u8 播放列表、fMP4 初始化段与 m4s 切片。
     * 外挂字幕物化会向输出目录写入 .sup/.idx，不能据此误判转码已产出。
     */
    private static boolean isValidOutputName(Path file) {
        String name = file.getFileName().toString();
        return "index.m3u8".equals(name) || "init.mp4".equals(name) || name.endsWith(".m4s");
    }

    /**
     * 监控 ffmpeg 早期失败：窗口内进程已退出且无有效产出时，硬件编码（cuda/qsv/vaapi）会话先降级软解
     * （hwDecode=false）重建进程重试一次并记降级结论；其余情况标记会话失败。软解与转封装会话无硬解诉求，直接跳过。
     *
     * @param session  被监控会话
     * @param sessions 会话注册表（用于判断会话是否已被回收）
     */
    public void watchEarlyFailure(TranscodeSession session, Map<String, TranscodeSession> sessions,
                                  StderrTail stderrTail) {
        watchEarlyFailure(session, sessions, stderrTail, EARLY_FAILURE_WINDOW_MS, new ConcurrentHashMap<>());
    }

    /**
     * 早期失败监控的参数化实现：窗口与降级黑名单由调用方指定
     * （测试注入短窗口与共享黑名单；生产入口由 {@link TranscodeSessionManager} 传入共享黑名单以复用降级结论）。
     */
    void watchEarlyFailure(TranscodeSession session, Map<String, TranscodeSession> sessions,
                           StderrTail stderrTail, long windowMillis, Map<String, Boolean> blacklist) {
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(windowMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Process process = session.process();
            boolean producedOutput;
            try (var stream = Files.list(session.outputDir())) {
                producedOutput = stream.anyMatch(TranscodeProcessLauncher::isValidOutputName);
            } catch (IOException e) {
                producedOutput = false;
            }
            if (process.isAlive() || producedOutput || !sessions.containsKey(session.id())
                    || "libx264".equals(session.encoder())
                    || TranscodeCommandBuilder.ENCODER_COPY.equals(session.encoder())) {
                return;
            }
            // 仅硬解编码器且未降级过且持有 request 才降级重试；否则维持现状标失败
            TranscodeCommandBuilder.TranscodeRequest request = session.request();
            if (!FALLBACK_ELIGIBLE_ENCODERS.contains(session.encoder())
                    || session.decodeFallback() || request == null) {
                log.warn("显式指定的硬解方式启动失败: session={}, encoder={}, ffmpeg stderr: {}",
                        session.id(), session.encoder(), stderrTail.tail());
                session.failed(true);
                process.destroy();
                return;
            }
            // 降级重试：以 hwDecode=false 重建命令（软解+硬编码）重启，替换会话进程、置降级标记、
            // 对本地文件按 localPath|videoCodec|encoder 记黑名单，并对新进程递归挂同样监控
            try {
                StderrTail restartedTail = new StderrTail();
                Process restarted = startFfmpeg(session.outputDir(), request, session.encoder(), restartedTail, false);
                session.process(restarted);
                session.decodeFallback(true);
                if (request.localPath() != null) {
                    blacklist.put(request.localPath() + "|" + request.videoCodec() + "|" + session.encoder(),
                            Boolean.TRUE);
                }
                log.info("硬件转码早期失败，降级软解重试: session={}, encoder={}", session.id(), session.encoder());
                watchEarlyFailure(session, sessions, restartedTail, windowMillis, blacklist);
            } catch (IOException e) {
                log.error("降级重试启动失败: session={}, encoder={}", session.id(), session.encoder(), e);
                session.failed(true);
                process.destroy();
            }
        });
    }
}
