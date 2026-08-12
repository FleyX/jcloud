package com.fleyx.jcloud.job;

import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.constant.StorageConstant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分片上传临时目录清理任务测试。
 */
@Transactional
class ChunkedUploadTempCleanupJobTest extends IntegrationTestBase {

    @Autowired
    private ChunkedUploadTempCleanupJob cleanupJob;

    /**
     * 超期目录（分片修改时间为 25 小时前）被整体删除。
     */
    @Test
    void shouldDeleteExpiredTempDir() throws IOException {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        Path expired = createTempUploadDir(userWithSpace, "task-1", true);

        cleanupJob.cleanup();

        assertFalse(Files.exists(expired));
    }

    /**
     * 未超期目录保留。
     */
    @Test
    void shouldKeepRecentTempDir() throws IOException {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        Path recent = createTempUploadDir(userWithSpace, "task-2", false);

        cleanupJob.cleanup();

        assertTrue(Files.exists(recent));
    }

    /**
     * 多用户多存储空间：一次执行清理全部超期目录。
     */
    @Test
    void shouldCleanupExpiredDirsAcrossSpacesAndUsers() throws IOException {
        UserWithSpace first = prepareUserWithStorageSpace();
        Path firstExpired = createTempUploadDir(first, "task-1", true);

        UserWithSpace second = prepareUserWithStorageSpace();
        Path secondExpired = createTempUploadDir(second, "task-2", true);

        cleanupJob.cleanup();

        assertFalse(Files.exists(firstExpired));
        assertFalse(Files.exists(secondExpired));
    }

    /**
     * 不误伤：清理 tmp 下超期目录时，files/trash 目录不受影响。
     */
    @Test
    void shouldNotTouchFilesAndTrashDirs() throws IOException {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        String username = userWithSpace.user().getUsername();
        Path spacePath = userWithSpace.spacePath();
        Path keepFile = Files.createDirectories(spacePath.resolve(StorageConstant.FILES_DIR).resolve(username))
                .resolve("keep.txt");
        Files.writeString(keepFile, "keep");
        Path keepInTrash = Files
                .createDirectories(spacePath.resolve(StorageConstant.TRASH_DIR).resolve(username).resolve("keep"))
                .resolve("keep.txt");
        Files.writeString(keepInTrash, "keep");
        Path expired = createTempUploadDir(userWithSpace, "task-1", true);

        cleanupJob.cleanup();

        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(keepFile));
        assertTrue(Files.exists(keepInTrash));
    }

    /**
     * 空 tmp 或 tmp 不存在时不报错。
     */
    @Test
    void shouldSkipWhenTempDirMissingOrEmpty() throws IOException {
        // tmp 不存在：空间创建后未产生任何上传目录
        prepareUserWithStorageSpace();
        cleanupJob.cleanup();

        // tmp 存在但为空
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        Files.createDirectories(userWithSpace.spacePath().resolve(StorageConstant.TMP_DIR));
        cleanupJob.cleanup();
    }

    /**
     * 在空间 tmp 下构造 username/uploadId 分片上传临时目录，可选将分片修改时间改为超期。
     */
    private Path createTempUploadDir(UserWithSpace userWithSpace, String uploadId, boolean expired)
            throws IOException {
        Path dir = userWithSpace.spacePath()
                .resolve(StorageConstant.TMP_DIR)
                .resolve(userWithSpace.user().getUsername())
                .resolve(uploadId);
        Path chunk = dir.resolve("chunk-0");
        Files.createDirectories(dir);
        Files.writeString(chunk, "chunk-data");
        if (expired) {
            Files.setLastModifiedTime(chunk,
                    FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(25)));
        }
        return dir;
    }
}
