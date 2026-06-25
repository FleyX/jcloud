-- 秒传去重：加速同用户 hash 查找
CREATE INDEX IF NOT EXISTS idx_file_node_user_hash ON t_file_node (user_id, hash);

-- 注册秒传相关资源
INSERT INTO t_resource (id, code, name, type, status, create_time, update_time, delete_at)
VALUES (70, 'POST:/jcloud/api/files/pre-check', '秒传预检查', 'API', 1, NOW(), NOW(), 0),
       (71, 'POST:/jcloud/api/files/instant', '秒传', 'API', 1, NOW(), NOW(), 0)
ON CONFLICT (code, delete_at) DO NOTHING;

-- 将秒传资源关联到 file:menu 权限
INSERT INTO t_permission_resource (permission_id, resource_id)
SELECT p.id, r.id
FROM t_permission p
         CROSS JOIN t_resource r
WHERE p.code = 'file:menu'
  AND p.delete_at = 0
  AND r.code IN ('POST:/jcloud/api/files/pre-check', 'POST:/jcloud/api/files/instant')
  AND r.delete_at = 0
ON CONFLICT (permission_id, resource_id) DO NOTHING;
