-- 在 system:menu 下新增角色管理、权限管理菜单权限
INSERT INTO t_permission (id, code, name, parent_id, status)
VALUES (1003, 'role:menu', '角色管理',
        (SELECT id FROM t_permission WHERE code = 'system:menu' AND is_deleted = 0), 1)
ON CONFLICT (code) DO NOTHING;

INSERT INTO t_permission (id, code, name, parent_id, status)
VALUES (1004, 'permission:menu', '权限管理',
        (SELECT id FROM t_permission WHERE code = 'system:menu' AND is_deleted = 0), 1)
ON CONFLICT (code) DO NOTHING;

-- 新增页面资源
INSERT INTO t_resource (id, code, name, type, status)
VALUES
    (35, '/admin/roles', '角色管理页面', 'PAGE', 1),
    (36, '/admin/permissions', '权限管理页面', 'PAGE', 1)
ON CONFLICT (code) DO NOTHING;

-- 新增 API 资源
INSERT INTO t_resource (id, code, name, type, status)
VALUES
    (37, 'GET:/jcloud/api/roles/page', '角色分页查询', 'API', 1),
    (38, 'GET:/jcloud/api/roles/{id}', '角色详情', 'API', 1),
    (39, 'POST:/jcloud/api/roles', '创建角色', 'API', 1),
    (40, 'PUT:/jcloud/api/roles/{id}', '更新角色', 'API', 1),
    (41, 'DELETE:/jcloud/api/roles/{id}', '删除角色', 'API', 1),
    (42, 'PATCH:/jcloud/api/roles/{id}/status', '切换角色状态', 'API', 1),
    (43, 'GET:/jcloud/api/permissions/tree', '权限树', 'API', 1),
    (44, 'GET:/jcloud/api/permissions/{id}', '权限详情', 'API', 1),
    (45, 'POST:/jcloud/api/permissions', '创建权限', 'API', 1),
    (46, 'PUT:/jcloud/api/permissions/{id}', '更新权限', 'API', 1),
    (47, 'DELETE:/jcloud/api/permissions/{id}', '删除权限', 'API', 1),
    (48, 'PATCH:/jcloud/api/permissions/{id}/status', '切换权限状态', 'API', 1),
    (49, 'GET:/jcloud/api/permissions/resources', '资源列表', 'API', 1)
ON CONFLICT (code) DO NOTHING;

-- 关联 role:menu 权限到其资源
INSERT INTO t_permission_resource (permission_id, resource_id)
SELECT p.id, r.id
FROM t_permission p
         CROSS JOIN t_resource r
WHERE p.code = 'role:menu'
  AND p.is_deleted = 0
  AND r.code IN ('/admin/roles',
                 'GET:/jcloud/api/roles/page',
                 'GET:/jcloud/api/roles/{id}',
                 'POST:/jcloud/api/roles',
                 'PUT:/jcloud/api/roles/{id}',
                 'DELETE:/jcloud/api/roles/{id}',
                 'PATCH:/jcloud/api/roles/{id}/status')
ON CONFLICT (permission_id, resource_id) DO NOTHING;

-- 关联 permission:menu 权限到其资源
INSERT INTO t_permission_resource (permission_id, resource_id)
SELECT p.id, r.id
FROM t_permission p
         CROSS JOIN t_resource r
WHERE p.code = 'permission:menu'
  AND p.is_deleted = 0
  AND r.code IN ('/admin/permissions',
                 'GET:/jcloud/api/permissions/tree',
                 'GET:/jcloud/api/permissions/{id}',
                 'POST:/jcloud/api/permissions',
                 'PUT:/jcloud/api/permissions/{id}',
                 'DELETE:/jcloud/api/permissions/{id}',
                 'PATCH:/jcloud/api/permissions/{id}/status',
                 'GET:/jcloud/api/permissions/resources')
ON CONFLICT (permission_id, resource_id) DO NOTHING;

-- 为系统角色绑定新权限
INSERT INTO t_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM t_role r
         CROSS JOIN t_permission p
WHERE r.code IN ('super_admin', 'system_admin')
  AND r.is_deleted = 0
  AND p.code IN ('role:menu', 'permission:menu')
  AND p.is_deleted = 0
ON CONFLICT (role_id, permission_id) DO NOTHING;
