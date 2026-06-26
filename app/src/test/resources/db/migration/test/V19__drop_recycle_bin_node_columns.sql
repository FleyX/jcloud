-- Issue #9：回收站表精简，path_name 已能完整表达节点路径，不再依赖 node_id/original_parent_id
-- trash 目录改用 t_recycle_bin.id 命名

ALTER TABLE t_recycle_bin DROP COLUMN IF EXISTS node_id;
ALTER TABLE t_recycle_bin DROP COLUMN IF EXISTS original_parent_id;
