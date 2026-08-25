package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.config.MediaProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HLS 实时转码会话管理器：每个会话一个 ffmpeg 进程，空闲/未完成时回收，支持 Jellyfin 式节流。
 * <p>
 * 会话生命周期（创建/心跳/空闲回收/清理）在本类，转码调度（节流）与早期失败监控（失败标记）、
 * 配置解析、播放列表 token 重写分别在 {@link TranscodeProcessLauncher}、
 * {@link TranscodeConfigResolver}、{@link TranscodePlaylistSupport}。
 * 播放列表预热（m3u8 请求等待攒够起始切片再响应）也由本类承载，见
 * {@link #awaitPlaylistWarmup(String, String)}。
 */
@Slf4j
@Component
public class TranscodeSessionManager {

    private final MediaProperties mediaProperties;
    private final TranscodeCommandBuilder commandBuilder;
    private final TranscodeThrottleSupport throttleSupport;
    private final TranscodeConfigResolver configResolver;
    private final TranscodeProcessLauncher processLauncher;
    private final Semaphore concurrencyPermits;

    /**
     * 已降级软解的会话黑名单：key = localPath|videoCodec|encoder，记录 launcher 早期失败降级结论，
     * 供后续同源 seek 重启直接沿用（S4 在 {@link #createSession} 命中时以 hwDecode=false 启动）。
     * 远程文件 localPath 为 null 不缓存；服务重启自然清空，不做持久化。
     */
    private final Map<String, Boolean> decodeFallbackBlacklist = new ConcurrentHashMap<>();

    public TranscodeSessionManager(MediaProperties mediaProperties, TranscodeCommandBuilder commandBuilder,
                                   TranscodeThrottleSupport throttleSupport, TranscodeConfigResolver configResolver,
                                   TranscodeProcessLauncher processLauncher) {
        this.mediaProperties = mediaProperties;
        this.commandBuilder = commandBuilder;
        this.throttleSupport = throttleSupport;
        this.configResolver = configResolver;
        this.processLauncher = processLauncher;
        this.concurrencyPermits = new Semaphore(Math.max(1, mediaProperties.getTranscodeConcurrency()));
    }

    /**
     * 播放列表预热轮询间隔（毫秒）。
     */
    private static final long PLAYLIST_WARMUP_POLL_INTERVAL_MS = 300;

    /**
     * HLS 播放列表结束标记。
     */
    private static final String HLS_ENDLIST_MARK = "#EXT-X-ENDLIST";

    /**
     * 播放列表切片条目标记（每个 #EXTINF 行对应一个切片）。
     */
    private static final Pattern EXTINF_ENTRY_PATTERN = Pattern.compile("#EXTINF", Pattern.CASE_INSENSITIVE);

    final Map<String, TranscodeSession> sessions = new ConcurrentHashMap<>();

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
        Path outputDir = processLauncher.resolveSessionRoot().resolve(sessionId);
        boolean started = false;
        try {
            Files.createDirectories(outputDir);
            boolean videoCopy = commandBuilder.isVideoCopyEligible(request.videoCodec(),
                    request.forceVideoTranscode(), request.targetBitrateKbps(), request.subtitleIndex(),
                    request.externalSubtitlePath(), request.externalSubtitleStream());
            String hwaccel = configResolver.resolveHwaccel();
            String encoder = videoCopy ? TranscodeCommandBuilder.ENCODER_COPY : commandBuilder.selectEncoder(hwaccel);
            TranscodeProcessLauncher.StderrTail stderrTail = new TranscodeProcessLauncher.StderrTail();
            // 黑名单命中（本地文件 + 编码器一致）直启降级软解命令；远程文件 localPath=null 不查表，恒硬解
            boolean hwDecode = request.localPath() == null
                    || !Boolean.TRUE.equals(decodeFallbackBlacklist.get(
                            request.localPath() + "|" + request.videoCodec() + "|" + encoder));
            Process process = processLauncher.startFfmpeg(outputDir, request, encoder, stderrTail, hwDecode);
            TranscodeSession session = new TranscodeSession(sessionId, userId, outputDir, process, encoder, Instant.now());
            session.setRequest(request);
            sessions.put(sessionId, session);
            processLauncher.watchEarlyFailure(session, sessions, stderrTail,
                    TranscodeProcessLauncher.EARLY_FAILURE_WINDOW_MS, decodeFallbackBlacklist);
            started = true;
            return session;
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "转码启动失败: " + e.getMessage());
        } finally {
            if (!started) {
                concurrencyPermits.release();
                processLauncher.deleteQuietly(outputDir);
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
     * 播放列表预热等待：未完成的转码会话 m3u8 请求轮询等待 playlist 攒够起始切片数再响应，
     * 避免 hls.js 起播时切片不足贴直播边缘卡顿（hls.js 仅在 playlist 重载时发现新切片，
     * 重载间隔约等于切片时长，起播切片不足会开播即卡）。
     * <p>
     * 轮询中满足以下任一条件即按当前内容返回：切片数 ≥ 阈值；playlist 含
     * #EXT-X-ENDLIST（转码完成）；ffmpeg 进程已退出；累计等待超过预算
     * （预算 = hlsSegmentSeconds × 2 秒，轮询间隔 300ms）。
     * 读取失败或文件短暂不存在（ffmpeg 重写窗口）时继续轮询直到预算耗尽。
     * <p>
     * 鉴权与活跃时间刷新复用 {@link #touchAndResolve(String, String, String)}。
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @return 播放列表路径；预算耗尽时文件仍不存在或不可读则为 null
     */
    public Path awaitPlaylistWarmup(String sessionId, String userId) {
        int threshold = Math.max(0, mediaProperties.getPlaylistWarmupSegments());
        long budgetMs = mediaProperties.getHlsSegmentSeconds() * 1000L * 2;
        return awaitPlaylistWarmup(sessionId, userId, threshold, budgetMs);
    }

    /**
     * 预热等待的参数化实现：阈值与等待预算由调用方指定。
     * 生产入口使用配置默认值；测试注入最小阈值与短预算以避免用例变慢。
     */
    Path awaitPlaylistWarmup(String sessionId, String userId, int threshold, long budgetMs) {
        TranscodeSession session = sessions.get(sessionId);
        if (session == null || !session.userId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "转码会话不存在");
        }
        if (session.failed()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR,
                    "硬件转码启动失败，请检查「系统-影视」中的硬解配置或改用软解");
        }
        long deadline = System.currentTimeMillis() + Math.max(0, budgetMs);
        while (true) {
            Path playlist = touchAndResolve(sessionId, userId, "index.m3u8");
            if (playlist != null) {
                String content = throttleSupport.readM3u8Content(playlist);
                if (content != null && countExtinfEntries(content) >= threshold) {
                    return playlist;
                }
                if (content != null && content.contains(HLS_ENDLIST_MARK)) {
                    return playlist;
                }
            }
            if (!session.process().isAlive()) {
                return playlist;
            }
            if (System.currentTimeMillis() >= deadline) {
                return playlist;
            }
            if (!sleepQuietly(PLAYLIST_WARMUP_POLL_INTERVAL_MS)) {
                return playlist;
            }
        }
    }

    private static int countExtinfEntries(String content) {
        int count = 0;
        Matcher matcher = EXTINF_ENTRY_PATTERN.matcher(content);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
        processLauncher.deleteQuietly(session.outputDir());
        concurrencyPermits.release();
    }

}
