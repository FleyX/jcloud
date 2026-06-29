-- 初始化脚本（合并历史 V1~V19）
-- 适配 ADR-0009：所有 ID 为 13 位定长 base36 字符串；t_file_node.path 为 id 路径；t_file_node 与 t_recycle_bin 物理删除。

-- ================== 用户表 ==================
CREATE TABLE IF NOT EXISTS t_user
(
    id               VARCHAR(13) PRIMARY KEY,
    username         VARCHAR(64)  NOT NULL,
    password         VARCHAR(128) NOT NULL,
    email            VARCHAR(128),
    nickname         VARCHAR(64),
    status           SMALLINT     NOT NULL DEFAULT 1,
    is_admin         SMALLINT     NOT NULL DEFAULT 0,
    storage_space_id VARCHAR(13),
    quota            BIGINT       NOT NULL DEFAULT 0,
    used_space       BIGINT       NOT NULL DEFAULT 0,
    reserved_space   BIGINT       NOT NULL DEFAULT 0,
    read_only        SMALLINT     NOT NULL DEFAULT 0,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_user_username_delete_at UNIQUE (username, delete_at)
);

COMMENT ON TABLE t_user IS '用户表';
COMMENT ON COLUMN t_user.id IS '用户 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN t_user.username IS '用户名';
COMMENT ON COLUMN t_user.password IS '加密后的密码';
COMMENT ON COLUMN t_user.email IS '邮箱';
COMMENT ON COLUMN t_user.nickname IS '昵称';
COMMENT ON COLUMN t_user.status IS '状态：1 启用，0 禁用';
COMMENT ON COLUMN t_user.is_admin IS '是否为超级管理员：1 是，0 否';
COMMENT ON COLUMN t_user.storage_space_id IS '默认存储空间 ID';
COMMENT ON COLUMN t_user.quota IS '用户配额（字节）';
COMMENT ON COLUMN t_user.used_space IS '已用空间（字节）';
COMMENT ON COLUMN t_user.reserved_space IS '预占空间（字节）';
COMMENT ON COLUMN t_user.read_only IS '是否只读：0 否，1 是（迁移期间）';
COMMENT ON COLUMN t_user.delete_at IS '逻辑删除时间戳：0 表示未删除';

CREATE INDEX IF NOT EXISTS idx_user_storage_space_id ON t_user (storage_space_id);

-- ================== 角色表 ==================
CREATE TABLE IF NOT EXISTS t_role
(
    id          VARCHAR(13) PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255),
    status      SMALLINT     NOT NULL DEFAULT 1,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at   BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_role_code_delete_at UNIQUE (code, delete_at)
);

COMMENT ON TABLE t_role IS '角色表';
COMMENT ON COLUMN t_role.id IS '角色 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN t_role.code IS '角色编码';
COMMENT ON COLUMN t_role.name IS '角色名称';
COMMENT ON COLUMN t_role.description IS '角色描述';
COMMENT ON COLUMN t_role.status IS '状态：1 启用，0 禁用';
COMMENT ON COLUMN t_role.delete_at IS '逻辑删除时间戳：0 表示未删除';

-- ================== 权限表 ==================
CREATE TABLE IF NOT EXISTS t_permission
(
    id          VARCHAR(13) PRIMARY KEY,
    code        VARCHAR(128) NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    parent_id   VARCHAR(13),
    status      SMALLINT     NOT NULL DEFAULT 1,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at   BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_permission_code_delete_at UNIQUE (code, delete_at)
);

COMMENT ON TABLE t_permission IS '权限表，仅保留树形层级信息';
COMMENT ON COLUMN t_permission.id IS '权限 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN t_permission.code IS '权限编码';
COMMENT ON COLUMN t_permission.name IS '权限名称';
COMMENT ON COLUMN t_permission.parent_id IS '父权限 ID';
COMMENT ON COLUMN t_permission.status IS '状态：1 启用，0 禁用';
COMMENT ON COLUMN t_permission.delete_at IS '逻辑删除时间戳：0 表示未删除';

CREATE INDEX IF NOT EXISTS idx_permission_parent_id ON t_permission (parent_id);
CREATE INDEX IF NOT EXISTS idx_permission_code ON t_permission (code);

-- ================== 用户角色关联表 ==================
CREATE TABLE IF NOT EXISTS t_user_role
(
    id          VARCHAR(13) PRIMARY KEY,
    user_id     VARCHAR(13) NOT NULL,
    role_id     VARCHAR(13) NOT NULL,
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_role UNIQUE (user_id, role_id)
);

COMMENT ON TABLE t_user_role IS '用户角色关联表';
COMMENT ON COLUMN t_user_role.user_id IS '用户 ID';
COMMENT ON COLUMN t_user_role.role_id IS '角色 ID';

CREATE INDEX IF NOT EXISTS idx_user_role_user_id ON t_user_role (user_id);
CREATE INDEX IF NOT EXISTS idx_user_role_role_id ON t_user_role (role_id);

-- ================== 角色权限关联表 ==================
CREATE TABLE IF NOT EXISTS t_role_permission
(
    id            VARCHAR(13) PRIMARY KEY,
    role_id       VARCHAR(13) NOT NULL,
    permission_id VARCHAR(13) NOT NULL,
    create_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_role_permission UNIQUE (role_id, permission_id)
);

COMMENT ON TABLE t_role_permission IS '角色权限关联表';
COMMENT ON COLUMN t_role_permission.role_id IS '角色 ID';
COMMENT ON COLUMN t_role_permission.permission_id IS '权限 ID';

CREATE INDEX IF NOT EXISTS idx_role_permission_role_id ON t_role_permission (role_id);
CREATE INDEX IF NOT EXISTS idx_role_permission_permission_id ON t_role_permission (permission_id);

-- ================== 资源表 ==================
CREATE TABLE IF NOT EXISTS t_resource
(
    id          VARCHAR(13) PRIMARY KEY,
    code        VARCHAR(255) NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    status      SMALLINT     NOT NULL DEFAULT 1,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at   BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_resource_code_delete_at UNIQUE (code, delete_at)
);

COMMENT ON TABLE t_resource IS '资源表，记录 API、页面、PUBLIC/LOGIN 资源';
COMMENT ON COLUMN t_resource.id IS '资源 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN t_resource.code IS '资源编码（URL 或路由）';
COMMENT ON COLUMN t_resource.name IS '资源名称';
COMMENT ON COLUMN t_resource.type IS '资源类型：API / PUBLIC / PAGE / LOGIN';
COMMENT ON COLUMN t_resource.status IS '状态：1 启用，0 禁用';
COMMENT ON COLUMN t_resource.delete_at IS '逻辑删除时间戳：0 表示未删除';

CREATE INDEX IF NOT EXISTS idx_resource_code ON t_resource (code);
CREATE INDEX IF NOT EXISTS idx_resource_type ON t_resource (type);

-- ================== 权限资源关联表 ==================
CREATE TABLE IF NOT EXISTS t_permission_resource
(
    id            VARCHAR(13) PRIMARY KEY,
    permission_id VARCHAR(13) NOT NULL,
    resource_id   VARCHAR(13) NOT NULL,
    create_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_permission_resource UNIQUE (permission_id, resource_id)
);

COMMENT ON TABLE t_permission_resource IS '权限资源关联表';
COMMENT ON COLUMN t_permission_resource.permission_id IS '权限 ID';
COMMENT ON COLUMN t_permission_resource.resource_id IS '资源 ID';

CREATE INDEX IF NOT EXISTS idx_permission_resource_permission_id ON t_permission_resource (permission_id);
CREATE INDEX IF NOT EXISTS idx_permission_resource_resource_id ON t_permission_resource (resource_id);

-- ================== 存储空间表 ==================
CREATE TABLE IF NOT EXISTS t_storage_space
(
    id          VARCHAR(13) PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    path        VARCHAR(512) NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    capacity    BIGINT       NOT NULL,
    used_space  BIGINT       NOT NULL DEFAULT 0,
    free_space  BIGINT       NOT NULL DEFAULT 0,
    is_primary  SMALLINT     NOT NULL DEFAULT 0,
    status      SMALLINT     NOT NULL DEFAULT 1,
    remark      VARCHAR(255),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at   BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_storage_space_path_delete_at UNIQUE (path, delete_at)
);

COMMENT ON TABLE t_storage_space IS '存储空间表';
COMMENT ON COLUMN t_storage_space.id IS '存储空间 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN t_storage_space.name IS '存储空间名称';
COMMENT ON COLUMN t_storage_space.path IS '物理路径';
COMMENT ON COLUMN t_storage_space.type IS '类型：USER / SYSTEM';
COMMENT ON COLUMN t_storage_space.capacity IS '容量（字节）';
COMMENT ON COLUMN t_storage_space.used_space IS '已用空间（字节）';
COMMENT ON COLUMN t_storage_space.free_space IS '剩余空间（字节）';
COMMENT ON COLUMN t_storage_space.is_primary IS '是否主存储空间：1 是，0 否';
COMMENT ON COLUMN t_storage_space.status IS '状态：1 启用，0 禁用';
COMMENT ON COLUMN t_storage_space.remark IS '备注';
COMMENT ON COLUMN t_storage_space.delete_at IS '逻辑删除时间戳：0 表示未删除';

CREATE INDEX IF NOT EXISTS idx_storage_space_type ON t_storage_space (type);
CREATE INDEX IF NOT EXISTS idx_storage_space_status ON t_storage_space (status);
CREATE INDEX IF NOT EXISTS idx_storage_space_is_primary ON t_storage_space (is_primary);

-- ================== 文件节点表 ==================
CREATE TABLE IF NOT EXISTS t_file_node
(
    id               VARCHAR(13) PRIMARY KEY,
    user_id          VARCHAR(13)  NOT NULL,
    parent_id        VARCHAR(13)  NOT NULL DEFAULT '0000000000000',
    name             VARCHAR(255) NOT NULL,
    type             VARCHAR(32)  NOT NULL,
    size             BIGINT       NOT NULL DEFAULT 0,
    hash             VARCHAR(128),
    storage_space_id VARCHAR(13)  NOT NULL,
    path             VARCHAR(300) NOT NULL DEFAULT '0000000000000',
    mime_type        VARCHAR(128),
    status           SMALLINT     NOT NULL DEFAULT 1,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_file_node IS '文件节点表，记录文件与文件夹';
COMMENT ON COLUMN t_file_node.id IS '节点 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN t_file_node.user_id IS '用户 ID';
COMMENT ON COLUMN t_file_node.parent_id IS '父节点 ID，根目录为虚拟根节点占位 ID';
COMMENT ON COLUMN t_file_node.name IS '节点名称';
COMMENT ON COLUMN t_file_node.type IS '类型：file / folder';
COMMENT ON COLUMN t_file_node.size IS '文件大小（字节），文件夹为 0';
COMMENT ON COLUMN t_file_node.hash IS '文件身份 hash';
COMMENT ON COLUMN t_file_node.storage_space_id IS '存储空间 ID';
COMMENT ON COLUMN t_file_node.path IS '从虚拟根到父节点的 id 路径，使用 "." 分割';
COMMENT ON COLUMN t_file_node.mime_type IS 'MIME 类型';
COMMENT ON COLUMN t_file_node.status IS '状态：1 启用，0 禁用';

CREATE INDEX IF NOT EXISTS idx_file_node_user_id ON t_file_node (user_id);
CREATE INDEX IF NOT EXISTS idx_file_node_parent_id ON t_file_node (parent_id);
CREATE INDEX IF NOT EXISTS idx_file_node_type ON t_file_node (type);
CREATE INDEX IF NOT EXISTS idx_file_node_user_parent ON t_file_node (user_id, parent_id);
CREATE INDEX IF NOT EXISTS idx_file_node_user_parent_name ON t_file_node (user_id, parent_id, name);
CREATE INDEX IF NOT EXISTS idx_file_node_user_hash ON t_file_node (user_id, hash);
CREATE INDEX IF NOT EXISTS idx_file_node_user_path ON t_file_node (user_id, path);

-- ================== 回收站记录表 ==================
CREATE TABLE IF NOT EXISTS t_recycle_bin
(
    id                 VARCHAR(13) PRIMARY KEY,
    user_id            VARCHAR(13)  NOT NULL,
    name               VARCHAR(255) NOT NULL,
    type               VARCHAR(32)  NOT NULL,
    original_path_name VARCHAR(300) NOT NULL DEFAULT '/',
    total_size         BIGINT       NOT NULL DEFAULT 0,
    status             SMALLINT     NOT NULL DEFAULT 1,
    create_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE t_recycle_bin IS '回收站记录表，记录用户主动删除的节点';
COMMENT ON COLUMN t_recycle_bin.id IS '记录 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN t_recycle_bin.user_id IS '用户 ID';
COMMENT ON COLUMN t_recycle_bin.name IS '节点名称';
COMMENT ON COLUMN t_recycle_bin.type IS '类型：file / folder';
COMMENT ON COLUMN t_recycle_bin.original_path_name IS '删除时的完整名称路径快照';
COMMENT ON COLUMN t_recycle_bin.total_size IS '该记录对应子树总字节数';
COMMENT ON COLUMN t_recycle_bin.status IS '状态：1 启用，0 禁用';

CREATE INDEX IF NOT EXISTS idx_recycle_bin_user_id ON t_recycle_bin (user_id);
CREATE INDEX IF NOT EXISTS idx_recycle_bin_user_create_time ON t_recycle_bin (user_id, create_time);

-- ================== 用户存储空间迁移任务表 ==================
CREATE TABLE IF NOT EXISTS t_user_migration_task
(
    id              VARCHAR(13) PRIMARY KEY,
    user_id         VARCHAR(13)  NOT NULL,
    source_space_id VARCHAR(13)  NOT NULL,
    target_space_id VARCHAR(13)  NOT NULL,
    new_quota       BIGINT       NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    total_bytes     BIGINT       NOT NULL DEFAULT 0,
    migrated_bytes  BIGINT       NOT NULL DEFAULT 0,
    error_msg       VARCHAR(4000),
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at       BIGINT       NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_user_migration_task IS '用户存储空间迁移任务表';
COMMENT ON COLUMN t_user_migration_task.status IS '任务状态：PENDING / RUNNING / COMPLETED / FAILED';

CREATE INDEX IF NOT EXISTS idx_user_migration_task_user_id ON t_user_migration_task (user_id);
CREATE INDEX IF NOT EXISTS idx_user_migration_task_status ON t_user_migration_task (status);
CREATE INDEX IF NOT EXISTS idx_user_migration_task_user_deleted_at ON t_user_migration_task (user_id, delete_at);

-- ================== 预览文件表 ==================
CREATE TABLE IF NOT EXISTS t_preview_file
(
    id               VARCHAR(13) PRIMARY KEY,
    file_node_id     VARCHAR(13)  NOT NULL,
    type             VARCHAR(32)  NOT NULL,
    storage_space_id VARCHAR(13)  NOT NULL,
    relative_path    VARCHAR(512) NOT NULL,
    size             BIGINT       NOT NULL DEFAULT 0,
    status           SMALLINT     NOT NULL DEFAULT 1,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_preview_file_node_type_delete_at UNIQUE (file_node_id, type, delete_at)
);

COMMENT ON TABLE t_preview_file IS '预览文件元数据表';
COMMENT ON COLUMN t_preview_file.file_node_id IS '文件节点 ID';
COMMENT ON COLUMN t_preview_file.type IS '预览类型';
COMMENT ON COLUMN t_preview_file.storage_space_id IS '存储空间 ID';
COMMENT ON COLUMN t_preview_file.relative_path IS '相对系统存储空间的路径';
COMMENT ON COLUMN t_preview_file.size IS '预览文件大小（字节）';

CREATE INDEX IF NOT EXISTS idx_preview_file_node_id ON t_preview_file (file_node_id);
CREATE INDEX IF NOT EXISTS idx_preview_file_type ON t_preview_file (type);

-- ================== 文件分片上传记录表 ==================
CREATE TABLE IF NOT EXISTS t_file_chunk
(
    id          VARCHAR(13) PRIMARY KEY,
    upload_id   VARCHAR(64)  NOT NULL,
    user_id     VARCHAR(13)  NOT NULL,
    chunk_index INT          NOT NULL,
    chunk_hash  VARCHAR(128) NOT NULL,
    size        BIGINT       NOT NULL,
    status      SMALLINT     NOT NULL DEFAULT 1,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at   BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_file_chunk_upload_index UNIQUE (upload_id, chunk_index)
);

COMMENT ON TABLE t_file_chunk IS '文件分片上传记录表';
COMMENT ON COLUMN t_file_chunk.upload_id IS '上传任务 ID';
COMMENT ON COLUMN t_file_chunk.user_id IS '用户 ID';
COMMENT ON COLUMN t_file_chunk.chunk_index IS '分片索引，从 0 开始';
COMMENT ON COLUMN t_file_chunk.chunk_hash IS '分片 hash（MD5）';
COMMENT ON COLUMN t_file_chunk.size IS '分片大小（字节）';

CREATE INDEX IF NOT EXISTS idx_file_chunk_upload_id ON t_file_chunk (upload_id);

-- ================== 系统配置表 ==================
CREATE TABLE IF NOT EXISTS t_system_config
(
    id           VARCHAR(13) PRIMARY KEY,
    config_key   VARCHAR(128) NOT NULL,
    config_value VARCHAR(512),
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_at    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_system_config_key_delete_at UNIQUE (config_key, delete_at)
);

COMMENT ON TABLE t_system_config IS '系统配置表';
COMMENT ON COLUMN t_system_config.config_key IS '配置键';
COMMENT ON COLUMN t_system_config.config_value IS '配置值';

CREATE INDEX IF NOT EXISTS idx_system_config_key ON t_system_config (config_key);

-- ================== 文件名模糊搜索扩展 ==================
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_file_node_name_trgm ON t_file_node USING gin (name gin_trgm_ops);

-- ================== 初始化权限树 ==================
INSERT INTO t_permission (id, code, name, parent_id, status)
VALUES ('0000000000004', 'system:menu', '系统设置', NULL, 1)
ON CONFLICT (code, delete_at) DO NOTHING;

INSERT INTO t_permission (id, code, name, parent_id, status)
VALUES ('0000000000005', 'user:menu', '用户管理', '0000000000004', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

INSERT INTO t_permission (id, code, name, parent_id, status)
VALUES ('0000000000006', 'storage_space:menu', '存储空间管理', '0000000000004', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

INSERT INTO t_permission (id, code, name, parent_id, status)
VALUES ('0000000000007', 'file:menu', '文件管理', NULL, 1)
ON CONFLICT (code, delete_at) DO NOTHING;

-- ================== 初始化角色 ==================
INSERT INTO t_role (id, code, name, description, status)
VALUES ('0000000000001', 'super_admin', '超级管理员', '系统内置超级管理员，拥有所有权限且不可删除', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

INSERT INTO t_role (id, code, name, description, status)
VALUES ('0000000000002', 'system_admin', '系统管理员', '拥有系统设置与用户管理权限', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

INSERT INTO t_role (id, code, name, description, status)
VALUES ('0000000000003', 'common_user', '普通用户', '拥有个人文件管理权限', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

-- ================== 初始化资源 ==================
INSERT INTO t_resource (id, code, name, type, status)
VALUES
    ('0000000000008', 'POST:/jcloud/api/auth/register', '用户注册', 'PUBLIC', 1),
    ('0000000000009', 'POST:/jcloud/api/auth/login', '用户登录', 'PUBLIC', 1),
    ('000000000000a', 'GET:/jcloud/api/auth/me', '当前用户信息', 'LOGIN', 1),
    ('000000000000b', '/admin', '系统设置', 'PAGE', 1),
    ('000000000000c', '/admin/users', '用户管理', 'PAGE', 1),
    ('000000000000d', 'POST:/jcloud/api/users', '新增用户', 'API', 1),
    ('000000000000e', 'GET:/jcloud/api/users', '用户分页查询', 'API', 1),
    ('000000000000f', 'GET:/jcloud/api/users/{id}', '查看用户详情', 'API', 1),
    ('000000000000g', 'GET:/jcloud/api/users/search', '按用户名搜索用户', 'API', 1),
    ('000000000000h', 'PUT:/jcloud/api/users/{id}/roles', '分配用户角色', 'API', 1),
    ('000000000000i', 'PUT:/jcloud/api/users/{id}/status', '修改用户状态', 'API', 1),
    ('000000000000j', 'DELETE:/jcloud/api/users/{id}', '删除用户', 'API', 1),
    ('000000000000k', 'PUT:/jcloud/api/users/{id}', '编辑用户', 'API', 1),
    ('000000000000l', 'DELETE:/jcloud/api/users/batch', '批量删除用户', 'API', 1),
    ('000000000000m', 'PUT:/jcloud/api/users/batch/status', '批量修改用户状态', 'API', 1),
    ('000000000000n', 'GET:/jcloud/api/roles', '角色列表', 'API', 1),
    ('000000000000o', '/admin/storage-spaces', '存储空间管理', 'PAGE', 1),
    ('000000000000p', 'POST:/jcloud/api/admin/storage-spaces', '新增存储空间', 'API', 1),
    ('000000000000q', 'GET:/jcloud/api/admin/storage-spaces', '分页查询存储空间', 'API', 1),
    ('000000000000r', 'GET:/jcloud/api/admin/storage-spaces/{id}', '查看存储空间详情', 'API', 1),
    ('000000000000s', 'PUT:/jcloud/api/admin/storage-spaces/{id}', '编辑存储空间', 'API', 1),
    ('000000000000t', 'DELETE:/jcloud/api/admin/storage-spaces/{id}', '删除存储空间', 'API', 1),
    ('000000000000u', 'PUT:/jcloud/api/users/{id}/storage', '设置用户默认存储空间', 'API', 1),
    ('000000000000v', '/files', '文件管理页面', 'PAGE', 1),
    ('000000000000w', 'POST:/jcloud/api/files/upload', '上传文件', 'API', 1),
    ('000000000000x', 'GET:/jcloud/api/files', '分页查询文件列表', 'API', 1),
    ('000000000000y', 'GET:/jcloud/api/files/{id}/download', '下载文件', 'API', 1),
    ('000000000000z', 'POST:/jcloud/api/files/rename', '重命名文件或文件夹', 'API', 1),
    ('0000000000010', 'POST:/jcloud/api/files/folders', '创建文件夹', 'API', 1),
    ('0000000000011', 'POST:/jcloud/api/files/operations/pre-check', '移动复制预检', 'API', 1),
    ('0000000000012', 'POST:/jcloud/api/files/move', '移动文件或文件夹', 'API', 1),
    ('0000000000013', 'POST:/jcloud/api/files/copy', '复制文件或文件夹', 'API', 1),
    ('0000000000014', 'POST:/jcloud/api/files/upload/pre-check', '上传前预检', 'API', 1),
    ('0000000000015', 'POST:/jcloud/api/files/instant', '秒传', 'API', 1),
    ('0000000000016', 'POST:/jcloud/api/files/delete', '删除文件或文件夹到回收站', 'API', 1),
    ('0000000000017', 'GET:/jcloud/api/files/trash', '分页查询回收站列表', 'API', 1),
    ('0000000000018', 'POST:/jcloud/api/files/trash/restore/pre-check', '恢复前冲突预检', 'API', 1),
    ('0000000000019', 'POST:/jcloud/api/files/trash/restore', '恢复文件或文件夹', 'API', 1),
    ('000000000001a', 'POST:/jcloud/api/files/trash/permanent-delete', '永久删除回收站记录', 'API', 1),
    ('000000000001b', 'GET:/jcloud/api/files/{id}/preview', '文件预览', 'API', 1),
    ('000000000001c', 'POST:/jcloud/api/files/chunked-upload/init', '初始化分片上传', 'API', 1),
    ('000000000001d', 'POST:/jcloud/api/files/chunked-upload/{uploadId}/chunks', '上传分片', 'API', 1),
    ('000000000001e', 'GET:/jcloud/api/files/chunked-upload/{uploadId}/chunks', '查询已上传分片', 'API', 1),
    ('000000000001f', 'POST:/jcloud/api/files/chunked-upload/{uploadId}/complete', '完成分片上传', 'API', 1),
    ('000000000001g', 'POST:/jcloud/api/admin/users/{id}/migrate', '发起用户存储空间迁移', 'API', 1),
    ('000000000001h', 'GET:/jcloud/api/admin/users/{id}/migration-task', '查询用户最新迁移任务', 'API', 1),
    ('000000000001i', 'GET:/jcloud/api/admin/system/init-status', '查询系统初始化状态', 'API', 1),
    ('000000000001j', 'POST:/jcloud/api/admin/system/initialize', '执行系统初始化', 'API', 1),
    ('000000000001k', 'GET:/jcloud/api/admin/storage-spaces/system-config', '查询系统数据目录配置', 'API', 1),
    ('000000000001l', 'PUT:/jcloud/api/admin/storage-spaces/system-config', '更新系统数据目录配置', 'API', 1)
ON CONFLICT (code, delete_at) DO NOTHING;

-- ================== 资源与权限关联 ==================
INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000001m', '0000000000004', '000000000000b'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000004' AND resource_id = '000000000000b');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000001n', '0000000000005', '000000000000c'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '000000000000c');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000001o', '0000000000005', '000000000000d'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '000000000000d');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000001p', '0000000000005', '000000000000e'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '000000000000e');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000001q', '0000000000005', '000000000000f'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '000000000000f');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000001r', '0000000000005', '000000000000g'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '000000000000g');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000001s', '0000000000005', '000000000000h'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '000000000000h');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000001t', '0000000000005', '000000000000i'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '000000000000i');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000001u', '0000000000005', '000000000000j'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '000000000000j');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000001v', '0000000000005', '000000000000k'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '000000000000k');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000001w', '0000000000005', '000000000000l'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '000000000000l');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000001x', '0000000000005', '000000000000m'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '000000000000m');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000001y', '0000000000005', '000000000000n'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000005' AND resource_id = '000000000000n');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000001z', '0000000000006', '000000000000o'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000006' AND resource_id = '000000000000o');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000020', '0000000000006', '000000000000p'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000006' AND resource_id = '000000000000p');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000021', '0000000000006', '000000000000q'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000006' AND resource_id = '000000000000q');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000022', '0000000000006', '000000000000r'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000006' AND resource_id = '000000000000r');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000023', '0000000000006', '000000000000s'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000006' AND resource_id = '000000000000s');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000024', '0000000000006', '000000000000t'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000006' AND resource_id = '000000000000t');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000025', '0000000000006', '000000000000u'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000006' AND resource_id = '000000000000u');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000026', '0000000000006', '000000000001i'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000006' AND resource_id = '000000000001i');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000027', '0000000000006', '000000000001j'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000006' AND resource_id = '000000000001j');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000028', '0000000000006', '000000000001k'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000006' AND resource_id = '000000000001k');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '0000000000029', '0000000000006', '000000000001l'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000006' AND resource_id = '000000000001l');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002a', '0000000000007', '000000000000v'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000000v');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002b', '0000000000007', '000000000000w'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000000w');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002c', '0000000000007', '000000000000x'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000000x');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002d', '0000000000007', '000000000000y'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000000y');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002e', '0000000000007', '000000000000z'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000000z');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002f', '0000000000007', '0000000000010'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000010');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002g', '0000000000007', '0000000000011'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000011');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002h', '0000000000007', '0000000000012'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000012');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002i', '0000000000007', '0000000000013'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000013');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002j', '0000000000007', '0000000000014'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000014');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002k', '0000000000007', '0000000000015'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000015');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002l', '0000000000007', '0000000000016'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000016');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002m', '0000000000007', '0000000000017'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000017');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002n', '0000000000007', '0000000000018'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000018');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002o', '0000000000007', '0000000000019'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '0000000000019');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002p', '0000000000007', '000000000001a'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000001a');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002q', '0000000000007', '000000000001b'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000001b');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002r', '0000000000007', '000000000001c'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000001c');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002s', '0000000000007', '000000000001d'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000001d');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002t', '0000000000007', '000000000001e'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000001e');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002u', '0000000000007', '000000000001f'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000007' AND resource_id = '000000000001f');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002v', '0000000000004', '000000000001g'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000004' AND resource_id = '000000000001g');

INSERT INTO t_permission_resource (id, permission_id, resource_id)
SELECT '000000000002w', '0000000000004', '000000000001h'
WHERE NOT EXISTS (SELECT 1 FROM t_permission_resource WHERE permission_id = '0000000000004' AND resource_id = '000000000001h');

-- ================== 为角色绑定权限 ==================
INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '000000000002x', '0000000000001', '0000000000004'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000001' AND permission_id = '0000000000004');

INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '000000000002y', '0000000000001', '0000000000005'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000001' AND permission_id = '0000000000005');

INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '000000000002z', '0000000000001', '0000000000006'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000001' AND permission_id = '0000000000006');

INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '0000000000030', '0000000000001', '0000000000007'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000001' AND permission_id = '0000000000007');

INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '0000000000031', '0000000000002', '0000000000004'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000002' AND permission_id = '0000000000004');

INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '0000000000032', '0000000000002', '0000000000005'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000002' AND permission_id = '0000000000005');

INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '0000000000033', '0000000000002', '0000000000006'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000002' AND permission_id = '0000000000006');

INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '0000000000034', '0000000000002', '0000000000007'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000002' AND permission_id = '0000000000007');

INSERT INTO t_role_permission (id, role_id, permission_id)
SELECT '0000000000035', '0000000000003', '0000000000007'
WHERE NOT EXISTS (SELECT 1 FROM t_role_permission WHERE role_id = '0000000000003' AND permission_id = '0000000000007');
