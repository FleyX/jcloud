package com.fleyx.jcloud.service.support;

import cn.hutool.core.io.FileUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 转码会话节流支撑逻辑。
 * <p>
 * 负责解析客户端请求进度、计算 ffmpeg 已生成时长、决策是否需要暂停/继续进程。
 * 本类为无状态组件，所有决策方法均为纯函数，便于单元测试。
 */
@Slf4j
@Component
public class TranscodeThrottleSupport {

    private static final Pattern SEGMENT_PATTERN = Pattern.compile("seg_(\\d+)\\.m4s");
    private static final Pattern EXTINF_PATTERN = Pattern.compile("#EXTINF:\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE);

    /**
     * 节流决策结果。
     */
    public enum ThrottleDecision {
        PAUSE, RESUME, HOLD
    }

    /**
     * 从分片文件名解析序号并更新会话中客户端请求过的最大序号。
     */
    public void updateMaxRequestedSegment(TranscodeSession session, String fileName) {
        Matcher matcher = SEGMENT_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return;
        }
        try {
            int index = Integer.parseInt(matcher.group(1));
            session.maxRequestedSegmentIndex().updateAndGet(current -> Math.max(current, index));
        } catch (NumberFormatException e) {
            log.debug("分片文件名序号解析异常: fileName={}", fileName);
        }
    }

    /**
     * 解析 m3u8 播放列表中已生成分片的累计时长（秒）。
     * <p>
     * 读取失败或文件不存在时返回 0，不影响主流程。
     */
    public double parseGeneratedDurationSeconds(Path m3u8Path) {
        String content = readM3u8Content(m3u8Path);
        return sumExtinfSeconds(content);
    }

    /**
     * 读取 m3u8 文件内容，失败返回 null。
     */
    public String readM3u8Content(Path m3u8Path) {
        if (m3u8Path == null || !Files.exists(m3u8Path)) {
            return null;
        }
        try {
            return FileUtil.readUtf8String(m3u8Path.toFile());
        } catch (Exception e) {
            log.debug("读取 m3u8 失败: {}", m3u8Path, e);
            return null;
        }
    }

    /**
     * 对 m3u8 文本内容累加所有 #EXTINF 时长。
     */
    public double sumExtinfSeconds(String content) {
        return sumExtinfSeconds(content, Integer.MAX_VALUE);
    }

    /**
     * 对 m3u8 文本内容累加前 upToCount 条 #EXTINF 时长。
     */
    public double sumExtinfSeconds(String content, int upToCount) {
        if (content == null || content.isBlank() || upToCount <= 0) {
            return 0;
        }
        double sum = 0;
        Matcher matcher = EXTINF_PATTERN.matcher(content);
        int count = 0;
        while (matcher.find() && count < upToCount) {
            try {
                sum += Double.parseDouble(matcher.group(1));
                count++;
            } catch (NumberFormatException e) {
                log.debug("#EXTINF 时长解析异常: {}", matcher.group(1));
            }
        }
        return sum;
    }

    /**
     * 根据已生成时长、客户端实际已消费时长与阈值决策下一步动作。
     *
     * @param generatedSeconds 已生成时长（秒）
     * @param consumedSeconds  客户端已请求分片对应的实际时长（秒）
     * @param aheadSeconds     领先阈值，达到则暂停
     * @param resumeSeconds    追近阈值，回落则继续
     * @return 节流决策
     */
    public ThrottleDecision decideThrottle(double generatedSeconds, double consumedSeconds,
                                             int aheadSeconds, int resumeSeconds) {
        double ahead = generatedSeconds - consumedSeconds;
        if (ahead >= aheadSeconds) {
            return ThrottleDecision.PAUSE;
        }
        if (ahead <= resumeSeconds) {
            return ThrottleDecision.RESUME;
        }
        return ThrottleDecision.HOLD;
    }

    /**
     * 判断转码是否已完成：进程退出且 index.m3u8 存在并包含 #EXT-X-ENDLIST。
     * <p>
     * 读取失败或文件不存在时返回 false，允许竞态（下次调度再判断）。
     */
    public boolean isTranscodeComplete(Path outputDir) {
        if (outputDir == null) {
            return false;
        }
        Path m3u8 = outputDir.resolve("index.m3u8");
        if (!Files.exists(m3u8)) {
            return false;
        }
        try {
            String content = FileUtil.readUtf8String(m3u8.toFile());
            return content != null && content.contains("#EXT-X-ENDLIST");
        } catch (Exception e) {
            log.debug("读取 m3u8 完成标记失败: {}", m3u8, e);
            return false;
        }
    }

    /**
     * 判断会话是否空闲可回收。
     * <p>
     * 进程存活时仅按 lastAccess 判断；
     * 进程已死时，已完成会话继续保留至 lastAccess 超时，未完成会话立即回收。
     */
    public boolean isSessionIdle(TranscodeSession session, Instant deadline) {
        if (session.process().isAlive()) {
            return session.lastAccess().isBefore(deadline);
        }
        return !isTranscodeComplete(session.outputDir()) || session.lastAccess().isBefore(deadline);
    }

    /**
     * 向 ffmpeg 进程发送 SIGSTOP/SIGCONT 信号。
     * <p>
     * 发送失败时仅记录 warn 日志并将会话暂停状态置为 UNKNOWN，不影响调用方定时任务。
     *
     * @param session 转码会话
     * @param signal  信号名（STOP/CONT）
     * @param action  用于日志描述的动作（暂停/继续/恢复）
     */
    public void sendSignal(TranscodeSession session, String signal, String action) {
        try {
            long pid = session.process().pid();
            Process signalProcess = new ProcessBuilder("kill", "-" + signal, String.valueOf(pid)).start();
            boolean exited = signalProcess.waitFor(5, TimeUnit.SECONDS);
            int exitValue = exited ? signalProcess.exitValue() : -1;
            if (!exited || exitValue != 0) {
                log.warn("发送 {} 信号可能失败: session={}, pid={}, exit={}", signal, session.id(), pid, exitValue);
                session.pauseState(TranscodeSession.PauseState.UNKNOWN);
            } else {
                log.info("转码进程已{}: session={}, pid={}", action, session.id(), pid);
            }
        } catch (Exception e) {
            log.warn("发送 {} 信号异常: session={}", signal, session.id(), e);
            session.pauseState(TranscodeSession.PauseState.UNKNOWN);
        }
    }

    /**
     * 当前运行环境是否为 Linux（SIGSTOP/SIGCONT 仅在该环境有效）。
     */
    public boolean isLinux() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().startsWith("linux");
    }
}
