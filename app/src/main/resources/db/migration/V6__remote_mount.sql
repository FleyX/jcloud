-- 远程资源挂载功能：支持 WebDAV 远程挂载、元数据同步、定时同步任务

-- ================== 文件节点表扩展来源标识 ==================
ALTER TABLE t_file_node
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(32) NOT NULL DEFAULT 'local',
    ADD COLUMN IF NOT EXISTS remote_mount_id VARCHAR(13);

ALTER TABLE t_file_node
    ALTER COLUMN storage_space_id DROP NOT NULL;

COMMENT ON COLUMN t_file_node.source_type IS '来源类型：local 本地存储空间 / remote 远程挂载';
COMMENT ON COLUMN t_file_node.remote_mount_id IS '远程挂载 ID，source_type=remote 时有效';
COMMENT ON COLUMN t_file_node.storage_space_id IS '本地存储空间 ID，source_type=local 时有效';
COMMENT ON COLUMN t_file_node.hash IS '内容标识符：本地文件为身份 hash，远程文件为 etag';

CREATE INDEX IF NOT EXISTS idx_file_node_source_type ON t_file_node (source_type);
CREATE INDEX IF NOT EXISTS idx_file_node_remote_mount_id ON t_file_node (remote_mount_id);
CREATE INDEX IF NOT EXISTS idx_file_node_user_source ON t_file_node (user_id, source_type);

-- ================== 远程挂载配置表 ==================
CREATE TABLE IF NOT EXISTS t_remote_mount
(
    id               VARCHAR(13) PRIMARY KEY,
    user_id          VARCHAR(13)  NOT NULL,
    name             VARCHAR(255) NOT NULL,
    type             VARCHAR(32)  NOT NULL DEFAULT 'webdav',
    enabled          SMALLINT     NOT NULL DEFAULT 1,
    cron_expr        VARCHAR(128),
    next_sync_time   TIMESTAMP,
    last_sync_time   TIMESTAMP,
    last_sync_status VARCHAR(32),
    last_sync_error  VARCHAR(4000),
    config           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_remote_mount_user_name_delete_at UNIQUE (user_id, name, delete_at)
);

COMMENT ON TABLE t_remote_mount IS '远程挂载配置表';
COMMENT ON COLUMN t_remote_mount.id IS '挂载点 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN t_remote_mount.user_id IS '所属用户 ID';
COMMENT ON COLUMN t_remote_mount.name IS '挂载点显示名，也是文件树根目录下文件夹名称';
COMMENT ON COLUMN t_remote_mount.type IS '协议类型：webdav / s3 / nfs 等，首期仅 webdav';
COMMENT ON COLUMN t_remote_mount.enabled IS '是否启用定时同步：1 启用，0 禁用';
COMMENT ON COLUMN t_remote_mount.cron_expr IS '定时同步 cron 表达式';
COMMENT ON COLUMN t_remote_mount.next_sync_time IS '下次定时同步时间';
COMMENT ON COLUMN t_remote_mount.last_sync_time IS '上次同步完成时间';
COMMENT ON COLUMN t_remote_mount.last_sync_status IS '上次同步状态：COMPLETED / FAILED / PARTIAL';
COMMENT ON COLUMN t_remote_mount.last_sync_error IS '上次同步错误信息';
COMMENT ON COLUMN t_remote_mount.config IS '协议配置 JSON，如 url、username、password、rootPath 等';
COMMENT ON COLUMN t_remote_mount.create_time IS '创建时间';
COMMENT ON COLUMN t_remote_mount.update_time IS '更新时间';
COMMENT ON COLUMN t_remote_mount.delete_at IS '逻辑删除时间戳：0 表示未删除';

CREATE INDEX IF NOT EXISTS idx_remote_mount_user_id ON t_remote_mount (user_id);
CREATE INDEX IF NOT EXISTS idx_remote_mount_enabled_next_time ON t_remote_mount (enabled, next_sync_time);

-- ================== 远程同步任务记录表 ==================
CREATE TABLE IF NOT EXISTS t_remote_sync_task
(
    id              VARCHAR(13) PRIMARY KEY,
    remote_mount_id VARCHAR(13)  NOT NULL,
    type            VARCHAR(32)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    start_time      TIMESTAMP,
    end_time        TIMESTAMP,
    total_count     BIGINT       NOT NULL DEFAULT 0,
    success_count   BIGINT       NOT NULL DEFAULT 0,
    fail_count      BIGINT       NOT NULL DEFAULT 0,
    error_msg       VARCHAR(4000),
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at       BIGINT       NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_remote_sync_task IS '远程挂载同步任务记录表';
COMMENT ON COLUMN t_remote_sync_task.id IS '任务 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN t_remote_sync_task.remote_mount_id IS '关联的远程挂载 ID';
COMMENT ON COLUMN t_remote_sync_task.type IS '触发方式：manual 手动 / scheduled 定时';
COMMENT ON COLUMN t_remote_sync_task.status IS '任务状态：PENDING / RUNNING / COMPLETED / FAILED / PARTIAL';
COMMENT ON COLUMN t_remote_sync_task.start_time IS '任务开始时间';
COMMENT ON COLUMN t_remote_sync_task.end_time IS '任务结束时间';
COMMENT ON COLUMN t_remote_sync_task.total_count IS '扫描到的远程节点总数';
COMMENT ON COLUMN t_remote_sync_task.success_count IS '成功同步节点数';
COMMENT ON COLUMN t_remote_sync_task.fail_count IS '失败/跳过节点数';
COMMENT ON COLUMN t_remote_sync_task.error_msg IS '错误信息';
COMMENT ON COLUMN t_remote_sync_task.create_time IS '创建时间';
COMMENT ON COLUMN t_remote_sync_task.update_time IS '更新时间';
COMMENT ON COLUMN t_remote_sync_task.delete_at IS '逻辑删除时间戳：0 表示未删除';

CREATE INDEX IF NOT EXISTS idx_remote_sync_task_mount_id ON t_remote_sync_task (remote_mount_id);
CREATE INDEX IF NOT EXISTS idx_remote_sync_task_status ON t_remote_sync_task (status);
CREATE INDEX IF NOT EXISTS idx_remote_sync_task_mount_create_time ON t_remote_sync_task (remote_mount_id, create_time);

-- ================== 远程挂载管理资源权限 ==================
INSERT INTO t_resource (id, code, name, type, status)
VALUES
    ('0000000000043', '/files/remote-mounts', '远程挂载管理页面', 'PAGE', 1),
    ('0000000000044', 'GET:/jcloud/api/remote-mounts', '分页查询远程挂载', 'API', 1),
    ('0000000000045', 'POST:/jcloud/api/remote-mounts', '创建远程挂载', 'API', 1),
    ('0000000000046', 'GET:/jcloud/api/remote-mounts/{id}', '查看远程挂载详情', 'API', 1),
    ('0000000000047', 'PUT:/jcloud/api/remote-mounts/{id}', '编辑远程挂载', 'API', 1),
    ('0000000000048', 'DELETE:/jcloud/api/remote-mounts/{id}', '删除远程挂载', 'API', 1),
    ('0000000000049', 'POST:/jcloud/api/remote-mounts/{id}/sync', '立即同步远程挂载', 'API', 1),
    ('000000000004a', 'GET:/jcloud/api/remote-mounts/{id}/sync/task', '查询远程挂载最新同步任务', 'API', 1),
    ('000000000004b', 'PUT:/jcloud/api/remote-mounts/{id}/sync-config', '更新远程挂载同步配置', 'API', 1),
    ('000000000004c', 'GET:/jcloud/api/remote-mounts/sync-tasks', '分页查询远程同步任务', 'API', 1),
    ('000000000004d', 'POST:/jcloud/api/remote-mounts/health-check', '检查远程挂载连接状态', 'API', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

-- 将远程挂载管理 API 关联到文件管理权限，普通用户即可管理自己的远程挂载
INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000004e', '0000000000007', '0000000000043'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000043');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000004f', '0000000000007', '0000000000044'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000044');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000004g', '0000000000007', '0000000000045'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000045');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000004h', '0000000000007', '0000000000046'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000046');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000004i', '0000000000007', '0000000000047'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000047');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000004j', '0000000000007', '0000000000048'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000048');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000004k', '0000000000007', '0000000000049'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000049');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000004l', '0000000000007', '000000000004a'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000004a');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000004m', '0000000000007', '000000000004b'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000004b');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000004n', '0000000000007', '000000000004c'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000004c');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000004o', '0000000000007', '000000000004d'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000004d');
