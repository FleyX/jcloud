package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.RecycleRecordMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.RecycleRecord;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.UserVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户已用空间集中记账支撑组件集成测试。
 * <p>
 * 覆盖原子累加、扣减到 0 截断、逻辑口径重算与并发不丢更新。
 * 并发用例依赖各语句独立事务提交，故本类不使用 {@code @Transactional}，
 * 测试数据按唯一用户隔离并在用例结束后清理（文件节点/回收站记录为物理删除，用户为逻辑删除）。
 */
class UserUsedSpaceSupportTest extends IntegrationTestBase {

    @Autowired
    private UserUsedSpaceSupport userUsedSpaceSupport;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private RecycleRecordMapper recycleRecordMapper;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

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
     * 累加：初始 0，+100 后为 100。
     */
    @Test
    void shouldAccumulateUsedSpace() {
        UserVo user = prepareUser();

        userUsedSpaceSupport.addUsedSpace(user.getId(), 100);

        assertEquals(100L, usedSpaceOf(user.getId()));
    }

    /**
     * 负增量截断：-50 再 -100 后为 0（结果不为负）。
     */
    @Test
    void shouldClampUsedSpaceAtZero() {
        UserVo user = prepareUser();

        userUsedSpaceSupport.addUsedSpace(user.getId(), -50);
        assertEquals(0L, usedSpaceOf(user.getId()));

        userUsedSpaceSupport.addUsedSpace(user.getId(), -100);
        assertEquals(0L, usedSpaceOf(user.getId()));
    }

    /**
     * 并发不丢更新：50 个虚拟线程各 +1，最终值精确等于 50（原子 SQL 生效，无需用户写锁）。
     */
    @Test
    void shouldNotLoseUpdatesUnderConcurrency() throws Exception {
        UserVo user = prepareUser();
        int taskCount = 50;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(taskCount);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < taskCount; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        userUsedSpaceSupport.addUsedSpace(user.getId(), 1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(30, TimeUnit.SECONDS), "并发累加未在超时内完成");
        }

        assertEquals(taskCount, usedSpaceOf(user.getId()));
    }

    /**
     * 逻辑口径重算：本地文件 100+200、回收站 50，远程文件 999 与文件夹 888 均不计入，结果为 350。
     */
    @Test
    void shouldRecalcUsedSpaceByLogicFormula() {
        UserVo user = prepareUser();
        insertFileNode(user.getId(), 100L, FileNodeConstants.TYPE_FILE, FileNodeConstants.SOURCE_LOCAL);
        insertFileNode(user.getId(), 200L, FileNodeConstants.TYPE_FILE, FileNodeConstants.SOURCE_LOCAL);
        insertFileNode(user.getId(), 999L, FileNodeConstants.TYPE_FILE, FileNodeConstants.SOURCE_REMOTE);
        insertFileNode(user.getId(), 888L, FileNodeConstants.TYPE_FOLDER, FileNodeConstants.SOURCE_LOCAL);
        RecycleRecord record = new RecycleRecord();
        record.setUserId(user.getId());
        record.setName("deleted.bin");
        record.setType(FileNodeConstants.TYPE_FILE);
        record.setTotalSize(50L);
        recycleRecordMapper.insert(record);

        long recalculated = userUsedSpaceSupport.recalcUsedSpace(user.getId());

        assertEquals(350L, recalculated);
        assertEquals(350L, usedSpaceOf(user.getId()));
    }

    // ---------- 工具方法 ----------

    private UserVo prepareUser() {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace();
        createdUserIds.add(userWithSpace.user().getId());
        createdSpaceIds.add(userWithSpace.space().getId());
        return userWithSpace.user();
    }

    private void insertFileNode(String userId, long size, String type, String sourceType) {
        FileNode node = new FileNode();
        node.setUserId(userId);
        node.setParentId(FileNodeConstants.ROOT_ID);
        node.setName(type + "-" + size);
        node.setType(type);
        node.setSize(size);
        node.setSourceType(sourceType);
        node.setPath(FileNodeConstants.ROOT_ID);
        node.setStatus(1);
        if (FileNodeConstants.SOURCE_LOCAL.equals(sourceType)) {
            User user = userMapper.selectById(userId);
            node.setStorageSpaceId(user.getStorageSpaceId());
        }
        fileMapper.insert(node);
    }

    private long usedSpaceOf(String userId) {
        User user = userMapper.selectById(userId);
        return user == null || user.getUsedSpace() == null ? 0L : user.getUsedSpace();
    }
}
