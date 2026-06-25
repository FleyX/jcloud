-- Issue #9：删除与回收站

-- 回收站记录表：记录用户主动删除的文件/文件夹节点
CREATE TABLE IF NOT EXISTS t_recycle_bin
(
    id                 BIGINT PRIMARY KEY,
    user_id            BIGINT       NOT NULL,
    node_id            BIGINT       NOT NULL,
    name               VARCHAR(255) NOT NULL,
    type               VARCHAR(32)  NOT NULL,
    original_parent_id BIGINT       NOT NULL DEFAULT 0,
    original_path_name VARCHAR(4000) NOT NULL DEFAULT '/',
    total_size         BIGINT       NOT NULL DEFAULT 0,
    status             SMALLINT     NOT NULL DEFAULT 1,
    create_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at          BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_recycle_bin_user_id ON t_recycle_bin (user_id);
CREATE INDEX IF NOT EXISTS idx_recycle_bin_user_deleted_at ON t_recycle_bin (user_id, delete_at);
CREATE INDEX IF NOT EXISTS idx_recycle_bin_deleted_at ON t_recycle_bin (delete_at);
