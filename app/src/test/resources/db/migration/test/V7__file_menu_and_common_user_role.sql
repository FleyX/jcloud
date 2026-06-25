-- 文件管理菜单权限与普通用户角色

-- ================== 普通用户角色 ==================
INSERT INTO t_role (id, code, name, description, status)
VALUES (3, 'common_user', '普通用户', '拥有个人文件管理权限', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

-- ================== 文件管理权限 ==================
INSERT INTO t_permission (id, code, name, parent_id, status)
VALUES (1005, 'file:menu', '文件管理', NULL, 1)
ON CONFLICT (code, delete_at) DO NOTHING;

-- ================== 注册文件管理资源 ==================
INSERT INTO t_resource (id, code, name, type, status)
SELECT id, code, name, type, status
FROM (VALUES
          (50, '/files', '文件管理页面', 'PAGE', 1),
          (51, 'POST:/jcloud/api/files/upload', '上传文件', 'API', 1),
          (52, 'GET:/jcloud/api/files', '分页查询文件列表', 'API', 1),
          (53, 'GET:/jcloud/api/files/{id}/download', '下载文件', 'API', 1)
      ) AS v(id, code, name, type, status)
WHERE NOT EXISTS (SELECT 1 FROM t_resource r WHERE r.code = v.code AND r.delete_at = 0)
ON CONFLICT (code, delete_at) DO NOTHING;

-- ================== 关联文件管理权限到资源 ==================
INSERT INTO t_permission_resource (permission_id, resource_id)
SELECT p.id, r.id
FROM t_permission p
         CROSS JOIN t_resource r
WHERE p.code = 'file:menu'
  AND p.delete_at = 0
  AND r.code IN (
                 '/files',
                 'POST:/jcloud/api/files/upload',
                 'GET:/jcloud/api/files',
                 'GET:/jcloud/api/files/{id}/download'
    )
ON CONFLICT (permission_id, resource_id) DO NOTHING;

-- ================== 为所有角色绑定文件管理权限 ==================
INSERT INTO t_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM t_role r
         CROSS JOIN t_permission p
WHERE r.code IN ('common_user', 'system_admin', 'super_admin')
  AND r.delete_at = 0
  AND p.code = 'file:menu'
  AND p.delete_at = 0
ON CONFLICT (role_id, permission_id) DO NOTHING;
