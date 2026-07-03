-- 远程挂载默认不启用定时同步
ALTER TABLE t_remote_mount
    ALTER COLUMN enabled SET DEFAULT 0;
