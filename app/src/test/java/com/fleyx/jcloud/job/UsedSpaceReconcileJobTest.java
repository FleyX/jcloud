package com.fleyx.jcloud.job;

import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.support.UserUsedSpaceSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 每日已用空间对账任务测试。
 */
@Transactional
class UsedSpaceReconcileJobTest extends IntegrationTestBase {

    @Autowired
    private UsedSpaceReconcileJob reconcileJob;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private UserUsedSpaceSupport userUsedSpaceSupport;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 收敛：手工把某用户 used_space 改成脏值后执行 Job，值收敛为公式结果（本地文件 size 之和）。
     */
    @Test
    void shouldConvergeDirtyUsedSpaceToFormulaValue() {
        UserVo user = prepareUserWithStorageSpace().user();
        insertLocalFileNode(user.getId(), 100L);
        dirtyUsedSpace(user.getId(), 50L);

        reconcileJob.reconcile();

        assertEquals(100L, usedSpaceOf(user.getId()));
    }

    /**
     * 多用户隔离：一个用户 used_space 脏、一个准确，Job 后各自保持/收敛为公式值。
     */
    @Test
    void shouldRecalcEachUserIndependently() {
        UserVo dirtyUser = prepareUserWithStorageSpace().user();
        insertLocalFileNode(dirtyUser.getId(), 100L);
        dirtyUsedSpace(dirtyUser.getId(), 50L);

        UserVo cleanUser = prepareUserWithStorageSpace().user();
        insertLocalFileNode(cleanUser.getId(), 200L);
        userUsedSpaceSupport.recalcUsedSpace(cleanUser.getId());

        reconcileJob.reconcile();

        assertEquals(100L, usedSpaceOf(dirtyUser.getId()));
        assertEquals(200L, usedSpaceOf(cleanUser.getId()));
    }

    /**
     * 逻辑删除用户不参与：删除后 used_space 脏值保持不变（Job 的 selectList 与重算 SQL 均过滤 delete_at）。
     * <p>
     * MP 逻辑删除会使 userMapper.selectById 过滤已删除用户，故此处用 JdbcTemplate 裸查 t_user
     * 确认该行 used_space 未被重算。
     */
    @Test
    void shouldSkipLogicallyDeletedUser() {
        UserVo user = prepareUserWithStorageSpace().user();
        insertLocalFileNode(user.getId(), 100L);
        dirtyUsedSpace(user.getId(), 50L);
        userMapper.deleteById(user.getId());

        reconcileJob.reconcile();

        Long usedSpace = jdbcTemplate.queryForObject(
                "SELECT used_space FROM t_user WHERE id = ?", Long.class, user.getId());
        assertEquals(50L, usedSpace);
    }

    // ---------- 工具方法 ----------

    /**
     * 直接插入本地文件节点（绕过记账），使公式值 = size 而 used_space 保持 0。
     */
    private void insertLocalFileNode(String userId, long size) {
        User user = userMapper.selectById(userId);
        FileNode node = new FileNode();
        node.setUserId(userId);
        node.setParentId(FileNodeConstants.ROOT_ID);
        node.setName("file-" + size);
        node.setType(FileNodeConstants.TYPE_FILE);
        node.setSize(size);
        node.setSourceType(FileNodeConstants.SOURCE_LOCAL);
        node.setStorageSpaceId(user.getStorageSpaceId());
        node.setPath(FileNodeConstants.ROOT_ID);
        node.setStatus(1);
        fileMapper.insert(node);
    }

    /**
     * 把 used_space 改成与公式值不同的脏值。
     */
    private void dirtyUsedSpace(String userId, long dirtyValue) {
        User user = userMapper.selectById(userId);
        user.setUsedSpace(dirtyValue);
        userMapper.updateById(user);
    }

    private long usedSpaceOf(String userId) {
        User user = userMapper.selectById(userId);
        return user == null || user.getUsedSpace() == null ? 0L : user.getUsedSpace();
    }
}
