package com.fleyx.jcloud.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.common.IntegrationTestBase;
import com.fleyx.jcloud.common.enums.MediaScanStatus;
import com.fleyx.jcloud.common.enums.NotificationEventType;
import com.fleyx.jcloud.common.enums.SyncTaskStatus;
import com.fleyx.jcloud.mapper.MediaDirectoryMapper;
import com.fleyx.jcloud.mapper.NotificationMapper;
import com.fleyx.jcloud.mapper.RemoteMountMapper;
import com.fleyx.jcloud.mapper.RemoteSyncTaskMapper;
import com.fleyx.jcloud.mapper.RoleMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.mapper.UserRoleMapper;
import com.fleyx.jcloud.model.dto.UserRegisterDto;
import com.fleyx.jcloud.model.po.MediaDirectory;
import com.fleyx.jcloud.model.po.Notification;
import com.fleyx.jcloud.model.po.RemoteMount;
import com.fleyx.jcloud.model.po.RemoteSyncTask;
import com.fleyx.jcloud.model.po.Role;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.UserRole;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.AuthService;
import com.fleyx.jcloud.service.MediaScanService;
import com.fleyx.jcloud.service.impl.RemoteMountSyncExecutor;
import com.fleyx.jcloud.service.impl.RemoteProtocolAdapterFactory;
import com.fleyx.jcloud.util.IdUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 业务事件源接入集成测试（工单 06）。
 * <p>
 * 覆盖配额/容量告警（80% 阈值边界、24h Redis 去重、超管抄送）与远程同步失败、媒体扫描失败、
 * 新用户注册三类事件。事件监听异步执行，正向用例轮询落库结果（100ms 间隔、10s 超时）；
 * 不使用事务回滚（异步线程看不到未提交数据），测试数据按唯一用户隔离并在用例结束后物理清理，
 * 同时清理 Redis 去重标记避免污染后续用例。
 */
class NotificationEventSourceTest extends IntegrationTestBase {

    @Autowired
    private UserUsedSpaceSupport userUsedSpaceSupport;

    @Autowired
    private NotificationAlertDedupSupport alertDedupSupport;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StorageSpaceMapper storageSpaceMapper;

    @Autowired
    private MediaDirectoryMapper mediaDirectoryMapper;

    @Autowired
    private RemoteMountMapper remoteMountMapper;

    @Autowired
    private RemoteSyncTaskMapper remoteSyncTaskMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private RemoteMountSyncExecutor remoteMountSyncExecutor;

    @Autowired
    private MediaScanService mediaScanService;

    @Autowired
    private AuthService authService;

    @MockitoBean
    private RemoteProtocolAdapterFactory adapterFactory;

    private final List<String> createdUserIds = new ArrayList<>();

    private final List<String> createdSpaceIds = new ArrayList<>();

    private final List<String> createdMountIds = new ArrayList<>();

    private final List<String> createdTaskIds = new ArrayList<>();

    private final List<String> createdDirectoryIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String userId : createdUserIds) {
            notificationMapper.delete(new LambdaQueryWrapper<Notification>().eq(Notification::getUserId, userId));
        }
        for (String userId : createdUserIds) {
            userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        }
        createdUserIds.forEach(userMapper::deleteById);
        createdSpaceIds.forEach(storageSpaceMapper::deleteById);
        createdTaskIds.forEach(remoteSyncTaskMapper::deleteById);
        createdMountIds.forEach(remoteMountMapper::deleteById);
        createdDirectoryIds.forEach(mediaDirectoryMapper::deleteById);
        createdUserIds.forEach(this::clearAlertKeys);
        createdSpaceIds.forEach(this::clearAlertKeys);
        createdUserIds.clear();
        createdSpaceIds.clear();
        createdMountIds.clear();
        createdTaskIds.clear();
        createdDirectoryIds.clear();
    }

    /**
     * 配额告警：79% 不告警、达 80% 告警且抄送全部超管；24 小时内重复超限不再发。
     */
    @Test
    void shouldAlertQuotaAtThresholdAndDedupWithin24Hours() {
        UserWithSpace user = prepareUser(100L);
        // 存储空间容量放到安全水位，隔离配额告警用例对容量告警的干扰
        updateSpaceUsage(user.space().getId(), 1_000_000L, 0L);
        UserWithSpace admin = prepareUser(DEFAULT_QUOTA_BYTES);
        bindSuperAdmin(admin.user().getId());

        userUsedSpaceSupport.addUsedSpace(user.user().getId(), 79L);
        assertEquals(0L, countNotifications(user.user().getId(), NotificationEventType.QUOTA_ALERT),
                "79% 不应触发配额告警");

        userUsedSpaceSupport.addUsedSpace(user.user().getId(), 1L);
        awaitNotificationCount(user.user().getId(), NotificationEventType.QUOTA_ALERT, 1);
        awaitNotificationCount(admin.user().getId(), NotificationEventType.QUOTA_ALERT, 1);
        Notification userNotice = firstNotification(user.user().getId(), NotificationEventType.QUOTA_ALERT);
        assertEquals("配额告警", userNotice.getTitle());
        assertTrue(userNotice.getContent().contains("已用空间"));
        Notification adminNotice = firstNotification(admin.user().getId(), NotificationEventType.QUOTA_ALERT);
        assertTrue(adminNotice.getContent().contains(user.user().getUsername()), "抄送内容应含用户名");

        userUsedSpaceSupport.addUsedSpace(user.user().getId(), 5L);
        sleepQuietly(300);
        assertEquals(1L, countNotifications(user.user().getId(), NotificationEventType.QUOTA_ALERT),
                "24 小时内重复超限不应再发");
        assertFalse(alertDedupSupport.tryMark("quota:" + user.user().getId()), "去重标记应已存在");
    }

    /**
     * 容量告警：79% 不告警、达 80% 全部超管告警；24 小时内重复超限不再发。
     */
    @Test
    void shouldAlertStorageCapacityAtThresholdAndDedupWithin24Hours() {
        UserWithSpace user = prepareUser(0L);
        updateSpaceUsage(user.space().getId(), 100L, 79L);
        UserWithSpace admin = prepareUser(DEFAULT_QUOTA_BYTES);
        bindSuperAdmin(admin.user().getId());

        userUsedSpaceSupport.addUsedSpace(user.user().getId(), 1L);
        assertEquals(0L, countNotifications(admin.user().getId(), NotificationEventType.STORAGE_CAPACITY_ALERT),
                "79% 不应触发容量告警");

        updateSpaceUsage(user.space().getId(), 100L, 80L);
        userUsedSpaceSupport.addUsedSpace(user.user().getId(), 0L);
        awaitNotificationCount(admin.user().getId(), NotificationEventType.STORAGE_CAPACITY_ALERT, 1);
        Notification notice = firstNotification(admin.user().getId(), NotificationEventType.STORAGE_CAPACITY_ALERT);
        assertEquals("存储空间容量告警", notice.getTitle());
        assertTrue(notice.getContent().contains(user.space().getName()), "告警内容应含存储空间名");
        assertEquals(0L, countNotifications(user.user().getId(), NotificationEventType.STORAGE_CAPACITY_ALERT),
                "容量告警只发管理员");

        updateSpaceUsage(user.space().getId(), 100L, 90L);
        userUsedSpaceSupport.addUsedSpace(user.user().getId(), 0L);
        sleepQuietly(300);
        assertEquals(1L, countNotifications(admin.user().getId(), NotificationEventType.STORAGE_CAPACITY_ALERT),
                "24 小时内重复超限不应再发");
        assertFalse(alertDedupSupport.tryMark("capacity:" + user.space().getId()), "去重标记应已存在");
    }

    /**
     * 远程挂载同步失败：属主收到事件，内容含挂载点名与失败原因。
     */
    @Test
    void shouldNotifyOwnerWhenRemoteMountSyncFails() {
        UserWithSpace user = prepareUser(DEFAULT_QUOTA_BYTES);
        RemoteMount mount = insertMount(user.user().getId(), "挂载-" + System.nanoTime());
        RemoteSyncTask task = insertPendingTask(mount.getId());
        when(adapterFactory.create(any())).thenThrow(new RuntimeException("连接远程失败"));

        remoteMountSyncExecutor.execute(task.getId());

        assertEquals(SyncTaskStatus.FAILED.getValue(), remoteSyncTaskMapper.selectById(task.getId()).getStatus());
        awaitNotificationCount(user.user().getId(), NotificationEventType.REMOTE_SYNC_FAILED, 1);
        Notification notice = firstNotification(user.user().getId(), NotificationEventType.REMOTE_SYNC_FAILED);
        assertEquals("远程挂载同步失败", notice.getTitle());
        assertTrue(notice.getContent().contains(mount.getName()));
        assertTrue(notice.getContent().contains("连接远程失败"));
    }

    /**
     * 媒体库扫描失败：属主收到事件，内容含媒体库名与失败原因。
     * <p>
     * 通过非法 media_type 注入扫描异常（{@code MediaType.of} 返回 null 后 switch 抛 NPE），
     * 命中 {@code scanOnce} 的失败收口分支。
     */
    @Test
    void shouldNotifyOwnerWhenMediaScanFails() {
        UserWithSpace user = prepareUser(DEFAULT_QUOTA_BYTES);
        MediaDirectory directory = new MediaDirectory();
        directory.setId(IdUtil.nextId());
        directory.setUserId(user.user().getId());
        directory.setName("媒体库-" + System.nanoTime());
        directory.setMediaType("invalid-type");
        mediaDirectoryMapper.insert(directory);
        createdDirectoryIds.add(directory.getId());

        mediaScanService.scan(directory.getId());

        assertEquals(MediaScanStatus.FAILED.name(),
                mediaDirectoryMapper.selectById(directory.getId()).getLastScanStatus());
        awaitNotificationCount(user.user().getId(), NotificationEventType.MEDIA_SCAN_FAILED, 1);
        Notification notice = firstNotification(user.user().getId(), NotificationEventType.MEDIA_SCAN_FAILED);
        assertEquals("媒体库扫描失败", notice.getTitle());
        assertTrue(notice.getContent().contains(directory.getName()));
    }

    /**
     * 新用户注册成功：事务提交后全部超管收到事件，内容含新用户名。
     */
    @Test
    void shouldNotifyAdminsWhenNewUserRegisters() {
        UserWithSpace admin = prepareUser(DEFAULT_QUOTA_BYTES);
        bindSuperAdmin(admin.user().getId());
        String username = "reg_" + Long.toUnsignedString(System.nanoTime(), 36);
        UserRegisterDto dto = new UserRegisterDto();
        dto.setUsername(username);
        dto.setPassword("123456");

        UserVo registered = authService.register(dto);
        createdUserIds.add(registered.getId());

        awaitNotificationCount(admin.user().getId(), NotificationEventType.USER_REGISTERED, 1);
        Notification notice = firstNotification(admin.user().getId(), NotificationEventType.USER_REGISTERED);
        assertEquals("新用户注册", notice.getTitle());
        assertTrue(notice.getContent().contains(username));
    }

    // ---------- 工具方法 ----------

    private UserWithSpace prepareUser(long quotaBytes) {
        UserWithSpace userWithSpace = prepareUserWithStorageSpace(quotaBytes);
        createdUserIds.add(userWithSpace.user().getId());
        createdSpaceIds.add(userWithSpace.space().getId());
        return userWithSpace;
    }

    private void updateSpaceUsage(String spaceId, long capacity, long usedSpace) {
        StorageSpace update = new StorageSpace();
        update.setId(spaceId);
        update.setCapacity(capacity);
        update.setUsedSpace(usedSpace);
        storageSpaceMapper.updateById(update);
    }

    private void bindSuperAdmin(String userId) {
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getCode, UserVoEnrichSupport.SUPER_ADMIN_ROLE_CODE)
                .last("LIMIT 1"));
        UserRole relation = new UserRole();
        relation.setUserId(userId);
        relation.setRoleId(role.getId());
        userRoleMapper.insert(relation);
    }

    private RemoteMount insertMount(String userId, String name) {
        RemoteMount mount = new RemoteMount();
        mount.setId(IdUtil.nextId());
        mount.setUserId(userId);
        mount.setName(name);
        mount.setType("webdav");
        mount.setEnabled(0);
        mount.setDeleteAt(0L);
        remoteMountMapper.insert(mount);
        createdMountIds.add(mount.getId());
        return mount;
    }

    private RemoteSyncTask insertPendingTask(String remoteMountId) {
        RemoteSyncTask task = new RemoteSyncTask();
        task.setId(IdUtil.nextId());
        task.setRemoteMountId(remoteMountId);
        task.setType("manual");
        task.setStatus(SyncTaskStatus.PENDING.getValue());
        task.setTotalCount(0L);
        task.setSuccessCount(0L);
        task.setFailCount(0L);
        task.setDeleteAt(0L);
        remoteSyncTaskMapper.insert(task);
        createdTaskIds.add(task.getId());
        return task;
    }

    private void clearAlertKeys(String id) {
        redissonClient.getBucket(NotificationAlertDedupSupport.KEY_PREFIX + "quota:" + id,
                StringCodec.INSTANCE).delete();
        redissonClient.getBucket(NotificationAlertDedupSupport.KEY_PREFIX + "capacity:" + id,
                StringCodec.INSTANCE).delete();
    }

    private long countNotifications(String userId, NotificationEventType eventType) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getEventType, eventType.getValue()));
    }

    private Notification firstNotification(String userId, NotificationEventType eventType) {
        List<Notification> list = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getEventType, eventType.getValue())
                .orderByAsc(Notification::getId));
        assertFalse(list.isEmpty(), "通知未落库: userId=" + userId + ", eventType=" + eventType.getValue());
        return list.get(0);
    }

    private void awaitNotificationCount(String userId, NotificationEventType eventType, long expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (countNotifications(userId, eventType) < expected) {
            if (System.currentTimeMillis() > deadline) {
                fail("通知未在超时内落库: userId=" + userId + ", eventType=" + eventType.getValue());
            }
            sleepQuietly();
        }
    }

    private void sleepQuietly() {
        sleepQuietly(100);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }
}
