-- 合并上传预检接口：移除旧秒传预检资源，注册新的统一预检资源

-- 移除已废弃的秒传预检资源及其权限关联
DELETE FROM t_permission_resource
WHERE resource_id IN (SELECT id FROM t_resource WHERE code = 'POST:/jcloud/api/files/pre-check' AND delete_at = 0);

DELETE FROM t_resource WHERE code = 'POST:/jcloud/api/files/pre-check' AND delete_at = 0;

-- 注册统一的上传前预检资源
INSERT INTO t_resource (id, code, name, type, status, create_time, update_time, delete_at)
VALUES (70, 'POST:/jcloud/api/files/upload/pre-check', '上传前预检', 'API', 1, NOW(), NOW(), 0)
ON CONFLICT (code, delete_at) DO NOTHING;

-- 将新的预检资源关联到 file:menu 权限
INSERT INTO t_permission_resource (permission_id, resource_id)
SELECT p.id, r.id
FROM t_permission p
         CROSS JOIN t_resource r
WHERE p.code = 'file:menu'
  AND p.delete_at = 0
  AND r.code = 'POST:/jcloud/api/files/upload/pre-check'
  AND r.delete_at = 0
ON CONFLICT (permission_id, resource_id) DO NOTHING;
