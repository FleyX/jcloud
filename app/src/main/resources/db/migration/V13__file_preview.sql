-- Issue #11：文件预览
-- 预览文件元数据表，记录生成在系统存储空间中的预览文件，支持缓存复用与后续清理。

CREATE TABLE IF NOT EXISTS t_preview_file
(
    id               BIGINT PRIMARY KEY,
    file_node_id     BIGINT       NOT NULL,
    type             VARCHAR(32)  NOT NULL,
    storage_space_id BIGINT       NOT NULL,
    relative_path    VARCHAR(512) NOT NULL,
    size             BIGINT       NOT NULL DEFAULT 0,
    status           SMALLINT     NOT NULL DEFAULT 1,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at        BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_preview_file_node_id ON t_preview_file (file_node_id);
CREATE INDEX IF NOT EXISTS idx_preview_file_type ON t_preview_file (type);
CREATE INDEX IF NOT EXISTS idx_preview_file_node_type ON t_preview_file (file_node_id, type);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1
                   FROM information_schema.table_constraints
                   WHERE table_schema = 'public'
                     AND table_name = 't_preview_file'
                     AND constraint_name = 'uk_preview_file_node_type_delete_at') THEN
        ALTER TABLE t_preview_file
            ADD CONSTRAINT uk_preview_file_node_type_delete_at UNIQUE (file_node_id, type, delete_at);
    END IF;
END $$;

-- 注册预览资源到 file:menu 权限
INSERT INTO t_resource (id, code, name, type, status, create_time, update_time, delete_at)
VALUES (95, 'GET:/jcloud/api/files/{id}/preview', '文件预览', 'API', 1, NOW(), NOW(), 0)
ON CONFLICT (code, delete_at) DO NOTHING;

INSERT INTO t_permission_resource (permission_id, resource_id)
SELECT p.id, r.id
FROM t_permission p
         CROSS JOIN t_resource r
WHERE p.code = 'file:menu'
  AND p.delete_at = 0
  AND r.code = 'GET:/jcloud/api/files/{id}/preview'
  AND r.delete_at = 0
ON CONFLICT (permission_id, resource_id) DO NOTHING;
