-- 分享功能：创建分享主表、分享项表，并注册相关资源权限

-- ================== 分享主表 ==================
CREATE TABLE IF NOT EXISTS t_share
(
    id             VARCHAR(13) PRIMARY KEY,
    user_id        VARCHAR(13)  NOT NULL,
    name           VARCHAR(128) NOT NULL,
    description    VARCHAR(512),
    share_code     VARCHAR(8)   NOT NULL,
    password_hash  VARCHAR(128),
    expire_at      TIMESTAMP,
    max_views      BIGINT,
    view_count     BIGINT       NOT NULL DEFAULT 0,
    status         SMALLINT     NOT NULL DEFAULT 1,
    delete_at      BIGINT       NOT NULL DEFAULT 0,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_share IS '分享主表';
COMMENT ON COLUMN t_share.id IS '分享 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN t_share.user_id IS '所有者用户 ID';
COMMENT ON COLUMN t_share.name IS '分享名称';
COMMENT ON COLUMN t_share.description IS '分享描述';
COMMENT ON COLUMN t_share.share_code IS '公开访问短码，8 位唯一';
COMMENT ON COLUMN t_share.password_hash IS '访问密码哈希，为空表示无密码';
COMMENT ON COLUMN t_share.expire_at IS '过期时间，为空表示永久有效';
COMMENT ON COLUMN t_share.max_views IS '最大访问次数，为空表示无限制';
COMMENT ON COLUMN t_share.view_count IS '已访问次数';
COMMENT ON COLUMN t_share.status IS '状态：1 启用，0 停用';
COMMENT ON COLUMN t_share.delete_at IS '逻辑删除时间戳：0 表示未删除';

CREATE INDEX IF NOT EXISTS idx_share_user_id ON t_share (user_id);
CREATE INDEX IF NOT EXISTS idx_share_create_time ON t_share (create_time);
CREATE UNIQUE INDEX IF NOT EXISTS uk_share_code_delete_at ON t_share (share_code, delete_at);

-- ================== 分享项表 ==================
CREATE TABLE IF NOT EXISTS t_share_item
(
    id           VARCHAR(13) PRIMARY KEY,
    share_id     VARCHAR(13) NOT NULL,
    file_node_id VARCHAR(13) NOT NULL,
    create_time  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_share_item IS '分享项表，记录分享包含的文件节点';
COMMENT ON COLUMN t_share_item.id IS '分享项 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN t_share_item.share_id IS '所属分享 ID';
COMMENT ON COLUMN t_share_item.file_node_id IS '文件节点 ID';

CREATE INDEX IF NOT EXISTS idx_share_item_share_id ON t_share_item (share_id);
CREATE INDEX IF NOT EXISTS idx_share_item_file_node_id ON t_share_item (file_node_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_share_item ON t_share_item (share_id, file_node_id);

-- ================== 分享管理 API 资源 ==================
INSERT INTO t_resource (id, code, name, type, status)
VALUES
    ('0000000000031', 'GET:/jcloud/api/shares', '分页查询我的分享', 'API', 1),
    ('0000000000032', 'POST:/jcloud/api/shares', '创建分享', 'API', 1),
    ('0000000000033', 'GET:/jcloud/api/shares/{id}', '查看分享详情', 'API', 1),
    ('0000000000034', 'PUT:/jcloud/api/shares/{id}', '更新分享', 'API', 1),
    ('0000000000035', 'DELETE:/jcloud/api/shares/{id}', '删除分享', 'API', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

-- 将分享管理 API 关联到文件管理权限，普通用户即可管理自己的分享
INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000036', '0000000000007', '0000000000031'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000031');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000037', '0000000000007', '0000000000032'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000032');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000038', '0000000000007', '0000000000033'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000033');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000039', '0000000000007', '0000000000034'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000034');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000003a', '0000000000007', '0000000000035'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000035');

-- ================== 公开分享资源（无需登录） ==================
INSERT INTO t_resource (id, code, name, type, status)
VALUES
    ('000000000003b', 'GET:/jcloud/api/s/{code}', '获取公开分享信息', 'PUBLIC', 1),
    ('000000000003c', 'POST:/jcloud/api/s/{code}/access', '校验分享访问密码', 'PUBLIC', 1),
    ('000000000003d', 'GET:/jcloud/api/s/{code}/items', '获取公开分享项列表', 'PUBLIC', 1),
    ('000000000003e', 'GET:/jcloud/api/s/{code}/files/{fileId}/download', '公开下载单个文件', 'PUBLIC', 1),
    ('000000000003f', 'GET:/jcloud/api/s/{code}/files/{fileId}/preview', '公开预览文件', 'PUBLIC', 1),
    ('0000000000040', 'POST:/jcloud/api/s/{code}/batch-download', '公开批量下载', 'PUBLIC', 1),
    ('0000000000041', 'GET:/jcloud/api/s/{code}/batch-download/{taskId}/status', '查询公开批量下载任务', 'PUBLIC', 1),
    ('0000000000042', 'GET:/jcloud/api/s/{code}/batch-download/{taskId}', '下载公开批量下载结果', 'PUBLIC', 1)
ON CONFLICT (code, delete_at) DO NOTHING;
