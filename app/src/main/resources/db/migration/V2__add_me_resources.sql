-- 注册当前用户相关接口为 LOGIN 类型，登录用户均可访问
INSERT INTO t_resource (id, code, name, type, status)
VALUES
    (32, 'GET:/jcloud/api/users/me', '当前用户个人信息', 'LOGIN', 1),
    (33, 'PUT:/jcloud/api/users/me', '更新当前用户个人信息', 'LOGIN', 1),
    (34, 'PUT:/jcloud/api/users/me/password', '修改当前用户密码', 'LOGIN', 1)
ON CONFLICT (code) DO NOTHING;
