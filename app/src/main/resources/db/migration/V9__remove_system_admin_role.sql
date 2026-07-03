-- 删除 system_admin 角色及其关联数据
-- 超级管理员(super_admin)保留为唯一受保护的内置角色

-- 先清理用户角色关联
DELETE FROM t_user_role
WHERE role_id IN (SELECT id FROM t_role WHERE code = 'system_admin');

-- 清理角色权限关联
DELETE FROM t_role_permission
WHERE role_id IN (SELECT id FROM t_role WHERE code = 'system_admin');

-- 逻辑删除 system_admin 角色
UPDATE t_role
SET delete_at = (EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000)::bigint
WHERE code = 'system_admin'
  AND delete_at = 0;
