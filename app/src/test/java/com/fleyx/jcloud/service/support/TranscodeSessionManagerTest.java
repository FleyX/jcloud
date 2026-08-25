package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.config.MediaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转码会话播放列表 token 重写、会话文件就绪判断与心跳回收测试。
 */
class TranscodeSessionManagerTest {

    private TranscodeSessionManager newManager(MediaProperties properties, TranscodeThrottleSupport throttle) {
        return new TranscodeSessionManager(properties, new TranscodeCommandBuilder(properties), throttle,
                mock(TranscodeConfigResolver.class), mock(TranscodeProcessLauncher.class));
    }

    private Map<String, TranscodeSession> sessionsOf(TranscodeSessionManager manager) {
        return manager.sessions;
    }

    private TranscodeSession newSession(String id, Path dir) {
        return new TranscodeSession(id, "u1", dir, mock(Process.class), "copy", Instant.now());
    }

    @Test
    void shouldTreatZeroByteFileAsNotReady(@TempDir Path tempDir) throws Exception {
        // ffmpeg 启动时会先创建 0 字节的 init.mp4 占位文件再写入内容，
        // 若按“存在即就绪”返回，客户端会拿到空初始化段导致播放失败
        MediaProperties properties = new MediaProperties();
        TranscodeSessionManager manager = new TranscodeSessionManager(properties,
                new TranscodeCommandBuilder(properties), mock(TranscodeThrottleSupport.class),
                mock(TranscodeConfigResolver.class), mock(TranscodeProcessLauncher.class));
        TranscodeSession session = new TranscodeSession("s1", "u1", tempDir, null, "copy", Instant.now());
        manager.sessions.put("s1", session);

        // 文件不存在：未就绪
        assertNull(manager.touchAndResolve("s1", "u1", "init.mp4"));
        // 0 字节占位：同样未就绪
        Files.createFile(tempDir.resolve("init.mp4"));
        assertNull(manager.touchAndResolve("s1", "u1", "init.mp4"));
        // 写入内容后就绪
        Files.writeString(tempDir.resolve("init.mp4"), "moov");
        assertNotNull(manager.touchAndResolve("s1", "u1", "init.mp4"));
    }


    @Test
    void shouldAppendTokenToSegmentAndInitMapUris() {
        String playlist = """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-MAP:URI="init.mp4"
                #EXTINF:4.000000,
                seg_00000.m4s
                #EXTINF:4.000000,
                seg_00001.m4s
                #EXT-X-ENDLIST
                """;
        String result = TranscodePlaylistSupport.appendTokenToPlaylist(playlist, "tk.abc");
        assertTrue(result.contains("URI=\"init.mp4?token=tk.abc\""), "EXT-X-MAP URI 应携带 token: " + result);
        assertTrue(result.contains("seg_00000.m4s?token=tk.abc"), "切片 URI 应携带 token: " + result);
        assertTrue(result.contains("seg_00001.m4s?token=tk.abc"), "切片 URI 应携带 token: " + result);
        assertTrue(result.contains("#EXT-X-VERSION:7\n"), "标签行不应被修改: " + result);
        assertTrue(result.contains("#EXT-X-ENDLIST\n") || result.endsWith("#EXT-X-ENDLIST\n"), "结束标签不应被修改");
    }

    @Test
    void shouldKeepAbsoluteAndAlreadyParameterizedUris() {
        String playlist = """
                #EXTM3U
                #EXT-X-MAP:URI="https://cdn.example.com/init.mp4"
                seg_00000.m4s?token=old
                """;
        String result = TranscodePlaylistSupport.appendTokenToPlaylist(playlist, "tk.abc");
        assertTrue(result.contains("URI=\"https://cdn.example.com/init.mp4\""), "绝对 URI 不应被修改");
        assertTrue(result.contains("seg_00000.m4s?token=old\n") || result.endsWith("seg_00000.m4s?token=old"),
                "已带查询参数的 URI 不应重复追加");
    }

    @Test
    void shouldReturnOriginWhenTokenBlank() {
        String playlist = "#EXTM3U\nseg_00000.m4s\n";
        assertEquals(playlist, TranscodePlaylistSupport.appendTokenToPlaylist(playlist, null));
        assertEquals(playlist, TranscodePlaylistSupport.appendTokenToPlaylist(playlist, "  "));
        assertNull(TranscodePlaylistSupport.appendTokenToPlaylist(null, "tk"));
    }

    @Test
    void shouldRefreshHeartbeatAndRejectInvalidSession(@TempDir Path tempDir) {
        TranscodeSessionManager manager = newManager(new MediaProperties(), mock(TranscodeThrottleSupport.class));
        TranscodeSession session = newSession("s1", tempDir);
        sessionsOf(manager).put("s1", session);
        assertNull(session.lastHeartbeatAt());

        manager.heartbeat("s1", "u1");
        assertNotNull(session.lastHeartbeatAt(), "心跳后应记录心跳时间");

        assertThrows(BusinessException.class, () -> manager.heartbeat("s1", "u2"), "他人会话应拒绝");
        assertThrows(BusinessException.class, () -> manager.heartbeat("missing", "u1"), "不存在会话应拒绝");
    }

    @Test
    void shouldRecycleHeartbeatSessionByHeartbeatTimeout(@TempDir Path tempDir) {
        TranscodeThrottleSupport throttle = mock(TranscodeThrottleSupport.class);
        TranscodeSessionManager manager = newManager(new MediaProperties(), throttle);
        // 心跳超过 90s（默认阈值）：回收
        TranscodeSession expired = newSession("expired", tempDir);
        expired.lastHeartbeatAt(Instant.now().minus(120, ChronoUnit.SECONDS));
        // 心跳在阈值内：保留
        TranscodeSession alive = newSession("alive", tempDir);
        alive.lastHeartbeatAt(Instant.now());
        sessionsOf(manager).put("expired", expired);
        sessionsOf(manager).put("alive", alive);

        manager.cleanupIdleSessions();

        assertFalse(sessionsOf(manager).containsKey("expired"), "心跳超时应回收");
        assertTrue(sessionsOf(manager).containsKey("alive"), "心跳未超时应保留");
        verify(expired.process()).destroy();
        verify(alive.process(), never()).destroy();
        // 心跳会话不走空闲规则
        verify(throttle, never()).isSessionIdle(any(), any());
    }

    @Test
    void shouldKeepNonHeartbeatSessionOnIdleRule(@TempDir Path tempDir) {
        TranscodeThrottleSupport throttle = mock(TranscodeThrottleSupport.class);
        TranscodeSessionManager manager = newManager(new MediaProperties(), throttle);
        // 未收到过心跳的会话（API 客户端）：沿用空闲规则，由 isSessionIdle 判定
        TranscodeSession session = newSession("s1", tempDir);
        sessionsOf(manager).put("s1", session);

        when(throttle.isSessionIdle(any(), any())).thenReturn(false);
        manager.cleanupIdleSessions();
        assertTrue(sessionsOf(manager).containsKey("s1"), "空闲规则判定未空闲应保留");

        when(throttle.isSessionIdle(any(), any())).thenReturn(true);
        manager.cleanupIdleSessions();
        assertFalse(sessionsOf(manager).containsKey("s1"), "空闲规则判定空闲应回收");
    }

    @Test
    void shouldCloseSessionWithOwnershipCheck(@TempDir Path tempDir) {
        TranscodeSessionManager manager = newManager(new MediaProperties(), mock(TranscodeThrottleSupport.class));
        TranscodeSession session = newSession("s1", tempDir);
        sessionsOf(manager).put("s1", session);

        assertThrows(BusinessException.class, () -> manager.closeSession("s1", "u2"), "他人会话应拒绝");
        assertTrue(sessionsOf(manager).containsKey("s1"));

        manager.closeSession("s1", "u1");
        assertFalse(sessionsOf(manager).containsKey("s1"), "关闭后应移除");
        verify(session.process()).destroy();

        // 会话不存在（可能已被回收）：静默返回
        manager.closeSession("missing", "u1");
    }

    // ---------- 播放列表预热 ----------

    private String playlistWithSegments(int count, boolean endlist) {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < count; i++) {
            sb.append("#EXTINF:4.000000,\nseg_").append(String.format("%05d", i)).append(".m4s\n");
        }
        if (endlist) {
            sb.append("#EXT-X-ENDLIST\n");
        }
        return sb.toString();
    }

    @Test
    void shouldReturnImmediatelyWhenPlaylistReachesWarmupThreshold(@TempDir Path tempDir) throws Exception {
        TranscodeSessionManager manager = newManager(new MediaProperties(), new TranscodeThrottleSupport());
        Path playlist = tempDir.resolve("index.m3u8");
        Files.writeString(playlist, playlistWithSegments(8, false));
        sessionsOf(manager).put("s1", newSession("s1", tempDir));

        assertEquals(playlist, manager.awaitPlaylistWarmup("s1", "u1"), "切片数已达阈值（默认 8）应立即返回");
    }

    @Test
    void shouldReturnImmediatelyWhenPlaylistHasEndlist(@TempDir Path tempDir) throws Exception {
        TranscodeSessionManager manager = newManager(new MediaProperties(), new TranscodeThrottleSupport());
        Path playlist = tempDir.resolve("index.m3u8");
        Files.writeString(playlist, playlistWithSegments(2, true));
        sessionsOf(manager).put("s1", newSession("s1", tempDir));

        assertEquals(playlist, manager.awaitPlaylistWarmup("s1", "u1"), "未达标但含 ENDLIST 应立即返回");
    }

    @Test
    void shouldReturnImmediatelyWhenProcessExited(@TempDir Path tempDir) throws Exception {
        TranscodeSessionManager manager = newManager(new MediaProperties(), new TranscodeThrottleSupport());
        Path playlist = tempDir.resolve("index.m3u8");
        Files.writeString(playlist, playlistWithSegments(2, false));
        // mock Process 默认 isAlive()=false，模拟 ffmpeg 进程已退出
        sessionsOf(manager).put("s1", newSession("s1", tempDir));

        assertEquals(playlist, manager.awaitPlaylistWarmup("s1", "u1"), "未达标且进程已退出应按当前内容返回");
    }

    @Test
    void shouldWaitUntilSegmentCountGrowsToThreshold(@TempDir Path tempDir) throws Exception {
        TranscodeSessionManager manager = newManager(new MediaProperties(), new TranscodeThrottleSupport());
        Path playlist = tempDir.resolve("index.m3u8");
        Files.writeString(playlist, playlistWithSegments(2, false));
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        sessionsOf(manager).put("s1", new TranscodeSession("s1", "u1", tempDir, process, "copy", Instant.now()));

        CompletableFuture<Path> future = CompletableFuture.supplyAsync(
                () -> manager.awaitPlaylistWarmup("s1", "u1", 4, 10_000));
        // 等待预热轮询读到首个写盘版本后再追加切片，模拟 ffmpeg 持续产出
        Thread.sleep(600);
        Files.writeString(playlist, playlistWithSegments(4, false));

        assertEquals(playlist, future.get(5, TimeUnit.SECONDS), "等待中切片增长到达标应返回");
        assertEquals(playlistWithSegments(4, false), Files.readString(playlist), "返回时应已读到达标版本");
    }

    @Test
    void shouldReturnCurrentContentWhenWarmupBudgetExhausted(@TempDir Path tempDir) throws Exception {
        TranscodeSessionManager manager = newManager(new MediaProperties(), new TranscodeThrottleSupport());
        Path playlist = tempDir.resolve("index.m3u8");
        Files.writeString(playlist, playlistWithSegments(2, false));
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        sessionsOf(manager).put("s1", new TranscodeSession("s1", "u1", tempDir, process, "copy", Instant.now()));

        long start = System.currentTimeMillis();
        assertEquals(playlist, manager.awaitPlaylistWarmup("s1", "u1", 4, 0), "预算耗尽应按当前内容返回");
        assertTrue(System.currentTimeMillis() - start < 1000, "预算 0ms 不应产生阻塞等待");
    }

    // ---------- S4：黑名单命中直启降级命令 ----------

    private Map<String, Boolean> blacklistOf(TranscodeSessionManager manager) throws Exception {
        // decodeFallbackBlacklist 为私有，反射仅取引用并修改其内容，不替换字段
        Field field = TranscodeSessionManager.class.getDeclaredField("decodeFallbackBlacklist");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Boolean> blacklist = (Map<String, Boolean>) field.get(manager);
        return blacklist;
    }

    private TranscodeSessionManager decoderManager(TranscodeProcessLauncher launcher, Path outputDir) throws IOException {
        // encoder 由 configResolver.resolveHwaccel()="nvenc" 经 selectEncoder 映射为 h264_nvenc
        TranscodeConfigResolver resolver = mock(TranscodeConfigResolver.class);
        when(resolver.resolveHwaccel()).thenReturn("nvenc");
        MediaProperties properties = new MediaProperties();
        when(launcher.resolveSessionRoot()).thenReturn(outputDir);
        when(launcher.startFfmpeg(any(), any(), any(), any(), anyBoolean())).thenReturn(mock(Process.class));
        return new TranscodeSessionManager(properties, new TranscodeCommandBuilder(properties),
                mock(TranscodeThrottleSupport.class), resolver, launcher);
    }

    private TranscodeCommandBuilder.TranscodeRequest localHwRequest() {
        return new TranscodeCommandBuilder.TranscodeRequest(
                0, null, Path.of("/data/movie.mkv"), null, "hevc", "aac", null, null, true, null, null, null);
    }

    @Test
    void shouldStartWithHwDecodeFalseWhenBlacklisted(@TempDir Path tempDir) throws Exception {
        // 黑名单已含 localPath|videoCodec|h264_nvenc → createSession 直接以 hwDecode=false 启动
        TranscodeProcessLauncher launcher = mock(TranscodeProcessLauncher.class);
        TranscodeSessionManager manager = decoderManager(launcher, tempDir);
        blacklistOf(manager).put("/data/movie.mkv|hevc|h264_nvenc", true);

        manager.createSession("s1", localHwRequest());

        verify(launcher).startFfmpeg(any(), any(), any(), any(), eq(false));
    }

    @Test
    void shouldStartWithHwDecodeTrueWhenNotBlacklisted(@TempDir Path tempDir) throws Exception {
        // 黑名单未命中 → createSession 以 hwDecode=true 启动
        TranscodeProcessLauncher launcher = mock(TranscodeProcessLauncher.class);
        TranscodeSessionManager manager = decoderManager(launcher, tempDir);

        manager.createSession("s1", localHwRequest());

        verify(launcher).startFfmpeg(any(), any(), any(), any(), eq(true));
    }

    @Test
    void shouldStartRemoteRequestWithHwDecodeTrueRegardlessOfBlacklist(@TempDir Path tempDir) throws Exception {
        // 远程请求 localPath=null：不查黑名单不缓存，恒 hwDecode=true（即使黑名单里已有本地 key）
        TranscodeProcessLauncher launcher = mock(TranscodeProcessLauncher.class);
        TranscodeSessionManager manager = decoderManager(launcher, tempDir);
        blacklistOf(manager).put("/data/movie.mkv|hevc|h264_nvenc", true);

        TranscodeCommandBuilder.TranscodeRequest request = new TranscodeCommandBuilder.TranscodeRequest(
                0, null, null, () -> null, "hevc", "aac", null, null, true, null, null, null);
        manager.createSession("s1", request);

        verify(launcher).startFfmpeg(any(), any(), any(), any(), eq(true));
    }
}
