package com.fleyx.jcloud.service.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 转码节流纯逻辑单元测试。
 */
class TranscodeThrottleSupportTest {

    private final TranscodeThrottleSupport support = new TranscodeThrottleSupport();

    @Test
    void shouldParseSegmentIndexAndTrackMax() {
        TranscodeSession session = newSession();
        support.updateMaxRequestedSegment(session, "seg_00042.m4s");
        assertEquals(42, session.maxRequestedSegmentIndex().get());
    }

    @Test
    void shouldIgnoreNonSegmentFiles() {
        TranscodeSession session = newSession();
        support.updateMaxRequestedSegment(session, "index.m3u8");
        support.updateMaxRequestedSegment(session, "init.mp4");
        support.updateMaxRequestedSegment(session, "seg_abc.m4s");
        assertEquals(-1, session.maxRequestedSegmentIndex().get());
    }

    @Test
    void shouldKeepMaxAcrossMultipleRequests() {
        TranscodeSession session = newSession();
        support.updateMaxRequestedSegment(session, "seg_00005.m4s");
        support.updateMaxRequestedSegment(session, "seg_00003.m4s");
        support.updateMaxRequestedSegment(session, "seg_00010.m4s");
        assertEquals(10, session.maxRequestedSegmentIndex().get());
    }

    @Test
    void shouldSumExtinfDurations() {
        String playlist = """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-MAP:URI="init.mp4"
                #EXTINF:4.000000,
                seg_00000.m4s
                #EXTINF:3.500000,
                seg_00001.m4s
                #EXTINF:4.000000,
                seg_00002.m4s
                #EXT-X-ENDLIST
                """;
        assertEquals(11.5, support.sumExtinfSeconds(playlist), 0.0001);
    }

    @Test
    void shouldReturnZeroForBlankOrNullContent() {
        assertEquals(0, support.sumExtinfSeconds(null));
        assertEquals(0, support.sumExtinfSeconds(""));
        assertEquals(0, support.sumExtinfSeconds("   "));
        assertEquals(0, support.sumExtinfSeconds("#EXTM3U\n#EXT-X-ENDLIST\n"));
    }

    @Test
    void shouldSumExtinfUpToCount() {
        String playlist = """
                #EXTM3U
                #EXTINF:2.000000,
                seg_00000.m4s
                #EXTINF:2.500000,
                seg_00001.m4s
                #EXTINF:3.000000,
                seg_00002.m4s
                """;
        assertEquals(0, support.sumExtinfSeconds(playlist, 0), 0.0001);
        assertEquals(2.0, support.sumExtinfSeconds(playlist, 1), 0.0001);
        assertEquals(4.5, support.sumExtinfSeconds(playlist, 2), 0.0001);
        assertEquals(7.5, support.sumExtinfSeconds(playlist, 3), 0.0001);
        // upToCount 超过实际条数时累加全部
        assertEquals(7.5, support.sumExtinfSeconds(playlist, 100), 0.0001);
    }

    @Test
    void shouldParseGeneratedDurationFromFile(@TempDir Path tempDir) throws Exception {
        Path m3u8 = tempDir.resolve("index.m3u8");
        Files.writeString(m3u8, """
                #EXTM3U
                #EXTINF:2.000000,
                seg_00000.m4s
                #EXTINF:2.500000,
                seg_00001.m4s
                """);
        assertEquals(4.5, support.parseGeneratedDurationSeconds(m3u8), 0.0001);
    }

    @Test
    void shouldReturnZeroWhenM3u8Missing(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("not_found.m3u8");
        assertEquals(0, support.parseGeneratedDurationSeconds(missing));
    }

    @Test
    void shouldDecidePauseWhenAheadReached() {
        TranscodeThrottleSupport.ThrottleDecision decision = support.decideThrottle(700, 4, 600, 300);
        assertEquals(TranscodeThrottleSupport.ThrottleDecision.PAUSE, decision);
    }

    @Test
    void shouldDecideResumeWhenAheadDropped() {
        TranscodeThrottleSupport.ThrottleDecision decision = support.decideThrottle(280, 4, 600, 300);
        assertEquals(TranscodeThrottleSupport.ThrottleDecision.RESUME, decision);
    }

    @Test
    void shouldHoldInsideHysteresisRange() {
        assertEquals(TranscodeThrottleSupport.ThrottleDecision.HOLD, support.decideThrottle(450, 4, 600, 300));
        assertEquals(TranscodeThrottleSupport.ThrottleDecision.HOLD, support.decideThrottle(500, 4, 600, 300));
    }

    @Test
    void shouldTreatNoRequestAsZeroConsumed() {
        // 已生成 280 秒但客户端一个分片都没请求过，consumed 按 0 计，低于 resume 阈值
        TranscodeThrottleSupport.ThrottleDecision decision = support.decideThrottle(280, 0, 600, 300);
        assertEquals(TranscodeThrottleSupport.ThrottleDecision.RESUME, decision);

        // 已生成 600 秒，consumed 为 0，刚好达到 ahead 阈值 -> 暂停
        decision = support.decideThrottle(600, 0, 600, 300);
        assertEquals(TranscodeThrottleSupport.ThrottleDecision.PAUSE, decision);
    }

    @Test
    void shouldAccountRequestedSegmentsInDecision() {
        // 客户端已请求 50 个分片，实际消费 200 秒，已生成 820 秒，领先 620 秒 -> 暂停
        TranscodeThrottleSupport.ThrottleDecision decision = support.decideThrottle(820, 200, 600, 300);
        assertEquals(TranscodeThrottleSupport.ThrottleDecision.PAUSE, decision);

        // 已生成 450 秒，消费 200 秒，领先 250 秒 -> 继续
        decision = support.decideThrottle(450, 200, 600, 300);
        assertEquals(TranscodeThrottleSupport.ThrottleDecision.RESUME, decision);
    }

    @Test
    void shouldDetectCompleteWhenEndlistPresent(@TempDir Path tempDir) throws Exception {
        Path m3u8 = tempDir.resolve("index.m3u8");
        Files.writeString(m3u8, """
                #EXTM3U
                #EXTINF:4.000000,
                seg_00000.m4s
                #EXT-X-ENDLIST
                """);
        assertTrue(support.isTranscodeComplete(tempDir));
    }

    @Test
    void shouldDetectIncompleteWhenEndlistMissing(@TempDir Path tempDir) throws Exception {
        Path m3u8 = tempDir.resolve("index.m3u8");
        Files.writeString(m3u8, """
                #EXTM3U
                #EXTINF:4.000000,
                seg_00000.m4s
                """);
        assertFalse(support.isTranscodeComplete(tempDir));
    }

    @Test
    void shouldDetectIncompleteWhenM3u8Missing(@TempDir Path tempDir) {
        assertFalse(support.isTranscodeComplete(tempDir));
        assertFalse(support.isTranscodeComplete(null));
    }

    @Test
    void shouldKeepAliveSessionUntilLastAccessTimeout() {
        Instant now = Instant.now();
        Instant deadline = now.minusSeconds(60);
        TranscodeSession activeRecent = newSession(now, true);
        TranscodeSession activeIdle = newSession(now.minusSeconds(120), true);

        assertFalse(support.isSessionIdle(activeRecent, deadline));
        assertTrue(support.isSessionIdle(activeIdle, deadline));
    }

    @Test
    void shouldRecycleDeadIncompleteSessionImmediately(@TempDir Path tempDir) {
        Instant now = Instant.now();
        Instant deadline = now.minusSeconds(60);
        TranscodeSession deadIncomplete = newSession(tempDir, now, false);

        assertTrue(support.isSessionIdle(deadIncomplete, deadline));
    }

    @Test
    void shouldKeepDeadCompleteSessionUntilLastAccessTimeout(@TempDir Path tempDir) throws Exception {
        Instant now = Instant.now();
        Instant deadline = now.minusSeconds(60);
        Files.writeString(tempDir.resolve("index.m3u8"), "#EXTM3U\n#EXT-X-ENDLIST\n");

        TranscodeSession deadCompleteRecent = newSession(tempDir, now, false);
        TranscodeSession deadCompleteIdle = newSession(tempDir, now.minusSeconds(120), false);

        assertFalse(support.isSessionIdle(deadCompleteRecent, deadline));
        assertTrue(support.isSessionIdle(deadCompleteIdle, deadline));
    }

    private TranscodeSession newSession() {
        return newSession(null, Instant.now(), true);
    }

    private TranscodeSession newSession(Instant lastAccess, boolean alive) {
        return newSession(null, lastAccess, alive);
    }

    private TranscodeSession newSession(Path outputDir, Instant lastAccess, boolean alive) {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(alive);
        return new TranscodeSession("sid", "uid", outputDir, process, "libx264", lastAccess);
    }
}
