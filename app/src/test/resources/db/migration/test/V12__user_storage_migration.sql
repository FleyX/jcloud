-- Issue #10：管理员扩容存储空间与迁移用户

-- 用户只读状态：迁移期间禁止写操作
ALTER TABLE t_user
    ADD COLUMN IF NOT EXISTS read_only SMALLINT NOT NULL DEFAULT 0;

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
