-- 用户名改造：全局唯一、缩短长度以匹配文件系统目录要求
-- 应用层负责 [a-z0-9_] 6-32 位的格式校验；admin 作为内置管理员特例放行

ALTER TABLE t_user
    ALTER COLUMN username TYPE VARCHAR(32);

ALTER TABLE t_user
    DROP CONSTRAINT IF EXISTS uk_user_username_delete_at;

ALTER TABLE t_user
    ADD CONSTRAINT uk_user_username UNIQUE (username);

COMMENT ON COLUMN t_user.username IS '用户名，全局唯一，小写，6-32 位，仅含 a-z0-9_；内置管理员 admin 除外';
