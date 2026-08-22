package com.fleyx.jcloud.service.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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

        launcher.watchEarlyFailure(session, sessions, new TranscodeProcessLauncher.StderrTail());

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

        launcher.watchEarlyFailure(session, sessions, new TranscodeProcessLauncher.StderrTail());

        // 等过 3 秒监控窗口后确认未误标
        Thread.sleep(3600);
        assertFalse(session.failed(), "已有产出时不应标记失败");
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
}
