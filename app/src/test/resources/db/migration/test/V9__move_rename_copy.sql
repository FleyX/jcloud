-- Issue #6：移动、重命名、复制与冲突解决

-- 加速同父目录下同名冲突检测
CREATE INDEX IF NOT EXISTS idx_file_node_user_parent_name
    ON t_file_node (user_id, parent_id, name, delete_at);

-- 注册文件组织操作资源
INSERT INTO t_resource (id, code, name, type, status, create_time, update_time, delete_at)
VALUES (80, 'POST:/jcloud/api/files/rename', '重命名文件或文件夹', 'API', 1, NOW(), NOW(), 0),
       (81, 'POST:/jcloud/api/files/folders', '创建文件夹', 'API', 1, NOW(), NOW(), 0),
       (82, 'POST:/jcloud/api/files/operations/pre-check', '移动复制预检', 'API', 1, NOW(), NOW(), 0),
       (83, 'POST:/jcloud/api/files/move', '移动文件或文件夹', 'API', 1, NOW(), NOW(), 0),
       (84, 'POST:/jcloud/api/files/copy', '复制文件或文件夹', 'API', 1, NOW(), NOW(), 0)
ON CONFLICT (code, delete_at) DO NOTHING;

-- 将新资源关联到 file:menu 权限
INSERT INTO t_permission_resource (permission_id, resource_id)
SELECT p.id, r.id
FROM t_permission p
         CROSS JOIN t_resource r
WHERE p.code = 'file:menu'
  AND p.delete_at = 0
  AND r.code IN ('POST:/jcloud/api/files/rename',
                 'POST:/jcloud/api/files/folders',
                 'POST:/jcloud/api/files/operations/pre-check',
                 'POST:/jcloud/api/files/move',
                 'POST:/jcloud/api/files/copy')
  AND r.delete_at = 0
ON CONFLICT (permission_id, resource_id) DO NOTHING;
