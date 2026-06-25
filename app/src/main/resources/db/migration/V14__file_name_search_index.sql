-- Issue #12：为文件名搜索创建 trigram/GIN 索引

-- 启用 PostgreSQL trigram 扩展（幂等）
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 为 t_file_node.name 创建 GIN 索引以支持高效的模糊搜索
CREATE INDEX IF NOT EXISTS idx_file_node_name_trgm
    ON t_file_node USING gin (name gin_trgm_ops);
