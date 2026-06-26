-- Issue #2 改造：存储空间自动探测磁盘、主空间标记、系统初始化标志

-- ================== t_storage_space 扩展 ==================
ALTER TABLE t_storage_space
    ADD COLUMN IF NOT EXISTS is_primary SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS free_space BIGINT  NOT NULL DEFAULT 0;

COMMENT ON COLUMN t_storage_space.is_primary IS '是否主存储空间：1 是，0 否，全库仅允许一个主空间';
COMMENT ON COLUMN t_storage_space.free_space IS '剩余空间（字节），由程序根据磁盘实际空间自动刷新';
COMMENT ON COLUMN t_storage_space.capacity IS '总容量（字节），由程序根据磁盘实际空间自动刷新';
COMMENT ON COLUMN t_storage_space.used_space IS '已用空间（字节），由程序根据磁盘实际空间自动刷新';

CREATE INDEX IF NOT EXISTS idx_storage_space_is_primary ON t_storage_space (is_primary);

-- ================== 注册初始化相关资源 ==================
INSERT INTO t_resource (id, code, name, type, status, create_time, update_time, delete_at)
VALUES (104, 'GET:/jcloud/api/admin/system/init-status', '查询系统初始化状态', 'API', 1, NOW(), NOW(), 0),
       (105, 'POST:/jcloud/api/admin/system/initialize', '执行系统初始化', 'API', 1, NOW(), NOW(), 0)
ON CONFLICT (code, delete_at) DO NOTHING;

-- 将初始化资源关联到 storage_space:menu 权限
INSERT INTO t_permission_resource (permission_id, resource_id)
SELECT p.id, r.id
FROM t_permission p
         CROSS JOIN t_resource r
WHERE p.code = 'storage_space:menu'
  AND p.delete_at = 0
  AND r.code IN ('GET:/jcloud/api/admin/system/init-status',
                 'POST:/jcloud/api/admin/system/initialize')
  AND r.delete_at = 0
ON CONFLICT (permission_id, resource_id) DO NOTHING;
