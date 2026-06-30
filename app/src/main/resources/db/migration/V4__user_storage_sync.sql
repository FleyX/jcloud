-- 用户存储空间同步功能：物理目录到数据库增量对齐
-- 新增 t_file_node.last_modified 用于 diff 判断；新增 t_user_sync_config 与 t_user_sync_task 支持立即/定时同步。

-- ================== 文件节点表新增 last_modified ==================
ALTER TABLE t_file_node
    ADD COLUMN IF NOT EXISTS last_modified BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN t_file_node.last_modified IS '文件/目录最后修改时间（毫秒时间戳），用于同步 diff 判断';

CREATE INDEX IF NOT EXISTS idx_file_node_user_parent_name_modified
    ON t_file_node (user_id, parent_id, name, last_modified);

-- ================== 用户同步配置表 ==================
CREATE TABLE IF NOT EXISTS t_user_sync_config
(
    user_id        VARCHAR(13)  PRIMARY KEY,
    cron_expr      VARCHAR(128) NOT NULL,
    enabled        SMALLINT     NOT NULL DEFAULT 1,
    next_sync_time TIMESTAMP,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_user_sync_config IS '用户存储空间同步配置表，按用户记录定时同步的 cron 表达式与启用状态';
COMMENT ON COLUMN t_user_sync_config.user_id IS '用户 ID';
COMMENT ON COLUMN t_user_sync_config.cron_expr IS '定时同步 cron 表达式';
COMMENT ON COLUMN t_user_sync_config.enabled IS '是否启用定时同步：1 启用，0 禁用';
COMMENT ON COLUMN t_user_sync_config.next_sync_time IS '下次定时同步时间';
COMMENT ON COLUMN t_user_sync_config.create_time IS '创建时间';
COMMENT ON COLUMN t_user_sync_config.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_user_sync_config_enabled_next_time
    ON t_user_sync_config (enabled, next_sync_time);

-- ================== 用户同步任务表 ==================
CREATE TABLE IF NOT EXISTS t_user_sync_task
(
    id            VARCHAR(13)  PRIMARY KEY,
    user_id       VARCHAR(13)  NOT NULL,
    type          VARCHAR(32)  NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    start_time    TIMESTAMP,
    end_time      TIMESTAMP,
    total_count   BIGINT       NOT NULL DEFAULT 0,
    success_count BIGINT       NOT NULL DEFAULT 0,
    fail_count    BIGINT       NOT NULL DEFAULT 0,
    error_msg     VARCHAR(4000),
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at     BIGINT       NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_user_sync_task IS '用户存储空间同步任务表，记录每次同步任务的执行状态与统计';
COMMENT ON COLUMN t_user_sync_task.id IS '任务 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN t_user_sync_task.user_id IS '用户 ID';
COMMENT ON COLUMN t_user_sync_task.type IS '触发方式：manual 手动 / scheduled 定时';
COMMENT ON COLUMN t_user_sync_task.status IS '任务状态：PENDING / RUNNING / COMPLETED / FAILED / PARTIAL';
COMMENT ON COLUMN t_user_sync_task.start_time IS '任务开始时间';
COMMENT ON COLUMN t_user_sync_task.end_time IS '任务结束时间';
COMMENT ON COLUMN t_user_sync_task.total_count IS '扫描到的物理节点总数';
COMMENT ON COLUMN t_user_sync_task.success_count IS '成功同步节点数';
COMMENT ON COLUMN t_user_sync_task.fail_count IS '失败/跳过节点数';
COMMENT ON COLUMN t_user_sync_task.error_msg IS '错误信息';
COMMENT ON COLUMN t_user_sync_task.create_time IS '创建时间';
COMMENT ON COLUMN t_user_sync_task.update_time IS '更新时间';
COMMENT ON COLUMN t_user_sync_task.delete_at IS '逻辑删除时间戳：0 表示未删除';

CREATE INDEX IF NOT EXISTS idx_user_sync_task_user_id ON t_user_sync_task (user_id);
CREATE INDEX IF NOT EXISTS idx_user_sync_task_status ON t_user_sync_task (status);
CREATE INDEX IF NOT EXISTS idx_user_sync_task_user_create_time ON t_user_sync_task (user_id, create_time);

-- ================== 新增同步相关资源 ==================
INSERT INTO t_resource (id, code, name, type, status)
VALUES
    ('000000000001m', 'POST:/jcloud/api/admin/users/{id}/sync/immediate', '立即同步用户存储空间', 'API', 1),
    ('000000000001n', 'GET:/jcloud/api/admin/users/{id}/sync/task', '查询用户最新同步任务', 'API', 1),
    ('000000000001o', 'GET:/jcloud/api/admin/users/{id}/sync/config', '查询用户同步配置', 'API', 1),
    ('000000000001p', 'PUT:/jcloud/api/admin/users/{id}/sync/config', '更新用户同步配置', 'API', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

-- ================== 资源与用户管理权限关联 ==================
INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002x', '0000000000005', '000000000001m'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '000000000001m');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002y', '0000000000005', '000000000001n'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '000000000001n');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002z', '0000000000005', '000000000001o'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '000000000001o');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000030', '0000000000005', '000000000001p'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '000000000001p');
