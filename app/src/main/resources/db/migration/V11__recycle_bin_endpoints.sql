-- Issue #9：回收站相关接口资源注册

-- 注册回收站操作资源
INSERT INTO t_resource (id, code, name, type, status, create_time, update_time, delete_at)
VALUES (90, 'POST:/jcloud/api/files/delete', '删除文件或文件夹到回收站', 'API', 1, NOW(), NOW(), 0),
       (91, 'GET:/jcloud/api/files/trash', '分页查询回收站列表', 'API', 1, NOW(), NOW(), 0),
       (92, 'POST:/jcloud/api/files/trash/restore/pre-check', '恢复前冲突预检', 'API', 1, NOW(), NOW(), 0),
       (93, 'POST:/jcloud/api/files/trash/restore', '恢复文件或文件夹', 'API', 1, NOW(), NOW(), 0),
       (94, 'POST:/jcloud/api/files/trash/permanent-delete', '永久删除回收站记录', 'API', 1, NOW(), NOW(), 0)
ON CONFLICT (code, delete_at) DO NOTHING;

-- 将回收站资源关联到 file:menu 权限
INSERT INTO t_permission_resource (permission_id, resource_id)
SELECT p.id, r.id
FROM t_permission p
         CROSS JOIN t_resource r
WHERE p.code = 'file:menu'
  AND p.delete_at = 0
  AND r.code IN ('POST:/jcloud/api/files/delete',
                 'GET:/jcloud/api/files/trash',
                 'POST:/jcloud/api/files/trash/restore/pre-check',
                 'POST:/jcloud/api/files/trash/restore',
                 'POST:/jcloud/api/files/trash/permanent-delete')
  AND r.delete_at = 0
ON CONFLICT (permission_id, resource_id) DO NOTHING;
