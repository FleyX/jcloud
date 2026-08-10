package com.fleyx.jcloud.job;

import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.enums.MediaScanStatus;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 启动时清理遗留扫描中状态组件测试。
 */
@Transactional
class MediaScanStartupCleanerTest extends IntegrationTestBase {

    @Autowired
    private MediaScanStartupCleaner startupCleaner;

    @Autowired
    private MediaDirectoryMapper mediaDirectoryMapper;

    @Test
    void shouldResetScanningDirectoriesToFailed() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaDirectory scanningA = insertDirectory(user.getId(), MediaScanStatus.SCANNING.name(), null);
        MediaDirectory scanningB = insertDirectory(user.getId(), MediaScanStatus.SCANNING.name(), "历史错误");

        startupCleaner.run(new DefaultApplicationArguments());

        MediaDirectory updatedA = mediaDirectoryMapper.selectById(scanningA.getId());
        assertEquals(MediaScanStatus.FAILED.name(), updatedA.getLastScanStatus());
        assertEquals("服务重启，扫描中断", updatedA.getLastScanError());
        MediaDirectory updatedB = mediaDirectoryMapper.selectById(scanningB.getId());
        assertEquals(MediaScanStatus.FAILED.name(), updatedB.getLastScanStatus());
        assertEquals("服务重启，扫描中断", updatedB.getLastScanError());
    }

    @Test
    void shouldKeepNonScanningDirectoriesUntouched() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaDirectory completed = insertDirectory(user.getId(), MediaScanStatus.COMPLETED.name(), null);
        MediaDirectory failed = insertDirectory(user.getId(), MediaScanStatus.FAILED.name(), "历史错误");

        startupCleaner.run(new DefaultApplicationArguments());

        MediaDirectory updatedCompleted = mediaDirectoryMapper.selectById(completed.getId());
        assertEquals(MediaScanStatus.COMPLETED.name(), updatedCompleted.getLastScanStatus());
        assertNull(updatedCompleted.getLastScanError());
        MediaDirectory updatedFailed = mediaDirectoryMapper.selectById(failed.getId());
        assertEquals(MediaScanStatus.FAILED.name(), updatedFailed.getLastScanStatus());
        assertEquals("历史错误", updatedFailed.getLastScanError());
    }

    @Test
    void shouldFinishCleanlyWhenNothingScanning() {
        UserVo user = prepareUserWithStorageSpace().user();
        MediaDirectory completed = insertDirectory(user.getId(), MediaScanStatus.COMPLETED.name(), null);

        assertDoesNotThrow(() -> startupCleaner.run(new DefaultApplicationArguments()));

        assertEquals(MediaScanStatus.COMPLETED.name(),
                mediaDirectoryMapper.selectById(completed.getId()).getLastScanStatus());
    }

    private MediaDirectory insertDirectory(String userId, String lastScanStatus, String lastScanError) {
        MediaDirectory directory = new MediaDirectory();
        directory.setUserId(userId);
        directory.setName("测试媒体库-" + System.nanoTime());
        directory.setMediaType("movie");
        directory.setLastScanStatus(lastScanStatus);
        directory.setLastScanError(lastScanError);
        mediaDirectoryMapper.insert(directory);
        return directory;
    }
}
