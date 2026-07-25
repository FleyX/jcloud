-- 权限模型重构：权限与资源改由 permissions.yml 维护并加载到内存（详见 ADR 0016）
-- 1. 删除权限/资源/权限资源关联表
-- 2. t_role_permission 改为引用权限编码，存量数据清空重建（系统未上线）
-- 3. 重建内置 common_user 角色的权限绑定；super_admin 角色保持零绑定（超管由 is_admin 短路）

DROP TABLE IF EXISTS t_permission_resource;
DROP TABLE IF EXISTS t_permission;
DROP TABLE IF EXISTS t_resource;

DELETE FROM t_role_permission;
ALTER TABLE t_role_permission DROP COLUMN permission_id;
ALTER TABLE t_role_permission ADD COLUMN permission_code VARCHAR(64) NOT NULL;
COMMENT ON COLUMN t_role_permission.permission_code IS '权限编码，引用 permissions.yml 中的 code';

CREATE INDEX IF NOT EXISTS idx_role_permission_code ON t_role_permission (permission_code);
ALTER TABLE t_role_permission ADD CONSTRAINT uk_role_permission UNIQUE (role_id, permission_code);

-- 重建内置普通用户角色绑定
INSERT INTO t_role_permission (id, role_id, permission_code, create_time)
SELECT substr(replace(gen_random_uuid()::text, '-', ''), 1, 13),
       r.id,
       c.permission_code,
       now()
FROM t_role r
         JOIN (VALUES ('file:menu'),
                      ('file:remote_mount:menu'),
                      ('note:menu'),
                      ('todo:menu')) AS c (permission_code) ON r.code = 'common_user' AND r.delete_at = 0;
