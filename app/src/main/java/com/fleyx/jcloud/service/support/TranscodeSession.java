package com.fleyx.jcloud.service.support;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HLS 实时转码会话数据对象。
 */
public final class TranscodeSession {

    /**
     * 进程暂停状态。
     */
    public enum PauseState {
        RUNNING, PAUSED, UNKNOWN
    }

    private final String id;
    private final String userId;
    private final Path outputDir;
    private final Process process;
    private final String encoder;
    private volatile Instant lastAccess;
    private volatile Instant lastHeartbeatAt;
    private volatile boolean failed;
    private final AtomicInteger maxRequestedSegmentIndex = new AtomicInteger(-1);
    private volatile PauseState pauseState = PauseState.RUNNING;

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

    /**
     * 最近一次收到前端播放页心跳的时间，null 表示从未收到（API 客户端等非心跳会话）。
     */
    public Instant lastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void lastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public boolean failed() {
        return failed;
    }

    public void failed(boolean failed) {
        this.failed = failed;
    }

    public AtomicInteger maxRequestedSegmentIndex() {
        return maxRequestedSegmentIndex;
    }

    public PauseState pauseState() {
        return pauseState;
    }

    public void pauseState(PauseState pauseState) {
        this.pauseState = pauseState;
    }
}
