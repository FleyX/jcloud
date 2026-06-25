-- Issue #4：最小化文件节点表，仅支持根目录文件

CREATE TABLE IF NOT EXISTS t_file_node
(
    id                BIGINT PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    parent_id         BIGINT       NOT NULL DEFAULT 0,
    name              VARCHAR(255) NOT NULL,
    type              VARCHAR(32)  NOT NULL,
    size              BIGINT       NOT NULL DEFAULT 0,
    hash              VARCHAR(128),
    storage_space_id  BIGINT       NOT NULL,
    path              VARCHAR(4000) NOT NULL DEFAULT '/',
    path_name         VARCHAR(4000) NOT NULL DEFAULT '/',
    mime_type         VARCHAR(128),
    status            SMALLINT     NOT NULL DEFAULT 1,
    create_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at         BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_file_node_user_id ON t_file_node (user_id);
CREATE INDEX IF NOT EXISTS idx_file_node_parent_id ON t_file_node (parent_id);
CREATE INDEX IF NOT EXISTS idx_file_node_type ON t_file_node (type);
CREATE INDEX IF NOT EXISTS idx_file_node_user_parent ON t_file_node (user_id, parent_id);
