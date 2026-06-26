-- Issue #6：分片上传、断点续传

-- ================== t_file_chunk ==================
CREATE TABLE IF NOT EXISTS t_file_chunk
(
    id            BIGINT PRIMARY KEY,
    upload_id     VARCHAR(64)  NOT NULL,
    user_id       BIGINT       NOT NULL,
    chunk_index   INT          NOT NULL,
    chunk_hash    VARCHAR(128) NOT NULL,
    size          BIGINT       NOT NULL,
    status        SMALLINT     NOT NULL DEFAULT 1,
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at     BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_file_chunk_upload_id ON t_file_chunk (upload_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_file_chunk_upload_index ON t_file_chunk (upload_id, chunk_index);

-- ================== 注册分片上传资源 ==================
INSERT INTO t_resource (id, code, name, type, status, create_time, update_time, delete_at)
VALUES (96, 'POST:/jcloud/api/files/chunked-upload/init', '初始化分片上传', 'API', 1, NOW(), NOW(), 0),
       (97, 'POST:/jcloud/api/files/chunked-upload/{uploadId}/chunks', '上传分片', 'API', 1, NOW(), NOW(), 0),
       (98, 'GET:/jcloud/api/files/chunked-upload/{uploadId}/chunks', '查询已上传分片', 'API', 1, NOW(), NOW(), 0),
       (99, 'POST:/jcloud/api/files/chunked-upload/{uploadId}/complete', '完成分片上传', 'API', 1, NOW(), NOW(), 0)
ON CONFLICT (code, delete_at) DO NOTHING;

-- 将分片上传资源关联到 file:menu 权限
INSERT INTO t_permission_resource (permission_id, resource_id)
SELECT p.id, r.id
FROM t_permission p
         CROSS JOIN t_resource r
WHERE p.code = 'file:menu'
  AND p.delete_at = 0
  AND r.code IN ('POST:/jcloud/api/files/chunked-upload/init',
                 'POST:/jcloud/api/files/chunked-upload/{uploadId}/chunks',
                 'GET:/jcloud/api/files/chunked-upload/{uploadId}/chunks',
                 'POST:/jcloud/api/files/chunked-upload/{uploadId}/complete')
  AND r.delete_at = 0
ON CONFLICT (permission_id, resource_id) DO NOTHING;
