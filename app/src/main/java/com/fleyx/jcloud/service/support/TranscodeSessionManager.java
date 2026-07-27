package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.config.MediaProperties;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.service.SystemConfigService;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * HLS 实时转码会话管理器。
 * <p>
 * 每个播放会话对应一个 ffmpeg 进程与独立输出目录，空闲超时自动回收。
 */
@Slf4j
@Component
public class TranscodeSessionManager {

    private static final String SESSION_ROOT = "media/transcode";

    /**
     * 系统配置键：硬件加速方式（auto / vaapi / qsv / nvenc / none）。
     */
    public static final String CONFIG_KEY_HWACCEL = "media.transcode.hwaccel";

    /**
     * 系统配置键：硬件设备路径（仅 vaapi 使用）。
     */
    public static final String CONFIG_KEY_DEVICE = "media.transcode.device";

    /**
     * 系统配置键：ffmpeg 单任务线程数，0 表示自动。
     */
    public static final String CONFIG_KEY_THREADS = "media.transcode.threads";

    private static final String DEFAULT_DEVICE = "/dev/dri/renderD128";

    private final MediaProperties mediaProperties;
    private final SystemStorageSpaceProvider systemStorageSpaceProvider;
    private final SystemConfigService systemConfigService;
    private final Semaphore concurrencyPermits;

    public TranscodeSessionManager(MediaProperties mediaProperties, SystemStorageSpaceProvider systemStorageSpaceProvider,
                                   SystemConfigService systemConfigService) {
        this.mediaProperties = mediaProperties;
        this.systemStorageSpaceProvider = systemStorageSpaceProvider;
        this.systemConfigService = systemConfigService;
        this.concurrencyPermits = new Semaphore(Math.max(1, mediaProperties.getTranscodeConcurrency()));
    }

    /**
     * 转码会话。
     */
    public static final class TranscodeSession {
        private final String id;
        private final String userId;
        private final Path outputDir;
        private final Process process;
        private final String encoder;
        private volatile Instant lastAccess;
        private volatile boolean failed;

        public TranscodeSession(String id, String userId, Path outputDir, Process process,
                                String encoder, Instant lastAccess) {
            this.id = id;
            this.userId = userId;
            this.outputDir = outputDir;
            this.process = process;
            this.encoder = encoder;
            this.lastAccess = lastAccess;
        }

        public String id() {
            return id;
        }

        public String userId() {
            return userId;
        }

        public Path outputDir() {
            return outputDir;
        }

        public Process process() {
            return process;
        }

        public String encoder() {
            return encoder;
        }

        public Instant lastAccess() {
            return lastAccess;
        }

        public void lastAccess(Instant lastAccess) {
            this.lastAccess = lastAccess;
        }

        public boolean failed() {
            return failed;
        }

        public void failed(boolean failed) {
            this.failed = failed;
        }
    }

    private final Map<String, TranscodeSession> sessions = new ConcurrentHashMap<>();

    /**
     * 创建转码会话并启动 ffmpeg。
     *
     * @param userId        用户 ID
     * @param startMs       起始播放位置（毫秒）
     * @param audioIndex    音轨序号，null 表示默认音轨
     * @param localPath     本地物理路径，远程文件为 null
     * @param remoteStream  远程文件输入流提供者，本地文件为 null
     * @return 会话
     */
    public TranscodeSession createSession(String userId, long startMs, Integer audioIndex,
                                          Path localPath, Supplier<InputStream> remoteStream) {
        if (!concurrencyPermits.tryAcquire()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "转码并发数已达上限，请稍后再试");
        }
        String sessionId = cn.hutool.core.util.IdUtil.fastSimpleUUID();
        Path outputDir = resolveSessionRoot().resolve(sessionId);
        boolean started = false;
        try {
            Files.createDirectories(outputDir);
            String hwaccel = resolveHwaccel();
            boolean autoMode = hwaccel == null || hwaccel.isBlank() || "auto".equalsIgnoreCase(hwaccel);
            String encoder = selectEncoder(hwaccel);
            Process process = startFfmpeg(outputDir, startMs, audioIndex, localPath, remoteStream, encoder);
            TranscodeSession session = new TranscodeSession(sessionId, userId, outputDir, process, encoder, Instant.now());
            sessions.put(sessionId, session);
            watchEarlyFailure(session, autoMode, startMs, audioIndex, localPath, remoteStream);
            started = true;
            return session;
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "转码启动失败: " + e.getMessage());
        } finally {
            if (!started) {
                concurrencyPermits.release();
                deleteQuietly(outputDir);
            }
        }
    }

    /**
     * 获取会话文件（播放列表或切片），并刷新访问时间。
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @param fileName  文件名
     * @return 文件路径，尚不存在返回 null
     */
    public Path touchAndResolve(String sessionId, String userId, String fileName) {
        TranscodeSession session = sessions.get(sessionId);
        if (session == null || !session.userId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "转码会话不存在");
        }
        if (session.failed()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR,
                    "硬件转码启动失败，请检查「系统-影视」中的硬解配置或改用软解");
        }
        session.lastAccess(Instant.now());
        if (fileName.contains("..") || fileName.contains("/")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "非法文件名");
        }
        Path path = session.outputDir().resolve(fileName);
        return Files.exists(path) ? path : null;
    }

    /**
     * 关闭会话并清理资源。
     *
     * @param sessionId 会话 ID
     */
    public void closeSession(String sessionId) {
        TranscodeSession session = sessions.remove(sessionId);
        if (session != null) {
            destroy(session);
        }
    }

    /**
     * 定时回收空闲会话。
     */
    @Scheduled(fixedRate = 60_000)
    public void cleanupIdleSessions() {
        Instant deadline = Instant.now().minus(mediaProperties.getSessionIdleTimeoutMinutes(), ChronoUnit.MINUTES);
        sessions.entrySet().removeIf(entry -> {
            TranscodeSession session = entry.getValue();
            boolean idle = session.lastAccess().isBefore(deadline) || !session.process().isAlive();
            if (idle) {
                destroy(session);
            }
            return idle;
        });
    }

    @PreDestroy
    public void shutdown() {
        sessions.values().forEach(this::destroy);
        sessions.clear();
    }

    private void destroy(TranscodeSession session) {
        session.process().destroy();
        try {
            if (!session.process().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                session.process().destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.process().destroyForcibly();
        }
        deleteQuietly(session.outputDir());
        concurrencyPermits.release();
    }

    /**
     * 监控 ffmpeg 早期失败：auto 模式自动回退软解；显式指定的硬解方式失败则标记失败并提示用户修改配置。
     */
    private void watchEarlyFailure(TranscodeSession session, boolean autoMode, long startMs, Integer audioIndex,
                                   Path localPath, Supplier<InputStream> remoteStream) {
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
                    || "libx264".equals(session.encoder())) {
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
                Process fallback = startFfmpeg(session.outputDir(), startMs, audioIndex, localPath, remoteStream, "libx264");
                sessions.put(session.id(), new TranscodeSession(session.id(), session.userId(),
                        session.outputDir(), fallback, "libx264", Instant.now()));
            } catch (IOException e) {
                log.error("软解回退启动失败: session={}", session.id(), e);
            }
        });
    }

    /**
     * 读取当前生效的硬解方式：优先系统配置表，缺省回退 yml 配置。
     */
    public String resolveHwaccel() {
        String configured = systemConfigService.getValue(CONFIG_KEY_HWACCEL, "");
        if (configured == null || configured.isBlank()) {
            configured = mediaProperties.getHwaccel();
        }
        return configured;
    }

    /**
     * 读取当前生效的硬件设备路径。
     */
    public String resolveDevice() {
        String device = systemConfigService.getValue(CONFIG_KEY_DEVICE, "");
        return device == null || device.isBlank() ? DEFAULT_DEVICE : device.trim();
    }

    /**
     * 读取当前生效的 ffmpeg 单任务线程数，0 表示自动。
     */
    public int resolveThreads() {
        String value = systemConfigService.getValue(CONFIG_KEY_THREADS, "0");
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String selectEncoder(String configured) {
        if (configured == null || configured.isBlank() || "none".equalsIgnoreCase(configured)) {
            return "libx264";
        }
        if (!"auto".equalsIgnoreCase(configured)) {
            return switch (configured.toLowerCase()) {
                case "vaapi" -> "h264_vaapi";
                case "qsv" -> "h264_qsv";
                case "nvenc" -> "h264_nvenc";
                default -> "libx264";
            };
        }
        // auto：按可用性探测
        for (String candidate : List.of("h264_vaapi", "h264_qsv", "h264_nvenc")) {
            if (encoderAvailable(candidate)) {
                return candidate;
            }
        }
        return "libx264";
    }

    private volatile List<String> availableEncoders;

    private boolean encoderAvailable(String encoder) {
        if (availableEncoders == null) {
            synchronized (this) {
                if (availableEncoders == null) {
                    availableEncoders = detectEncoders();
                }
            }
        }
        return availableEncoders.contains(encoder);
    }

    private List<String> detectEncoders() {
        try {
            Process process = new ProcessBuilder(mediaProperties.getFfmpegPath(), "-hide_banner", "-encoders").start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            List<String> result = new ArrayList<>();
            for (String encoder : List.of("h264_vaapi", "h264_qsv", "h264_nvenc")) {
                if (output.contains(encoder)) {
                    result.add(encoder);
                }
            }
            log.info("可用硬件编码器: {}", result);
            return result;
        } catch (Exception e) {
            log.warn("ffmpeg 编码器探测失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Process startFfmpeg(Path outputDir, long startMs, Integer audioIndex,
                                Path localPath, Supplier<InputStream> remoteStream, String encoder) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(mediaProperties.getFfmpegPath());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("warning");
        if ("h264_vaapi".equals(encoder)) {
            command.addAll(List.of("-vaapi_device", resolveDevice()));
        }
        if (startMs > 0) {
            command.addAll(List.of("-ss", String.format(java.util.Locale.ROOT, "%.3f", startMs / 1000.0)));
        }
        command.addAll(List.of("-i", localPath != null ? localPath.toAbsolutePath().toString() : "pipe:0"));
        command.addAll(List.of("-map", "0:v:0"));
        if (audioIndex != null) {
            command.addAll(List.of("-map", "0:a:" + audioIndex));
        } else {
            command.addAll(List.of("-map", "0:a:0?"));
        }
        command.addAll(List.of("-c:v", encoder));
        switch (encoder) {
            case "h264_vaapi" -> command.addAll(List.of("-vf", "format=nv12,hwupload"));
            case "h264_qsv" -> command.addAll(List.of("-vf", "format=nv12", "-preset", "veryfast", "-global_quality", "23"));
            case "h264_nvenc" -> command.addAll(List.of("-preset", "p4", "-cq", "23"));
            default -> command.addAll(List.of("-preset", "veryfast", "-crf", "23"));
        }
        int threads = resolveThreads();
        if (threads > 0) {
            command.addAll(List.of("-threads", String.valueOf(threads)));
        }
        command.addAll(List.of("-c:a", "aac", "-b:a", "128k", "-ac", "2"));
        command.addAll(List.of("-f", "hls",
                "-hls_time", String.valueOf(mediaProperties.getHlsSegmentSeconds()),
                "-hls_segment_type", "fmp4",
                "-hls_flags", "independent_segments",
                "-hls_segment_filename", outputDir.resolve("seg_%05d.m4s").toString(),
                outputDir.resolve("index.m3u8").toString()));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        Process process = builder.start();
        Thread.startVirtualThread(() -> {
            try {
                process.getErrorStream().transferTo(OutputStreamNull.INSTANCE);
            } catch (IOException ignored) {
            }
        });
        if (remoteStream != null) {
            Thread.startVirtualThread(() -> {
                try (InputStream in = remoteStream.get(); var out = process.getOutputStream()) {
                    in.transferTo(out);
                } catch (Exception ignored) {
                    // ffmpeg 提前退出导致管道关闭，忽略
                }
            });
        }
        return process;
    }

    private Path resolveSessionRoot() {
        StorageSpace space = systemStorageSpaceProvider.getSystemSpace();
        return Path.of(space.getPath(), "system", SESSION_ROOT);
    }

    private void deleteQuietly(Path dir) {
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

    private static final class OutputStreamNull extends java.io.OutputStream {
        private static final OutputStreamNull INSTANCE = new OutputStreamNull();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
