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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * HLS 实时转码会话管理器：每个会话一个 ffmpeg 进程，空闲/未完成时回收，支持 Jellyfin 式节流。
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
    private final TranscodeCommandBuilder commandBuilder;
    private final TranscodeThrottleSupport throttleSupport;
    private final Semaphore concurrencyPermits;

    public TranscodeSessionManager(MediaProperties mediaProperties, SystemStorageSpaceProvider systemStorageSpaceProvider,
                                   SystemConfigService systemConfigService, TranscodeCommandBuilder commandBuilder,
                                   TranscodeThrottleSupport throttleSupport) {
        this.mediaProperties = mediaProperties;
        this.systemStorageSpaceProvider = systemStorageSpaceProvider;
        this.systemConfigService = systemConfigService;
        this.commandBuilder = commandBuilder;
        this.throttleSupport = throttleSupport;
        this.concurrencyPermits = new Semaphore(Math.max(1, mediaProperties.getTranscodeConcurrency()));
    }

    private final Map<String, TranscodeSession> sessions = new ConcurrentHashMap<>();

    /**
     * 创建转码会话并启动 ffmpeg。
     * <p>
     * 视频流满足转封装条件（白名单编码、未降码率、未强制转码）时按 -c:v copy 转封装，不做硬解探测。
     *
     * @param userId  用户 ID
     * @param request 会话请求（含 probe 出的视频/音轨编码与码率档位参数）
     * @return 会话
     */
    public TranscodeSession createSession(String userId, TranscodeCommandBuilder.TranscodeRequest request) {
        if (!concurrencyPermits.tryAcquire()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "转码并发数已达上限，请稍后再试");
        }
        String sessionId = cn.hutool.core.util.IdUtil.fastSimpleUUID();
        Path outputDir = resolveSessionRoot().resolve(sessionId);
        boolean started = false;
        try {
            Files.createDirectories(outputDir);
            boolean videoCopy = commandBuilder.isVideoCopyEligible(request.videoCodec(),
                    request.forceVideoTranscode(), request.targetBitrateKbps());
            String hwaccel = resolveHwaccel();
            boolean autoMode = !videoCopy && (hwaccel == null || hwaccel.isBlank() || "auto".equalsIgnoreCase(hwaccel));
            String encoder = videoCopy ? TranscodeCommandBuilder.ENCODER_COPY : commandBuilder.selectEncoder(hwaccel);
            Process process = startFfmpeg(outputDir, request, encoder);
            TranscodeSession session = new TranscodeSession(sessionId, userId, outputDir, process, encoder, Instant.now());
            sessions.put(sessionId, session);
            watchEarlyFailure(session, autoMode, request);
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
        // ffmpeg 会先创建 0 字节占位文件再写入内容，0 字节同样视为尚未生成
        if (!Files.exists(path) || isZeroByte(path)) {
            return null;
        }
        throttleSupport.updateMaxRequestedSegment(session, fileName);
        return path;
    }

    private boolean isZeroByte(Path path) {
        try {
            return Files.size(path) == 0;
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * 给 HLS 播放列表中的相对切片/初始化段 URI 追加 token 查询参数。
     * <p>
     * 播放列表 URL 携带的 ?token= 在切片相对地址解析时会丢失，导致切片请求 401；
     * 服务端返回 m3u8 前重写其中的相对 URI（含 #EXT-X-MAP 的 URI 属性），
     * 使 hls.js 与 Safari 原生 HLS 的切片请求都能携带凭证。
     *
     * @param content 播放列表文本
     * @param token   访问令牌，为空时原样返回
     * @return 重写后的播放列表文本
     */
    public static String appendTokenToPlaylist(String content, String token) {
        if (content == null || token == null || token.isBlank()) {
            return content;
        }
        String encoded = java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = rewritePlaylistLine(lines[i], encoded);
        }
        return String.join("\n", lines);
    }

    private static String rewritePlaylistLine(String line, String encodedToken) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return line;
        }
        if (trimmed.startsWith("#")) {
            int uriStart = line.indexOf("URI=\"");
            if (!trimmed.startsWith("#EXT-X-MAP:") || uriStart < 0) {
                return line;
            }
            int start = uriStart + 5;
            int end = line.indexOf('"', start);
            if (end < 0) {
                return line;
            }
            String uri = line.substring(start, end);
            if (uri.contains("://") || uri.contains("?")) {
                return line;
            }
            return line.substring(0, start) + uri + "?token=" + encodedToken + line.substring(end);
        }
        if (trimmed.contains("://") || trimmed.contains("?")) {
            return line;
        }
        return line + "?token=" + encodedToken;
    }

    /**
     * 播放页心跳：刷新会话心跳时间。心跳会话超过配置时间无新心跳将被回收（见 {@link #cleanupIdleSessions}）。
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID（校验会话归属）
     */
    public void heartbeat(String sessionId, String userId) {
        TranscodeSession session = sessions.get(sessionId);
        if (session == null || !session.userId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "转码会话不存在");
        }
        Instant now = Instant.now();
        session.lastHeartbeatAt(now);
        session.lastAccess(now);
    }

    /**
     * 关闭会话并清理资源（杀 ffmpeg、删缓存目录、释放并发许可）。
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
     * 关闭会话并清理资源，校验会话归属。会话不存在时静默返回（播放页退出时会话可能已被回收）。
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     */
    public void closeSession(String sessionId, String userId) {
        TranscodeSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        if (!session.userId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "转码会话不存在");
        }
        closeSession(sessionId);
    }

    /**
     * 定时回收空闲会话，双轨规则：
     * 收到过播放页心跳的会话按心跳超时（默认 90s）回收——播放页已退出或崩溃；
     * 从未收到心跳的会话（API 客户端等）沿用空闲超时规则（无切片请求超过配置分钟数）。
     */
    @Scheduled(fixedRate = 30_000)
    public void cleanupIdleSessions() {
        Instant now = Instant.now();
        Instant idleDeadline = now.minus(mediaProperties.getSessionIdleTimeoutMinutes(), ChronoUnit.MINUTES);
        Instant heartbeatDeadline = now.minus(mediaProperties.getSessionHeartbeatTimeoutSeconds(), ChronoUnit.SECONDS);
        sessions.entrySet().removeIf(entry -> {
            TranscodeSession session = entry.getValue();
            boolean idle = isSessionExpired(session, idleDeadline, heartbeatDeadline);
            if (idle) {
                destroy(session);
            }
            return idle;
        });
    }

    private boolean isSessionExpired(TranscodeSession session, Instant idleDeadline, Instant heartbeatDeadline) {
        Instant lastHeartbeat = session.lastHeartbeatAt();
        if (lastHeartbeat != null) {
            return lastHeartbeat.isBefore(heartbeatDeadline);
        }
        return throttleSupport.isSessionIdle(session, idleDeadline);
    }

    /**
     * 定时转码节流：根据客户端请求进度与 ffmpeg 已生成时长差距，暂停或继续进程。
     */
    @Scheduled(fixedRate = 5_000)
    public void throttleSessions() {
        if (!throttleSupport.isLinux()) {
            log.debug("非 Linux 系统，跳过转码节流调度");
            return;
        }
        int aheadSeconds = mediaProperties.getTranscodeThrottleAheadSeconds();
        int resumeSeconds = mediaProperties.getTranscodeThrottleResumeSeconds();
        for (TranscodeSession session : sessions.values()) {
            if (!session.process().isAlive()) {
                continue;
            }
            String m3u8Content = throttleSupport.readM3u8Content(session.outputDir().resolve("index.m3u8"));
            double generated = throttleSupport.sumExtinfSeconds(m3u8Content);
            int maxIndex = session.maxRequestedSegmentIndex().get();
            double consumed = throttleSupport.sumExtinfSeconds(m3u8Content, Math.max(0, maxIndex + 1));
            double ahead = generated - consumed;
            TranscodeThrottleSupport.ThrottleDecision decision = throttleSupport.decideThrottle(
                    generated, consumed, aheadSeconds, resumeSeconds);
            if (decision == TranscodeThrottleSupport.ThrottleDecision.PAUSE
                    && session.pauseState() != TranscodeSession.PauseState.PAUSED) {
                log.info("转码进程领先过多，准备暂停: session={}, ahead={}s", session.id(), ahead);
                throttleSupport.sendSignal(session, "STOP", "暂停");
                if (session.pauseState() != TranscodeSession.PauseState.UNKNOWN) {
                    session.pauseState(TranscodeSession.PauseState.PAUSED);
                }
            } else if (decision == TranscodeThrottleSupport.ThrottleDecision.RESUME
                    && session.pauseState() == TranscodeSession.PauseState.PAUSED) {
                log.info("转码进程已追近，准备继续: session={}, ahead={}s", session.id(), ahead);
                throttleSupport.sendSignal(session, "CONT", "继续");
                if (session.pauseState() != TranscodeSession.PauseState.UNKNOWN) {
                    session.pauseState(TranscodeSession.PauseState.RUNNING);
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        sessions.values().forEach(this::destroy);
        sessions.clear();
    }

    private void destroy(TranscodeSession session) {
        if (throttleSupport.isLinux() && session.pauseState() == TranscodeSession.PauseState.PAUSED) {
            throttleSupport.sendSignal(session, "CONT", "恢复");
        }
        session.process().destroy();
        try {
            if (!session.process().waitFor(5, TimeUnit.SECONDS)) {
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
     * 软解与转封装会话无硬解回退诉求，直接跳过监控。
     */
    private void watchEarlyFailure(TranscodeSession session, boolean autoMode,
                                   TranscodeCommandBuilder.TranscodeRequest request) {
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

    private Process startFfmpeg(Path outputDir, TranscodeCommandBuilder.TranscodeRequest request,
                                String encoder) throws IOException {
        List<String> command = commandBuilder.buildCommand(request, encoder, resolveDevice(), resolveThreads(), outputDir);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        Process process = builder.start();
        Thread.startVirtualThread(() -> {
            try {
                process.getErrorStream().transferTo(java.io.OutputStream.nullOutputStream());
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

}
