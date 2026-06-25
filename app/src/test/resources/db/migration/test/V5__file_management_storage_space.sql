-- 文件管理基础：存储空间表与用户存储绑定字段

-- ================== t_storage_space ==================
CREATE TABLE IF NOT EXISTS t_storage_space
(
    id            BIGINT PRIMARY KEY,
    name          VARCHAR(64)  NOT NULL,
    path          VARCHAR(512) NOT NULL,
    type          VARCHAR(32)  NOT NULL,
    capacity      BIGINT       NOT NULL,
    used_space    BIGINT       NOT NULL DEFAULT 0,
    status        SMALLINT     NOT NULL DEFAULT 1,
    remark        VARCHAR(255),
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at     BIGINT       NOT NULL DEFAULT 0
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1
                   FROM information_schema.table_constraints
                   WHERE table_schema = 'public'
                     AND table_name = 't_storage_space'
                     AND constraint_name = 'uk_storage_space_path_delete_at') THEN
        ALTER TABLE t_storage_space
            ADD CONSTRAINT uk_storage_space_path_delete_at UNIQUE (path, delete_at);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_storage_space_type ON t_storage_space (type);
CREATE INDEX IF NOT EXISTS idx_storage_space_status ON t_storage_space (status);

-- ================== t_user 扩展字段 ==================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1
                   FROM information_schema.columns
                   WHERE table_schema = 'public'
                     AND table_name = 't_user'
                     AND column_name = 'storage_space_id') THEN
        ALTER TABLE t_user ADD COLUMN storage_space_id BIGINT;
    END IF;

    IF NOT EXISTS (SELECT 1
                   FROM information_schema.columns
                   WHERE table_schema = 'public'
                     AND table_name = 't_user'
                     AND column_name = 'quota') THEN
        ALTER TABLE t_user ADD COLUMN quota BIGINT NOT NULL DEFAULT 0;
    END IF;

    IF NOT EXISTS (SELECT 1
                   FROM information_schema.columns
                   WHERE table_schema = 'public'
                     AND table_name = 't_user'
                     AND column_name = 'used_space') THEN
        ALTER TABLE t_user ADD COLUMN used_space BIGINT NOT NULL DEFAULT 0;
    END IF;

    IF NOT EXISTS (SELECT 1
                   FROM information_schema.columns
                   WHERE table_schema = 'public'
                     AND table_name = 't_user'
                     AND column_name = 'reserved_space') THEN
        ALTER TABLE t_user ADD COLUMN reserved_space BIGINT NOT NULL DEFAULT 0;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_user_storage_space_id ON t_user (storage_space_id);

-- ================== 注册存储空间管理权限与资源 ==================
INSERT INTO t_permission (id, code, name, parent_id, status)
VALUES (1003, 'storage_space:menu', '存储空间管理', 1001, 1)
ON CONFLICT DO NOTHING;

-- 注册资源（幂等：仅当 code + delete_at=0 不存在时插入）
INSERT INTO t_resource (id, code, name, type, status)
SELECT id, code, name, type, status
FROM (VALUES
          (60, '/admin/storage-spaces', '存储空间管理', 'PAGE', 1),
          (61, 'POST:/jcloud/api/admin/storage-spaces', '新增存储空间', 'API', 1),
          (62, 'GET:/jcloud/api/admin/storage-spaces', '分页查询存储空间', 'API', 1),
          (63, 'GET:/jcloud/api/admin/storage-spaces/{id}', '查看存储空间详情', 'API', 1),
          (64, 'PUT:/jcloud/api/admin/storage-spaces/{id}', '编辑存储空间', 'API', 1),
          (65, 'DELETE:/jcloud/api/admin/storage-spaces/{id}', '删除存储空间', 'API', 1),
          (66, 'PUT:/jcloud/api/users/{id}/storage', '设置用户默认存储空间', 'API', 1)
      ) AS v(id, code, name, type, status)
WHERE NOT EXISTS (SELECT 1 FROM t_resource r WHERE r.code = v.code AND r.delete_at = 0);

-- 关联资源与权限
INSERT INTO t_permission_resource (permission_id, resource_id)
SELECT p.id, r.id
FROM t_permission p
         CROSS JOIN t_resource r
WHERE p.code = 'storage_space:menu'
  AND r.code IN (
                 '/admin/storage-spaces',
                 'POST:/jcloud/api/admin/storage-spaces',
                 'GET:/jcloud/api/admin/storage-spaces',
                 'GET:/jcloud/api/admin/storage-spaces/{id}',
                 'PUT:/jcloud/api/admin/storage-spaces/{id}',
                 'DELETE:/jcloud/api/admin/storage-spaces/{id}',
                 'PUT:/jcloud/api/users/{id}/storage'
    )
ON CONFLICT (permission_id, resource_id) DO NOTHING;

-- 为系统管理员与超级管理员绑定存储空间管理权限
INSERT INTO t_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM t_role r
         CROSS JOIN t_permission p
WHERE r.code IN ('system_admin', 'super_admin')
  AND p.code = 'storage_space:menu'
ON CONFLICT (role_id, permission_id) DO NOTHING;
