package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.config.MediaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    @SuppressWarnings("unchecked")
    private Map<String, TranscodeSession> sessionsOf(TranscodeSessionManager manager) {
        return (Map<String, TranscodeSession>) ReflectionTestUtils.getField(manager, "sessions");
    }

    private TranscodeSession newSession(String id, Path dir) {
        return new TranscodeSession(id, "u1", dir, mock(Process.class), "copy", Instant.now());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldTreatZeroByteFileAsNotReady(@TempDir Path tempDir) throws Exception {
        // ffmpeg 启动时会先创建 0 字节的 init.mp4 占位文件再写入内容，
        // 若按“存在即就绪”返回，客户端会拿到空初始化段导致播放失败
        MediaProperties properties = new MediaProperties();
        TranscodeSessionManager manager = new TranscodeSessionManager(properties,
                new TranscodeCommandBuilder(properties), mock(TranscodeThrottleSupport.class),
                mock(TranscodeConfigResolver.class), mock(TranscodeProcessLauncher.class));
        TranscodeSession session = new TranscodeSession("s1", "u1", tempDir, null, "copy", Instant.now());
        ((Map<String, TranscodeSession>) ReflectionTestUtils.getField(manager, "sessions")).put("s1", session);

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
}
