-- Issue #2：系统数据目录配置
-- 新增系统配置表，用于存储管理员指定的系统数据目录所在用户存储空间。

-- ================== t_system_config ==================
CREATE TABLE IF NOT EXISTS t_system_config
(
    id          BIGINT PRIMARY KEY,
    config_key  VARCHAR(128) NOT NULL,
    config_value VARCHAR(512),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at   BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_system_config_key ON t_system_config (config_key);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1
                   FROM information_schema.table_constraints
                   WHERE table_schema = 'public'
                     AND table_name = 't_system_config'
                     AND constraint_name = 'uk_system_config_key_delete_at') THEN
        ALTER TABLE t_system_config
            ADD CONSTRAINT uk_system_config_key_delete_at UNIQUE (config_key, delete_at);
    END IF;
END $$;

-- ================== 注册系统数据目录配置资源 ==================
INSERT INTO t_resource (id, code, name, type, status, create_time, update_time, delete_at)
VALUES (102, 'GET:/jcloud/api/admin/storage-spaces/system-config', '查询系统数据目录配置', 'API', 1, NOW(), NOW(), 0),
       (103, 'PUT:/jcloud/api/admin/storage-spaces/system-config', '更新系统数据目录配置', 'API', 1, NOW(), NOW(), 0)
ON CONFLICT (code, delete_at) DO NOTHING;

-- 关联资源到 storage_space:menu 权限
INSERT INTO t_permission_resource (permission_id, resource_id)
SELECT p.id, r.id
FROM t_permission p
         CROSS JOIN t_resource r
WHERE p.code = 'storage_space:menu'
  AND p.delete_at = 0
  AND r.code IN ('GET:/jcloud/api/admin/storage-spaces/system-config',
                 'PUT:/jcloud/api/admin/storage-spaces/system-config')
  AND r.delete_at = 0
ON CONFLICT (permission_id, resource_id) DO NOTHING;
