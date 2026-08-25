package com.fleyx.jcloud.service.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 早期失败监控测试：启动失败标记会话失败、不再回退软解、有产出不误标。
 * 纯 JUnit 不起 Spring，watchEarlyFailure 不触碰未使用的依赖，构造时传 null。
 */
class TranscodeProcessLauncherTest {

    private TranscodeProcessLauncher newLauncher() {
        return new TranscodeProcessLauncher(null, null, null);
    }

    /**
     * 即时退出的真实短进程，waitFor 收割避免残留。
     */
    private Process exitedProcess() throws Exception {
        Process process = new ProcessBuilder("true").start();
        process.waitFor();
        return process;
    }

    @Test
    void shouldMarkSessionFailedAndNotFallbackToSoftware(@TempDir Path tempDir) throws Exception {
        TranscodeProcessLauncher launcher = newLauncher();
        TranscodeSession session = new TranscodeSession("s1", "u1", tempDir, exitedProcess(), "nvenc", Instant.now());
        Map<String, TranscodeSession> sessions = new ConcurrentHashMap<>();
        sessions.put("s1", session);

        // 默认窗口由 3s 改为 10s，测试改用包级 windowMillis 重载保持可测
        launcher.watchEarlyFailure(session, sessions, new TranscodeProcessLauncher.StderrTail(), 300L,
                new ConcurrentHashMap<>());

        await(() -> session.failed(), "启动失败的会话应被标记失败");
        assertSame(session, sessions.get("s1"), "注册表中的会话不应被替换");
        assertEquals("nvenc", sessions.get("s1").encoder(), "编码器不应被替换为软解 libx264");
    }

    @Test
    void shouldNotMarkFailureWhenOutputProduced(@TempDir Path tempDir) throws Exception {
        TranscodeProcessLauncher launcher = newLauncher();
        // 已有产出文件，即使进程已退出也不应标记失败
        Files.createFile(tempDir.resolve("init.mp4"));
        TranscodeSession session = new TranscodeSession("s2", "u1", tempDir, exitedProcess(), "nvenc", Instant.now());
        Map<String, TranscodeSession> sessions = new ConcurrentHashMap<>();
        sessions.put("s2", session);

        // 默认窗口由 3s 改为 10s，测试改用包级 windowMillis 重载保持可测
        launcher.watchEarlyFailure(session, sessions, new TranscodeProcessLauncher.StderrTail(), 3000L,
                new ConcurrentHashMap<>());

        // 等过 3 秒监控窗口后确认未误标
        Thread.sleep(3600);
        assertFalse(session.failed(), "已有产出时不应标记失败");
    }

    @Test
    void shouldFallbackToSoftwareDecodeOnEarlyFailure(@TempDir Path tempDir) throws Exception {
        // 早期失败（已退出 + 无有效产出 + 已注册 + h264_nvenc + 持有 request + 未降级）→ 以 hwDecode=false 重启一次：
        // 会话进程被替换、decodeFallback=true、failed 保持 false、黑名单记 localPath|videoCodec|h264_nvenc
        Process original = exitedProcess();
        TranscodeCommandBuilder.TranscodeRequest request = new TranscodeCommandBuilder.TranscodeRequest(
                0, null, Path.of("/data/movie.mkv"), null, "hevc", "aac", null, null, true, null, null, null);
        TranscodeSession session = new TranscodeSession("s1", "u1", tempDir, original, "h264_nvenc", Instant.now());
        session.setRequest(request);
        Map<String, TranscodeSession> sessions = new ConcurrentHashMap<>();
        sessions.put("s1", session);
        Map<String, Boolean> blacklist = new ConcurrentHashMap<>();
        List<Boolean> startHwDecodeFlags = new ArrayList<>();
        List<Process> started = new ArrayList<>();

        TranscodeProcessLauncher launcher = new TranscodeProcessLauncher(null, null, null) {
            @Override
            public Process startFfmpeg(Path outputDir, TranscodeCommandBuilder.TranscodeRequest req, String encoder,
                                       TranscodeProcessLauncher.StderrTail tail, boolean hwDecode) throws IOException {
                startHwDecodeFlags.add(hwDecode);
                // 存活短进程：降级重启后的二次监控在窗口内不触发失败
                Process process = new ProcessBuilder("sleep", "10").start();
                started.add(process);
                return process;
            }
        };
        launcher.watchEarlyFailure(session, sessions, new TranscodeProcessLauncher.StderrTail(), 300L, blacklist);

        await(() -> session.decodeFallback(), "降级后应置 decodeFallback=true");
        assertFalse(session.failed(), "降级重启后不应标记失败");
        assertEquals(1, startHwDecodeFlags.size(), "应恰好重启一次");
        assertFalse(startHwDecodeFlags.get(0), "重启应以 hwDecode=false 调用 startFfmpeg");
        assertNotSame(original, session.process(), "会话进程应被替换为重启的新进程");
        assertSame(session, sessions.get("s1"), "注册表中的会话实例不应被替换");
        assertTrue(blacklist.containsKey("/data/movie.mkv|hevc|h264_nvenc"),
                "黑名单应记录 localPath|videoCodec|h264_nvenc");
        for (Process process : started) {
            process.destroy();
        }
    }

    @Test
    void shouldMarkFailedWithoutRetryWhenAlreadyFallback(@TempDir Path tempDir) throws Exception {
        // 已降级会话再次早期失败 → 直接 failed=true，不再重启
        TranscodeCommandBuilder.TranscodeRequest request = new TranscodeCommandBuilder.TranscodeRequest(
                0, null, Path.of("/data/movie.mkv"), null, "hevc", "aac", null, null, true, null, null, null);
        TranscodeSession session = new TranscodeSession("s2", "u1", tempDir, exitedProcess(), "h264_nvenc", Instant.now());
        session.setRequest(request);
        session.decodeFallback(true);
        Map<String, TranscodeSession> sessions = new ConcurrentHashMap<>();
        sessions.put("s2", session);
        Map<String, Boolean> blacklist = new ConcurrentHashMap<>();
        List<Boolean> startHwDecodeFlags = new ArrayList<>();

        TranscodeProcessLauncher launcher = new TranscodeProcessLauncher(null, null, null) {
            @Override
            public Process startFfmpeg(Path outputDir, TranscodeCommandBuilder.TranscodeRequest req, String encoder,
                                       TranscodeProcessLauncher.StderrTail tail, boolean hwDecode) throws IOException {
                startHwDecodeFlags.add(hwDecode);
                try {
                    return exitedProcess();
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }
        };
        launcher.watchEarlyFailure(session, sessions, new TranscodeProcessLauncher.StderrTail(), 300L, blacklist);

        await(() -> session.failed(), "已降级会话再次早期失败应标记 failed");
        assertTrue(startHwDecodeFlags.isEmpty(), "已降级后不应再次重启");
        assertFalse(blacklist.containsKey("/data/movie.mkv|hevc|h264_nvenc"), "不应重复记黑名单");
    }

    @Test
    void shouldNotTreatSupAsValidOutput(@TempDir Path tempDir) throws Exception {
        // 外挂字幕物化写入的 .sup 不算有效产出：早期失败仍触发降级
        Files.createFile(tempDir.resolve("sub.sup"));
        TranscodeCommandBuilder.TranscodeRequest request = new TranscodeCommandBuilder.TranscodeRequest(
                0, null, Path.of("/data/movie.mkv"), null, "hevc", "aac", null, null, true, null, null, null);
        TranscodeSession session = new TranscodeSession("s3", "u1", tempDir, exitedProcess(), "h264_nvenc", Instant.now());
        session.setRequest(request);
        Map<String, TranscodeSession> sessions = new ConcurrentHashMap<>();
        sessions.put("s3", session);
        Map<String, Boolean> blacklist = new ConcurrentHashMap<>();
        List<Boolean> startHwDecodeFlags = new ArrayList<>();

        TranscodeProcessLauncher launcher = new TranscodeProcessLauncher(null, null, null) {
            @Override
            public Process startFfmpeg(Path outputDir, TranscodeCommandBuilder.TranscodeRequest req, String encoder,
                                       TranscodeProcessLauncher.StderrTail tail, boolean hwDecode) throws IOException {
                startHwDecodeFlags.add(hwDecode);
                return new ProcessBuilder("sleep", "10").start();
            }
        };
        launcher.watchEarlyFailure(session, sessions, new TranscodeProcessLauncher.StderrTail(), 300L, blacklist);

        await(() -> session.decodeFallback(), ".sup 不算有效产出，应触发降级");
        assertFalse(session.failed(), "降级重启后不应标记失败");
        assertEquals(1, startHwDecodeFlags.size());
        assertTrue(blacklist.containsKey("/data/movie.mkv|hevc|h264_nvenc"), "降级应写入黑名单");
    }

    @Test
    void shouldNotFallbackWhenValidOutputPresent(@TempDir Path tempDir) throws Exception {
        // 有效产出（init.mp4 / seg_00000.m4s / index.m3u8）存在时：不降级、不标失败、不记黑名单
        for (String file : List.of("init.mp4", "seg_00000.m4s", "index.m3u8")) {
            Files.createFile(tempDir.resolve(file));
            TranscodeCommandBuilder.TranscodeRequest request = new TranscodeCommandBuilder.TranscodeRequest(
                    0, null, Path.of("/data/movie.mkv"), null, "hevc", "aac", null, null, true, null, null, null);
            TranscodeSession session = new TranscodeSession(file, "u1", tempDir, exitedProcess(), "h264_nvenc", Instant.now());
            session.setRequest(request);
            Map<String, TranscodeSession> sessions = new ConcurrentHashMap<>();
            sessions.put(file, session);
            Map<String, Boolean> blacklist = new ConcurrentHashMap<>();
            List<Boolean> startHwDecodeFlags = new ArrayList<>();

            TranscodeProcessLauncher launcher = new TranscodeProcessLauncher(null, null, null) {
                @Override
                public Process startFfmpeg(Path outputDir, TranscodeCommandBuilder.TranscodeRequest req, String encoder,
                                           TranscodeProcessLauncher.StderrTail tail, boolean hwDecode) throws IOException {
                    startHwDecodeFlags.add(hwDecode);
                    try {
                        return exitedProcess();
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                }
            };
            launcher.watchEarlyFailure(session, sessions, new TranscodeProcessLauncher.StderrTail(), 300L, blacklist);

            Thread.sleep(600);
            assertFalse(session.failed(), file + " 应视为有效产出，不标失败");
            assertFalse(session.decodeFallback(), file + " 有效产出不应触发降级");
            assertTrue(startHwDecodeFlags.isEmpty(), file + " 有效产出不应重启");
            assertTrue(blacklist.isEmpty(), file + " 有效产出不应记黑名单");
        }
    }

    @Test
    void shouldKeepOnlyLastTenLinesInStderrTail() {
        // 环形缓冲：超过 10 行时丢弃最早的行，仅保留尾部
        TranscodeProcessLauncher.StderrTail tail = new TranscodeProcessLauncher.StderrTail();
        for (int i = 1; i <= 12; i++) {
            tail.append("line" + i);
        }

        assertEquals("line3 | line4 | line5 | line6 | line7 | line8 | line9 | line10 | line11 | line12",
                tail.tail());
    }

    @Test
    void shouldReturnEmptyTailWhenNoStderr() {
        assertEquals("", new TranscodeProcessLauncher.StderrTail().tail());
    }

    /**
     * 轮询等待条件成立，上限约 5 秒（3 秒监控窗口 + 余量）。
     */
    private void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail(message);
    }

    @Test
    void shouldFallbackToSoftwareDecodeOnEarlyFailureForQsv(@TempDir Path tempDir) throws Exception {
        // 票02 S3 降级条件扩展到 h264_qsv：早期失败以 hwDecode=false 重试一次，黑名单 encoder 段为 h264_qsv
        TranscodeCommandBuilder.TranscodeRequest request = new TranscodeCommandBuilder.TranscodeRequest(
                0, null, Path.of("/data/movie.mkv"), null, "hevc", "aac", null, null, true, null, null, null);
        TranscodeSession session = new TranscodeSession("s-qsv", "u1", tempDir, exitedProcess(), "h264_qsv", Instant.now());
        session.setRequest(request);
        Map<String, TranscodeSession> sessions = new ConcurrentHashMap<>();
        sessions.put("s-qsv", session);
        Map<String, Boolean> blacklist = new ConcurrentHashMap<>();
        List<Boolean> startHwDecodeFlags = new ArrayList<>();

        TranscodeProcessLauncher launcher = new TranscodeProcessLauncher(null, null, null) {
            @Override
            public Process startFfmpeg(Path outputDir, TranscodeCommandBuilder.TranscodeRequest req, String encoder,
                                       TranscodeProcessLauncher.StderrTail tail, boolean hwDecode) throws IOException {
                startHwDecodeFlags.add(hwDecode);
                return new ProcessBuilder("sleep", "10").start();
            }
        };
        launcher.watchEarlyFailure(session, sessions, new TranscodeProcessLauncher.StderrTail(), 300L, blacklist);

        await(() -> session.decodeFallback(), "qsv 早期失败应触发降级");
        assertFalse(session.failed(), "降级重启后不应标记失败");
        assertEquals(1, startHwDecodeFlags.size(), "应恰好重启一次");
        assertFalse(startHwDecodeFlags.get(0), "重启应以 hwDecode=false 调用 startFfmpeg");
        assertTrue(blacklist.containsKey("/data/movie.mkv|hevc|h264_qsv"), "黑名单应记录 localPath|videoCodec|h264_qsv");
    }

    @Test
    void shouldFallbackToSoftwareDecodeOnEarlyFailureForVaapi(@TempDir Path tempDir) throws Exception {
        // 票02 S3 降级条件扩展到 h264_vaapi：早期失败以 hwDecode=false 重试一次，黑名单 encoder 段为 h264_vaapi
        TranscodeCommandBuilder.TranscodeRequest request = new TranscodeCommandBuilder.TranscodeRequest(
                0, null, Path.of("/data/movie.mkv"), null, "hevc", "aac", null, null, true, null, null, null);
        TranscodeSession session = new TranscodeSession("s-vaapi", "u1", tempDir, exitedProcess(), "h264_vaapi", Instant.now());
        session.setRequest(request);
        Map<String, TranscodeSession> sessions = new ConcurrentHashMap<>();
        sessions.put("s-vaapi", session);
        Map<String, Boolean> blacklist = new ConcurrentHashMap<>();
        List<Boolean> startHwDecodeFlags = new ArrayList<>();

        TranscodeProcessLauncher launcher = new TranscodeProcessLauncher(null, null, null) {
            @Override
            public Process startFfmpeg(Path outputDir, TranscodeCommandBuilder.TranscodeRequest req, String encoder,
                                       TranscodeProcessLauncher.StderrTail tail, boolean hwDecode) throws IOException {
                startHwDecodeFlags.add(hwDecode);
                return new ProcessBuilder("sleep", "10").start();
            }
        };
        launcher.watchEarlyFailure(session, sessions, new TranscodeProcessLauncher.StderrTail(), 300L, blacklist);

        await(() -> session.decodeFallback(), "vaapi 早期失败应触发降级");
        assertFalse(session.failed(), "降级重启后不应标记失败");
        assertEquals(1, startHwDecodeFlags.size(), "应恰好重启一次");
        assertFalse(startHwDecodeFlags.get(0), "重启应以 hwDecode=false 调用 startFfmpeg");
        assertTrue(blacklist.containsKey("/data/movie.mkv|hevc|h264_vaapi"), "黑名单应记录 localPath|videoCodec|h264_vaapi");
    }
}
