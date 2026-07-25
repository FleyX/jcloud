-- 远程挂载调整为登录即可访问（permissions.yml 中已移至 login 段），清理相关角色绑定
DELETE FROM t_role_permission WHERE permission_code = 'file:remote_mount:menu';
