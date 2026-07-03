-- 完善权限配置：补充全部一级菜单、二级菜单及对应资源
-- 权限层级：
--   文件管理
--     └── 远程挂载
--   系统设置
--     ├── 用户管理
--     ├── 角色管理
--     ├── 权限管理
--     └── 存储空间管理
--   笔记管理（一级占位）
--   待办管理（一级占位）
-- 文件、分享、回收站相关资源统一关联到 file:menu

-- ================== 新增菜单权限 ==================
INSERT INTO t_permission (id, code, name, parent_id, status)
VALUES ('0000000000070', 'role:menu', '角色管理', '0000000000004', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

INSERT INTO t_permission (id, code, name, parent_id, status)
VALUES ('0000000000071', 'permission:menu', '权限管理', '0000000000004', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

INSERT INTO t_permission (id, code, name, parent_id, status)
VALUES ('0000000000074', 'file:remote_mount:menu', '远程挂载', '0000000000007', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

INSERT INTO t_permission (id, code, name, parent_id, status)
VALUES ('0000000000075', 'notes:menu', '笔记管理', NULL, 1)
ON CONFLICT (code, delete_at) DO NOTHING;

INSERT INTO t_permission (id, code, name, parent_id, status)
VALUES ('0000000000076', 'todos:menu', '待办管理', NULL, 1)
ON CONFLICT (code, delete_at) DO NOTHING;

-- ================== 新增资源 ==================
-- 角色管理页面与 API
INSERT INTO t_resource (id, code, name, type, status)
VALUES
    ('000000000004e', '/admin/roles', '角色管理页面', 'PAGE', 1),
    ('000000000004f', 'GET:/jcloud/api/roles', '查询所有启用角色', 'API', 1),
    ('0000000000050', 'GET:/jcloud/api/roles/page', '分页查询角色', 'API', 1),
    ('0000000000051', 'GET:/jcloud/api/roles/{id}', '查看角色详情', 'API', 1),
    ('0000000000052', 'POST:/jcloud/api/roles', '新增角色', 'API', 1),
    ('0000000000053', 'PUT:/jcloud/api/roles/{id}', '编辑角色', 'API', 1),
    ('0000000000054', 'DELETE:/jcloud/api/roles/{id}', '删除角色', 'API', 1),
    ('0000000000055', 'PATCH:/jcloud/api/roles/{id}/status', '修改角色状态', 'API', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

-- 权限管理页面与 API
INSERT INTO t_resource (id, code, name, type, status)
VALUES
    ('0000000000056', '/admin/permissions', '权限管理页面', 'PAGE', 1),
    ('0000000000057', 'GET:/jcloud/api/permissions/tree', '查询权限树', 'API', 1),
    ('0000000000058', 'GET:/jcloud/api/permissions/{id}', '查看权限详情', 'API', 1),
    ('0000000000059', 'POST:/jcloud/api/permissions', '新增权限', 'API', 1),
    ('000000000005a', 'PUT:/jcloud/api/permissions/{id}', '编辑权限', 'API', 1),
    ('000000000005b', 'DELETE:/jcloud/api/permissions/{id}', '删除权限', 'API', 1),
    ('000000000005c', 'PATCH:/jcloud/api/permissions/{id}/status', '修改权限状态', 'API', 1),
    ('000000000005d', 'GET:/jcloud/api/permissions/resources', '查询资源列表', 'API', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

-- 文件二级菜单页面
INSERT INTO t_resource (id, code, name, type, status)
VALUES
    ('000000000005e', '/files/share', '我的分享页面', 'PAGE', 1),
    ('000000000005f', '/files/trash', '回收站页面', 'PAGE', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

-- 补充各模块缺失的 API 资源
INSERT INTO t_resource (id, code, name, type, status)
VALUES
    ('0000000000060', 'POST:/jcloud/api/remote-mounts/test-connection', '测试远程挂载连接', 'API', 1),
    ('0000000000061', 'POST:/jcloud/api/admin/storage-spaces/{id}/refresh', '刷新存储空间磁盘状态', 'API', 1),
    ('0000000000062', 'GET:/jcloud/api/admin/users/{id}/sync/task', '查询用户最新同步任务', 'API', 1),
    ('0000000000063', 'POST:/jcloud/api/admin/users/{id}/sync/immediate', '立即同步用户存储空间', 'API', 1),
    ('0000000000064', 'GET:/jcloud/api/admin/users/{id}/sync/config', '查询用户同步配置', 'API', 1),
    ('0000000000065', 'PUT:/jcloud/api/admin/users/{id}/sync/config', '更新用户同步配置', 'API', 1),
    ('0000000000066', 'GET:/jcloud/api/users/me', '获取当前登录用户个人信息', 'LOGIN', 1),
    ('0000000000067', 'PUT:/jcloud/api/users/me', '更新当前登录用户个人信息', 'LOGIN', 1),
    ('0000000000068', 'PUT:/jcloud/api/users/me/password', '修改当前登录用户密码', 'LOGIN', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

-- ================== 角色管理权限关联资源 ==================
INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000077', '0000000000070', '000000000004e'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000070' AND resource_id = '000000000004e');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000078', '0000000000070', '000000000004f'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000070' AND resource_id = '000000000004f');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000079', '0000000000070', '0000000000050'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000070' AND resource_id = '0000000000050');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000007a', '0000000000070', '0000000000051'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000070' AND resource_id = '0000000000051');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000007b', '0000000000070', '0000000000052'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000070' AND resource_id = '0000000000052');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000007c', '0000000000070', '0000000000053'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000070' AND resource_id = '0000000000053');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000007d', '0000000000070', '0000000000054'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000070' AND resource_id = '0000000000054');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000007e', '0000000000070', '0000000000055'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000070' AND resource_id = '0000000000055');

-- ================== 权限管理权限关联资源 ==================
INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000007f', '0000000000071', '0000000000056'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000071' AND resource_id = '0000000000056');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000080', '0000000000071', '0000000000057'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000071' AND resource_id = '0000000000057');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000081', '0000000000071', '0000000000058'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000071' AND resource_id = '0000000000058');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000082', '0000000000071', '0000000000059'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000071' AND resource_id = '0000000000059');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000083', '0000000000071', '000000000005a'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000071' AND resource_id = '000000000005a');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000084', '0000000000071', '000000000005b'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000071' AND resource_id = '000000000005b');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000085', '0000000000071', '000000000005c'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000071' AND resource_id = '000000000005c');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000086', '0000000000071', '000000000005d'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000071' AND resource_id = '000000000005d');

-- ================== 远程挂载权限关联资源 ==================
INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000092', '0000000000074', '0000000000043'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000074' AND resource_id = '0000000000043');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000093', '0000000000074', '0000000000044'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000074' AND resource_id = '0000000000044');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000094', '0000000000074', '0000000000045'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000074' AND resource_id = '0000000000045');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000095', '0000000000074', '0000000000046'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000074' AND resource_id = '0000000000046');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000096', '0000000000074', '0000000000047'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000074' AND resource_id = '0000000000047');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000097', '0000000000074', '0000000000048'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000074' AND resource_id = '0000000000048');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000098', '0000000000074', '0000000000049'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000074' AND resource_id = '0000000000049');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000099', '0000000000074', '000000000004a'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000074' AND resource_id = '000000000004a');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000009a', '0000000000074', '000000000004b'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000074' AND resource_id = '000000000004b');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000009b', '0000000000074', '000000000004c'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000074' AND resource_id = '000000000004c');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000009c', '0000000000074', '000000000004d'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000074' AND resource_id = '000000000004d');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000009d', '0000000000074', '0000000000060'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000074' AND resource_id = '0000000000060');

-- ================== 文件权限归集分享/回收站页面及补充缺失 API ==================
-- 远程挂载测试连接 API 同时归集到文件权限
INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000009e', '0000000000007', '0000000000060'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000060');

-- 用户管理补充缺失 API
INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000009f', '0000000000005', '0000000000062'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '0000000000062');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '00000000000a0', '0000000000005', '0000000000063'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '0000000000063');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '00000000000a1', '0000000000005', '0000000000064'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '0000000000064');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '00000000000a2', '0000000000005', '0000000000065'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '0000000000065');

-- 存储空间管理补充缺失 API
INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '00000000000a3', '0000000000006', '0000000000061'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000006' AND resource_id = '0000000000061');

-- 分享/回收站页面归集到文件权限
INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '00000000000a4', '0000000000007', '000000000005e'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000005e');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '00000000000a5', '0000000000007', '000000000005f'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000005f');

-- ================== 为内置角色绑定新增权限 ==================
-- 超级管理员拥有所有新增权限
INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '0000000000036', '0000000000001', '0000000000070'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000001' AND permission_id = '0000000000070');

INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '0000000000037', '0000000000001', '0000000000071'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000001' AND permission_id = '0000000000071');

INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '0000000000038', '0000000000001', '0000000000074'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000001' AND permission_id = '0000000000074');

INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '0000000000039', '0000000000001', '0000000000075'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000001' AND permission_id = '0000000000075');

INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '000000000003a', '0000000000001', '0000000000076'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000001' AND permission_id = '0000000000076');

-- 普通用户拥有远程挂载及未来 notes/todos 菜单权限
INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '000000000003b', '0000000000003', '0000000000074'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000003' AND permission_id = '0000000000074');

INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '000000000003c', '0000000000003', '0000000000075'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000003' AND permission_id = '0000000000075');

INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '000000000003d', '0000000000003', '0000000000076'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000003' AND permission_id = '0000000000076');
