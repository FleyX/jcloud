-- ================== 跨来源传输任务表 ==================
CREATE TABLE IF NOT EXISTS t_transfer_task
(
    id               VARCHAR(13) PRIMARY KEY,
    user_id          VARCHAR(13)  NOT NULL,
    op_type          VARCHAR(16)  NOT NULL,
    source_type      VARCHAR(16)  NOT NULL,
    target_type      VARCHAR(16)  NOT NULL,
    source_mount_id  VARCHAR(13),
    target_mount_id  VARCHAR(13),
    target_parent_id VARCHAR(13)  NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    total_count      BIGINT       NOT NULL DEFAULT 0,
    success_count    BIGINT       NOT NULL DEFAULT 0,
    fail_count       BIGINT       NOT NULL DEFAULT 0,
    total_bytes      BIGINT       NOT NULL DEFAULT 0,
    items            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    fail_detail      VARCHAR(4000),
    error_msg        VARCHAR(4000),
    start_time       TIMESTAMP,
    end_time         TIMESTAMP,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at        BIGINT       NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_transfer_task IS '跨来源传输任务表';
COMMENT ON COLUMN t_transfer_task.id IS '任务 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN t_transfer_task.user_id IS '发起用户 ID';
COMMENT ON COLUMN t_transfer_task.op_type IS '操作类型：copy 复制 / move 移动';
COMMENT ON COLUMN t_transfer_task.source_type IS '来源类型：local / remote';
COMMENT ON COLUMN t_transfer_task.target_type IS '目标类型：local / remote';
COMMENT ON COLUMN t_transfer_task.source_mount_id IS '源为远程时的挂载 ID，源为本地为空';
COMMENT ON COLUMN t_transfer_task.target_mount_id IS '目标为远程时的挂载 ID，目标为本地为空';
COMMENT ON COLUMN t_transfer_task.target_parent_id IS '目标父节点 ID，根目录为虚拟根节点占位 ID';
COMMENT ON COLUMN t_transfer_task.status IS '任务状态：PENDING / RUNNING / CANCELLING / CANCELED / COMPLETED / FAILED / PARTIAL';
COMMENT ON COLUMN t_transfer_task.total_count IS '待传输文件节点总数（含文件夹展开后的文件）';
COMMENT ON COLUMN t_transfer_task.success_count IS '成功传输文件数';
COMMENT ON COLUMN t_transfer_task.fail_count IS '失败/跳过文件数';
COMMENT ON COLUMN t_transfer_task.total_bytes IS '待传输总字节数，用于配额预检与进度展示';
COMMENT ON COLUMN t_transfer_task.items IS '传输项快照 JSON：用户勾选的顶层节点（id、名称、类型、大小、冲突策略、最终名）';
COMMENT ON COLUMN t_transfer_task.fail_detail IS '失败明细 JSON（截断至 4000 字符）：每项含名称与失败原因';
COMMENT ON COLUMN t_transfer_task.error_msg IS '任务级错误信息';
COMMENT ON COLUMN t_transfer_task.start_time IS '任务开始时间';
COMMENT ON COLUMN t_transfer_task.end_time IS '任务结束时间';
COMMENT ON COLUMN t_transfer_task.create_time IS '创建时间';
COMMENT ON COLUMN t_transfer_task.update_time IS '更新时间';
COMMENT ON COLUMN t_transfer_task.delete_at IS '逻辑删除时间戳：0 表示未删除';

CREATE INDEX IF NOT EXISTS idx_transfer_task_user_id ON t_transfer_task (user_id);
CREATE INDEX IF NOT EXISTS idx_transfer_task_status ON t_transfer_task (status);
CREATE INDEX IF NOT EXISTS idx_transfer_task_user_create_time ON t_transfer_task (user_id, create_time);

-- ================== 跨来源传输资源权限 ==================
INSERT INTO t_resource (id, code, name, type, status)
VALUES
    ('00000000000a6', 'POST:/jcloud/api/transfers/move', '跨来源移动', 'API', 1),
    ('00000000000a7', 'POST:/jcloud/api/transfers/copy', '跨来源复制', 'API', 1),
    ('00000000000a8', 'GET:/jcloud/api/transfers/recent', '查询最近传输任务', 'API', 1),
    ('00000000000a9', 'GET:/jcloud/api/transfers/{id}', '查询传输任务详情', 'API', 1),
    ('00000000000aa', 'POST:/jcloud/api/transfers/{id}/cancel', '取消传输任务', 'API', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

-- 将跨来源传输 API 关联到文件管理权限，普通用户即可发起传输
INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '00000000000ab', '0000000000007', '00000000000a6'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '00000000000a6');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '00000000000ac', '0000000000007', '00000000000a7'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '00000000000a7');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '00000000000ad', '0000000000007', '00000000000a8'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '00000000000a8');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '00000000000ae', '0000000000007', '00000000000a9'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '00000000000a9');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '00000000000af', '0000000000007', '00000000000aa'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '00000000000aa');
