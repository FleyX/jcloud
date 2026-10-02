-- 预览缓存已脱离存储空间，改为由 jcloud.system.cache-dir 指定的系统缓存目录统一存放，
-- t_preview_file.storage_space_id（原为 NOT NULL 占位列）不再有意义，直接删除。
-- 系统未上线，不做旧数据迁移。
ALTER TABLE t_preview_file DROP COLUMN storage_space_id;
