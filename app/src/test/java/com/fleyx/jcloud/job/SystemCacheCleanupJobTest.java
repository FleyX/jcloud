package com.fleyx.jcloud.job;

import cn.hutool.core.io.FileUtil;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.mapper.PreviewFileMapper;
import com.fleyx.jcloud.model.po.PreviewFile;
import com.fleyx.jcloud.service.support.MediaSubtitleConvertSupport;
import com.fleyx.jcloud.service.support.SystemCacheDirProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统缓存清理任务测试：覆盖预览、字幕、转码残留三类清理的过期/未过期边界与启动清空。
 * <p>
 * 文件统一落在 {@code target/test-system-cache}（由 mvn clean 清理），用例结束删除本类创建的文件。
 */
@Transactional
class SystemCacheCleanupJobTest extends IntegrationTestBase {

    @Autowired
    private SystemCacheCleanupJob systemCacheCleanupJob;

    @Autowired
    private SystemCacheDirProvider systemCacheDirProvider;

    @Autowired
    private PreviewFileMapper previewFileMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Path> createdPaths = new ArrayList<>();

    @AfterEach
    void deleteCreatedFiles() {
        createdPaths.forEach(FileUtil::del);
        createdPaths.clear();
    }

    /**
     * 预览清理：过期行与物理文件一并删除，未过期行与文件保留。
     */
    @Test
    void shouldCleanupExpiredPreviewAndKeepRecent() throws IOException {
        PreviewRecord expired = createPreviewFile("expired");
        backfillCreateTime(expired.id(), LocalDateTime.now().minusDays(100));
        PreviewRecord recent = createPreviewFile("recent");

        systemCacheCleanupJob.cleanup();

        assertEquals(0, previewRowCount(expired.id()), "过期预览行应被物理删除");
        assertFalse(Files.exists(expired.file()), "过期预览物理文件应被删除");
        assertEquals(1, previewRowCount(recent.id()), "未过期预览行应保留");
        assertTrue(Files.exists(recent.file()), "未过期预览物理文件应保留");
    }

    /**
     * 字幕清理：修改时间超过 30 天的文件删除，未超期的保留。
     */
    @Test
    void shouldCleanupExpiredSubtitleAndKeepRecent() throws IOException {
        Path subtitleDir = Files.createDirectories(
                systemCacheDirProvider.getCacheDir().resolve(MediaSubtitleConvertSupport.SUBTITLE_CACHE_DIR));
        Path expired = uniquePath(subtitleDir, "expired.vtt");
        Path recent = uniquePath(subtitleDir, "recent.vtt");
        Files.writeString(expired, "expired");
        Files.writeString(recent, "recent");
        setLastModified(expired, 31);
        setLastModified(recent, 29);

        systemCacheCleanupJob.cleanup();

        assertFalse(Files.exists(expired), "过期字幕缓存文件应被删除");
        assertTrue(Files.exists(recent), "未过期字幕缓存文件应保留");
    }

    /**
     * 转码残留清理：目录修改时间超过 1 天的会话目录递归删除，活跃会话目录保留。
     */
    @Test
    void shouldCleanupResidueTranscodeSessionAndKeepActive() throws IOException {
        Path transcodeDir = Files.createDirectories(systemCacheDirProvider.getCacheDir().resolve("media/transcode"));
        Path expired = createSessionDir(transcodeDir, "expired-session");
        Path active = createSessionDir(transcodeDir, "active-session");
        Files.setLastModifiedTime(expired, FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(25)));
        Files.setLastModifiedTime(active, FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(23)));

        systemCacheCleanupJob.cleanup();

        assertFalse(Files.exists(expired), "超期转码残留会话目录应被递归删除");
        assertTrue(Files.exists(active), "活跃转码会话目录应保留");
    }

    /**
     * 启动清空：转码根目录内容被清空，根目录本身保留。
     */
    @Test
    void shouldClearTranscodeDirOnStartup() throws IOException {
        Path transcodeDir = Files.createDirectories(systemCacheDirProvider.getCacheDir().resolve("media/transcode"));
        Path session = createSessionDir(transcodeDir, "startup-session");
        Path stray = uniquePath(transcodeDir, "stray.ts");

        systemCacheCleanupJob.clearTranscodeDirOnStartup();

        assertTrue(Files.isDirectory(transcodeDir), "转码根目录本身应保留");
        assertFalse(Files.exists(session), "转码根目录下的会话目录应被清空");
        assertFalse(Files.exists(stray), "转码根目录下的散落文件应被清空");
    }

    // ---------- 工具方法 ----------

    private PreviewRecord createPreviewFile(String name) throws IOException {
        String nodeId = "pf" + Long.toUnsignedString(System.nanoTime(), 36);
        String relativePath = "previews/it/" + name + "-" + Long.toUnsignedString(System.nanoTime(), 36) + ".jpg";
        Path file = systemCacheDirProvider.getCacheDir().resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, name);
        createdPaths.add(file);

        PreviewFile preview = new PreviewFile();
        preview.setFileNodeId(nodeId);
        preview.setType("thumbnail");
        preview.setRelativePath(relativePath);
        preview.setSize(0L);
        preview.setStatus(1);
        previewFileMapper.insert(preview);
        return new PreviewRecord(preview.getId(), file);
    }

    private void backfillCreateTime(String id, LocalDateTime createTime) {
        jdbcTemplate.update("UPDATE t_preview_file SET create_time = ? WHERE id = ?", Timestamp.valueOf(createTime), id);
    }

    private Path createSessionDir(Path transcodeDir, String name) throws IOException {
        Path session = Files.createDirectories(uniquePath(transcodeDir, name));
        Path chunk = session.resolve("chunk-0.ts");
        Files.writeString(chunk, "chunk");
        createdPaths.add(session);
        return session;
    }

    private Path uniquePath(Path parent, String name) {
        return parent.resolve(name + "-" + Long.toUnsignedString(System.nanoTime(), 36));
    }

    private void setLastModified(Path path, long daysAgo) throws IOException {
        Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysAgo)));
    }

    private long previewRowCount(String id) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_preview_file WHERE id = ?", Long.class, id);
        return count == null ? 0L : count;
    }

    private record PreviewRecord(String id, Path file) {
    }
}
