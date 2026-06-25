-- Issue #10：管理员扩容存储空间与迁移用户

-- 用户只读状态：迁移期间禁止写操作
ALTER TABLE t_user
    ADD COLUMN IF NOT EXISTS read_only SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN t_user.read_only IS '是否只读：0 否，1 是（迁移期间）';

-- 用户存储空间迁移任务表
CREATE TABLE IF NOT EXISTS t_user_migration_task
(
    id               BIGINT PRIMARY KEY,
    user_id          BIGINT       NOT NULL,
    source_space_id  BIGINT       NOT NULL,
    target_space_id  BIGINT       NOT NULL,
    new_quota        BIGINT       NOT NULL,
    status           VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    total_bytes      BIGINT       NOT NULL DEFAULT 0,
    migrated_bytes   BIGINT       NOT NULL DEFAULT 0,
    error_msg        VARCHAR(4000),
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at        BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_user_migration_task_user_id ON t_user_migration_task (user_id);
CREATE INDEX IF NOT EXISTS idx_user_migration_task_status ON t_user_migration_task (status);
CREATE INDEX IF NOT EXISTS idx_user_migration_task_user_deleted_at ON t_user_migration_task (user_id, delete_at);

COMMENT ON TABLE t_user_migration_task IS '用户存储空间迁移任务';
COMMENT ON COLUMN t_user_migration_task.status IS '任务状态：PENDING / RUNNING / COMPLETED / FAILED';

-- 注册迁移相关资源
INSERT INTO t_resource (id, code, name, type, status, create_time, update_time, delete_at)
VALUES (100, 'POST:/jcloud/api/admin/users/{id}/migrate', '发起用户存储空间迁移', 'API', 1, NOW(), NOW(), 0),
       (101, 'GET:/jcloud/api/admin/users/{id}/migration-task', '查询用户最新迁移任务', 'API', 1, NOW(), NOW(), 0)
ON CONFLICT (code, delete_at) DO NOTHING;

-- 将迁移资源关联到 system:menu 权限
INSERT INTO t_permission_resource (permission_id, resource_id)
SELECT p.id, r.id
FROM t_permission p
         CROSS JOIN t_resource r
WHERE p.code = 'system:menu'
  AND p.delete_at = 0
  AND r.code IN ('POST:/jcloud/api/admin/users/{id}/migrate',
                 'GET:/jcloud/api/admin/users/{id}/migration-task')
  AND r.delete_at = 0
ON CONFLICT (permission_id, resource_id) DO NOTHING;
