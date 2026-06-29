-- 删除存储空间类型字段及索引
-- 当前业务已通过 is_primary 与 system_config 区分主空间和系统数据目录，type 字段冗余
DROP INDEX IF EXISTS idx_storage_space_type;
ALTER TABLE t_storage_space DROP COLUMN IF EXISTS type;
