package com.fleyx.jcloud.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.constant.StorageConstant;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.dto.FileDeleteDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.vo.FileNodeVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.FileRecycleService;
import com.fleyx.jcloud.service.FileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除用户级联清理任务集成测试。
 * <p>
 * 覆盖到期清理、未到期保留与多用户隔离。本类不使用 {@code @Transactional}，
 * 测试数据按唯一用户隔离并在用例结束后清理（文件节点/回收站记录为物理删除，用户为逻辑删除）。
 */
class DeletedUserCleanupJobTest extends IntegrationTestBase {

    @Autowired
    private DeletedUserCleanupJob deletedUserCleanupJob;

    @Autowired
    private FileService fileService;

    @Autowired
    private FileRecycleService fileRecycleService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private RecycleRecordMapper recycleRecordMapper;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<String> createdUserIds = new ArrayList<>();
    private final List<String> createdSpaceIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String userId : createdUserIds) {
            recycleRecordMapper.delete(new LambdaQueryWrapper<RecycleRecord>().eq(RecycleRecord::getUserId, userId));
            fileMapper.delete(new LambdaQueryWrapper<FileNode>().eq(FileNode::getUserId, userId));
            userMapper.deleteById(userId);
        }
        createdUserIds.clear();
        createdSpaceIds.forEach(storageSpaceMapper::deleteById);
        createdSpaceIds.clear();
    }

    /**
     * 到期清理：逻辑删除满 7 天的用户，文件节点行、回收站记录与三个物理目录均被移除，用户行保留逻辑删除态。
     */
    @Test
    void shouldCleanupExpiredDeletedUser() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        register(userWithSpace);
        prepareUserData(user.getId(), userWithSpace);

        userMapper.deleteById(user.getId());
        backfillDeleteAt(user.getId(), System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8));

        deletedUserCleanupJob.cleanup();

        assertEquals(0, nodeCount(user.getId()), "文件节点行应被物理删除");
        assertEquals(0, recordCount(user.getId()), "回收站记录应被物理删除");
        assertFalse(Files.exists(userDir(userWithSpace, StorageConstant.FILES_DIR)), "files 物理目录应被删除");
        assertFalse(Files.exists(userDir(userWithSpace, StorageConstant.TRASH_DIR)), "trash 物理目录应被删除");
        assertFalse(Files.exists(userDir(userWithSpace, StorageConstant.TMP_DIR)), "tmp 物理目录应被删除");
        assertEquals(1, deletedUserRowCount(user.getId()), "用户行应保留逻辑删除态作审计");
    }

    /**
     * 未到期保留：逻辑删除未满 7 天的用户，文件节点行、回收站记录与物理目录均保持不动。
     */
    @Test
    void shouldKeepNotExpiredDeletedUser() throws Exception {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        UserVo user = userWithSpace.user();
        register(userWithSpace);
        prepareUserData(user.getId(), userWithSpace);

        userMapper.deleteById(user.getId());

        deletedUserCleanupJob.cleanup();

        assertEquals(1, nodeCount(user.getId()), "文件节点行应保留");
        assertEquals(1, recordCount(user.getId()), "回收站记录应保留");
        assertTrue(Files.exists(userDir(userWithSpace, StorageConstant.FILES_DIR)), "files 物理目录应保留");
        assertTrue(Files.exists(userDir(userWithSpace, StorageConstant.TRASH_DIR)), "trash 物理目录应保留");
        assertTrue(Files.exists(userDir(userWithSpace, StorageConstant.TMP_DIR)), "tmp 物理目录应保留");
    }

    /**
     * 多用户隔离：一个到期一个未到期，只清理到期的用户数据，未到期用户数据保持不动。
     */
    @Test
    void shouldOnlyCleanupExpiredUserAmongMultiple() throws Exception {
        UserWithSpace expired = prepareUserWithStorageSpace();
        register(expired);
        prepareUserData(expired.user().getId(), expired);

        UserWithSpace recent = prepareUserWithStorageSpace();
        register(recent);
        prepareUserData(recent.user().getId(), recent);

        userMapper.deleteById(expired.user().getId());
        backfillDeleteAt(expired.user().getId(), System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8));
        userMapper.deleteById(recent.user().getId());

        deletedUserCleanupJob.cleanup();

        // 到期用户被级联清理
        assertEquals(0, nodeCount(expired.user().getId()), "到期用户文件节点行应被清理");
        assertEquals(0, recordCount(expired.user().getId()), "到期用户回收站记录应被清理");
        assertFalse(Files.exists(userDir(expired, StorageConstant.FILES_DIR)));
        assertFalse(Files.exists(userDir(expired, StorageConstant.TRASH_DIR)));
        assertFalse(Files.exists(userDir(expired, StorageConstant.TMP_DIR)));
        // 未到期用户数据保持不动
        assertEquals(1, nodeCount(recent.user().getId()), "未到期用户文件节点行应保留");
        assertEquals(1, recordCount(recent.user().getId()), "未到期用户回收站记录应保留");
        assertTrue(Files.exists(userDir(recent, StorageConstant.FILES_DIR)));
        assertTrue(Files.exists(userDir(recent, StorageConstant.TRASH_DIR)));
        assertTrue(Files.exists(userDir(recent, StorageConstant.TMP_DIR)));
    }

    // ---------- 工具方法 ----------

    private void register(UserWithSpace userWithSpace) {
        createdUserIds.add(userWithSpace.user().getId());
        createdSpaceIds.add(userWithSpace.space().getId());
    }

    /**
     * 准备用户数据：一个保留在 files 目录的文件节点、一个删除进回收站的文件（记录 + trash 目录）与一个手工 tmp 目录。
     */
    private void prepareUserData(String userId, UserWithSpace userWithSpace) throws Exception {
        fileService.upload(buildFile("kept.txt", "kept"), userId, FileNodeConstants.ROOT_ID, null);
        FileNodeVo trashFile = fileService.upload(buildFile("trash.txt", "trash"), userId, FileNodeConstants.ROOT_ID, null);
        FileDeleteDto deleteDto = new FileDeleteDto();
        deleteDto.setIds(List.of(trashFile.getId()));
        fileRecycleService.deleteToTrash(deleteDto, userId);

        Path tmpRoot = Path.of(userWithSpace.spacePath().toString(), StorageConstant.TMP_DIR,
                userWithSpace.user().getUsername());
        Files.createDirectories(tmpRoot);
        Files.writeString(tmpRoot.resolve("leftover.bin"), "tmp");
    }

    private long nodeCount(String userId) {
        return fileMapper.selectCount(new LambdaQueryWrapper<FileNode>().eq(FileNode::getUserId, userId));
    }

    private long recordCount(String userId) {
        return recycleRecordMapper.selectCount(new LambdaQueryWrapper<RecycleRecord>().eq(RecycleRecord::getUserId, userId));
    }

    private Path userDir(UserWithSpace userWithSpace, String dirName) {
        return Path.of(userWithSpace.spacePath().toString(), dirName, userWithSpace.user().getUsername());
    }

    private void backfillDeleteAt(String userId, long deleteAtMillis) {
        jdbcTemplate.update("UPDATE t_user SET delete_at = ? WHERE id = ?", deleteAtMillis, userId);
    }

    private long deletedUserRowCount(String userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE id = ? AND delete_at > 0", Long.class, userId);
        return count == null ? 0L : count;
    }

    private MultipartFile buildFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes());
    }
}
