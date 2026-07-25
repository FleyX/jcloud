
-- pg_trgm 扩展（文件名模糊搜索依赖 gin_trgm_ops）
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- public.t_file_chunk 定义

-- Drop table

-- DROP TABLE t_file_chunk;

CREATE TABLE t_file_chunk (
	id varchar(13) NOT NULL,
	upload_id varchar(64) NOT NULL, -- 上传任务 ID
	user_id varchar(13) NOT NULL, -- 用户 ID
	chunk_index int4 NOT NULL, -- 分片索引，从 0 开始
	chunk_hash varchar(128) NOT NULL, -- 分片 hash（MD5）
	"size" int8 NOT NULL, -- 分片大小（字节）
	status int2 DEFAULT 1 NOT NULL,
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	delete_at int8 DEFAULT 0 NOT NULL,
	CONSTRAINT t_file_chunk_pkey PRIMARY KEY (id),
	CONSTRAINT uk_file_chunk_upload_index UNIQUE (upload_id, chunk_index)
);
COMMENT ON TABLE public.t_file_chunk IS '文件分片上传记录表';

-- Column comments

COMMENT ON COLUMN public.t_file_chunk.upload_id IS '上传任务 ID';
COMMENT ON COLUMN public.t_file_chunk.user_id IS '用户 ID';
COMMENT ON COLUMN public.t_file_chunk.chunk_index IS '分片索引，从 0 开始';
COMMENT ON COLUMN public.t_file_chunk.chunk_hash IS '分片 hash（MD5）';
COMMENT ON COLUMN public.t_file_chunk."size" IS '分片大小（字节）';


-- public.t_file_node 定义

-- Drop table

-- DROP TABLE t_file_node;

CREATE TABLE t_file_node (
	id varchar(13) NOT NULL, -- 节点 ID，13 位定长 base36 字符串
	user_id varchar(13) NOT NULL, -- 用户 ID
	parent_id varchar(13) DEFAULT '0000000000000'::character varying NOT NULL, -- 父节点 ID，根目录为虚拟根节点占位 ID
	"name" varchar(255) NOT NULL, -- 节点名称
	"type" varchar(32) NOT NULL, -- 类型：file / folder
	"size" int8 DEFAULT 0 NOT NULL, -- 文件大小（字节），文件夹为 0
	hash varchar(128) NULL, -- 内容标识符：本地文件为身份 hash，远程文件为 etag
	storage_space_id varchar(13) NULL, -- 本地存储空间 ID，source_type=local 时有效
	"path" varchar(300) DEFAULT '0000000000000'::character varying NOT NULL, -- 从虚拟根到父节点的 id 路径，使用 "." 分割
	mime_type varchar(128) NULL, -- MIME 类型
	status int2 DEFAULT 1 NOT NULL, -- 状态：1 启用，0 禁用
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	last_modified int8 DEFAULT 0 NOT NULL, -- 文件/目录最后修改时间（毫秒时间戳），用于同步 diff 判断
	source_type varchar(32) DEFAULT 'local'::character varying NOT NULL, -- 来源类型：local 本地存储空间 / remote 远程挂载
	remote_mount_id varchar(13) NULL, -- 远程挂载 ID，source_type=remote 时有效
	CONSTRAINT t_file_node_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_file_node_name_trgm ON public.t_file_node USING gin (name gin_trgm_ops);
CREATE INDEX idx_file_node_remote_mount_id ON public.t_file_node USING btree (remote_mount_id);
CREATE INDEX idx_file_node_user_hash ON public.t_file_node USING btree (user_id, hash);
CREATE INDEX idx_file_node_user_parent_name_modified ON public.t_file_node USING btree (user_id, parent_id, name, last_modified);
CREATE INDEX idx_file_node_user_path ON public.t_file_node USING btree (user_id, path);
CREATE INDEX idx_file_node_user_source ON public.t_file_node USING btree (user_id, source_type);
COMMENT ON TABLE public.t_file_node IS '文件节点表，记录文件与文件夹';

-- Column comments

COMMENT ON COLUMN public.t_file_node.id IS '节点 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_file_node.user_id IS '用户 ID';
COMMENT ON COLUMN public.t_file_node.parent_id IS '父节点 ID，根目录为虚拟根节点占位 ID';
COMMENT ON COLUMN public.t_file_node."name" IS '节点名称';
COMMENT ON COLUMN public.t_file_node."type" IS '类型：file / folder';
COMMENT ON COLUMN public.t_file_node."size" IS '文件大小（字节），文件夹为 0';
COMMENT ON COLUMN public.t_file_node.hash IS '内容标识符：本地文件为身份 hash，远程文件为 etag';
COMMENT ON COLUMN public.t_file_node.storage_space_id IS '本地存储空间 ID，source_type=local 时有效';
COMMENT ON COLUMN public.t_file_node."path" IS '从虚拟根到父节点的 id 路径，使用 "." 分割';
COMMENT ON COLUMN public.t_file_node.mime_type IS 'MIME 类型';
COMMENT ON COLUMN public.t_file_node.status IS '状态：1 启用，0 禁用';
COMMENT ON COLUMN public.t_file_node.last_modified IS '文件/目录最后修改时间（毫秒时间戳），用于同步 diff 判断';
COMMENT ON COLUMN public.t_file_node.source_type IS '来源类型：local 本地存储空间 / remote 远程挂载';
COMMENT ON COLUMN public.t_file_node.remote_mount_id IS '远程挂载 ID，source_type=remote 时有效';


-- public.t_preview_file 定义

-- Drop table

-- DROP TABLE t_preview_file;

CREATE TABLE t_preview_file (
	id varchar(13) NOT NULL,
	file_node_id varchar(13) NOT NULL, -- 文件节点 ID
	"type" varchar(32) NOT NULL, -- 预览类型
	storage_space_id varchar(13) NOT NULL, -- 存储空间 ID
	relative_path varchar(512) NOT NULL, -- 相对系统存储空间的路径
	"size" int8 DEFAULT 0 NOT NULL, -- 预览文件大小（字节）
	status int2 DEFAULT 1 NOT NULL,
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	delete_at int8 DEFAULT 0 NOT NULL,
	CONSTRAINT t_preview_file_pkey PRIMARY KEY (id),
	CONSTRAINT uk_preview_file_node_type_delete_at UNIQUE (file_node_id, type, delete_at)
);
COMMENT ON TABLE public.t_preview_file IS '预览文件元数据表';

-- Column comments

COMMENT ON COLUMN public.t_preview_file.file_node_id IS '文件节点 ID';
COMMENT ON COLUMN public.t_preview_file."type" IS '预览类型';
COMMENT ON COLUMN public.t_preview_file.storage_space_id IS '存储空间 ID';
COMMENT ON COLUMN public.t_preview_file.relative_path IS '相对系统存储空间的路径';
COMMENT ON COLUMN public.t_preview_file."size" IS '预览文件大小（字节）';


-- public.t_recycle_bin 定义

-- Drop table

-- DROP TABLE t_recycle_bin;

CREATE TABLE t_recycle_bin (
	id varchar(13) NOT NULL, -- 记录 ID，13 位定长 base36 字符串
	user_id varchar(13) NOT NULL, -- 用户 ID
	"name" varchar(255) NOT NULL, -- 节点名称
	"type" varchar(32) NOT NULL, -- 类型：file / folder
	original_path_name varchar(300) DEFAULT '/'::character varying NOT NULL, -- 删除时的完整名称路径快照
	total_size int8 DEFAULT 0 NOT NULL, -- 该记录对应子树总字节数
	status int2 DEFAULT 1 NOT NULL, -- 状态：1 启用，0 禁用
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_recycle_bin_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_recycle_bin_user_create_time ON public.t_recycle_bin USING btree (user_id, create_time);
COMMENT ON TABLE public.t_recycle_bin IS '回收站记录表，记录用户主动删除的节点';

-- Column comments

COMMENT ON COLUMN public.t_recycle_bin.id IS '记录 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_recycle_bin.user_id IS '用户 ID';
COMMENT ON COLUMN public.t_recycle_bin."name" IS '节点名称';
COMMENT ON COLUMN public.t_recycle_bin."type" IS '类型：file / folder';
COMMENT ON COLUMN public.t_recycle_bin.original_path_name IS '删除时的完整名称路径快照';
COMMENT ON COLUMN public.t_recycle_bin.total_size IS '该记录对应子树总字节数';
COMMENT ON COLUMN public.t_recycle_bin.status IS '状态：1 启用，0 禁用';


-- public.t_remote_mount 定义

-- Drop table

-- DROP TABLE t_remote_mount;

CREATE TABLE t_remote_mount (
	id varchar(13) NOT NULL, -- 挂载点 ID，13 位定长 base36 字符串
	user_id varchar(13) NOT NULL, -- 所属用户 ID
	"name" varchar(255) NOT NULL, -- 挂载点显示名，也是文件树根目录下文件夹名称
	"type" varchar(32) DEFAULT 'webdav'::character varying NOT NULL, -- 协议类型：webdav / s3 / nfs 等，首期仅 webdav
	enabled int2 DEFAULT 0 NOT NULL, -- 是否启用定时同步：1 启用，0 禁用
	cron_expr varchar(128) NULL, -- 定时同步 cron 表达式
	next_sync_time timestamp NULL, -- 下次定时同步时间
	last_sync_time timestamp NULL, -- 上次同步完成时间
	last_sync_status varchar(32) NULL, -- 上次同步状态：COMPLETED / FAILED / PARTIAL
	last_sync_error varchar(4000) NULL, -- 上次同步错误信息
	config jsonb DEFAULT '{}'::jsonb NOT NULL, -- 协议配置 JSON，如 url、username、password、rootPath 等
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL, -- 创建时间
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL, -- 更新时间
	delete_at int8 DEFAULT 0 NOT NULL, -- 逻辑删除时间戳：0 表示未删除
	CONSTRAINT t_remote_mount_pkey PRIMARY KEY (id),
	CONSTRAINT uk_remote_mount_user_name_delete_at UNIQUE (user_id, name, delete_at)
);
CREATE INDEX idx_remote_mount_enabled_next_time ON public.t_remote_mount USING btree (enabled, next_sync_time);
CREATE INDEX idx_remote_mount_user_id ON public.t_remote_mount USING btree (user_id);
COMMENT ON TABLE public.t_remote_mount IS '远程挂载配置表';

-- Column comments

COMMENT ON COLUMN public.t_remote_mount.id IS '挂载点 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_remote_mount.user_id IS '所属用户 ID';
COMMENT ON COLUMN public.t_remote_mount."name" IS '挂载点显示名，也是文件树根目录下文件夹名称';
COMMENT ON COLUMN public.t_remote_mount."type" IS '协议类型：webdav / s3 / nfs 等，首期仅 webdav';
COMMENT ON COLUMN public.t_remote_mount.enabled IS '是否启用定时同步：1 启用，0 禁用';
COMMENT ON COLUMN public.t_remote_mount.cron_expr IS '定时同步 cron 表达式';
COMMENT ON COLUMN public.t_remote_mount.next_sync_time IS '下次定时同步时间';
COMMENT ON COLUMN public.t_remote_mount.last_sync_time IS '上次同步完成时间';
COMMENT ON COLUMN public.t_remote_mount.last_sync_status IS '上次同步状态：COMPLETED / FAILED / PARTIAL';
COMMENT ON COLUMN public.t_remote_mount.last_sync_error IS '上次同步错误信息';
COMMENT ON COLUMN public.t_remote_mount.config IS '协议配置 JSON，如 url、username、password、rootPath 等';
COMMENT ON COLUMN public.t_remote_mount.create_time IS '创建时间';
COMMENT ON COLUMN public.t_remote_mount.update_time IS '更新时间';
COMMENT ON COLUMN public.t_remote_mount.delete_at IS '逻辑删除时间戳：0 表示未删除';


-- public.t_remote_sync_task 定义

-- Drop table

-- DROP TABLE t_remote_sync_task;

CREATE TABLE t_remote_sync_task (
	id varchar(13) NOT NULL, -- 任务 ID，13 位定长 base36 字符串
	remote_mount_id varchar(13) NOT NULL, -- 关联的远程挂载 ID
	"type" varchar(32) NOT NULL, -- 触发方式：manual 手动 / scheduled 定时
	status varchar(32) NOT NULL, -- 任务状态：PENDING / RUNNING / COMPLETED / FAILED / PARTIAL
	start_time timestamp NULL, -- 任务开始时间
	end_time timestamp NULL, -- 任务结束时间
	total_count int8 DEFAULT 0 NOT NULL, -- 扫描到的远程节点总数
	success_count int8 DEFAULT 0 NOT NULL, -- 成功同步节点数
	fail_count int8 DEFAULT 0 NOT NULL, -- 失败/跳过节点数
	error_msg varchar(4000) NULL, -- 错误信息
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL, -- 创建时间
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL, -- 更新时间
	delete_at int8 DEFAULT 0 NOT NULL, -- 逻辑删除时间戳：0 表示未删除
	CONSTRAINT t_remote_sync_task_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_remote_sync_task_mount_create_time ON public.t_remote_sync_task USING btree (remote_mount_id, create_time);
COMMENT ON TABLE public.t_remote_sync_task IS '远程挂载同步任务记录表';

-- Column comments

COMMENT ON COLUMN public.t_remote_sync_task.id IS '任务 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_remote_sync_task.remote_mount_id IS '关联的远程挂载 ID';
COMMENT ON COLUMN public.t_remote_sync_task."type" IS '触发方式：manual 手动 / scheduled 定时';
COMMENT ON COLUMN public.t_remote_sync_task.status IS '任务状态：PENDING / RUNNING / COMPLETED / FAILED / PARTIAL';
COMMENT ON COLUMN public.t_remote_sync_task.start_time IS '任务开始时间';
COMMENT ON COLUMN public.t_remote_sync_task.end_time IS '任务结束时间';
COMMENT ON COLUMN public.t_remote_sync_task.total_count IS '扫描到的远程节点总数';
COMMENT ON COLUMN public.t_remote_sync_task.success_count IS '成功同步节点数';
COMMENT ON COLUMN public.t_remote_sync_task.fail_count IS '失败/跳过节点数';
COMMENT ON COLUMN public.t_remote_sync_task.error_msg IS '错误信息';
COMMENT ON COLUMN public.t_remote_sync_task.create_time IS '创建时间';
COMMENT ON COLUMN public.t_remote_sync_task.update_time IS '更新时间';
COMMENT ON COLUMN public.t_remote_sync_task.delete_at IS '逻辑删除时间戳：0 表示未删除';


-- public.t_role 定义

-- Drop table

-- DROP TABLE t_role;

CREATE TABLE t_role (
	id varchar(13) NOT NULL, -- 角色 ID，13 位定长 base36 字符串
	code varchar(64) NOT NULL, -- 角色编码
	"name" varchar(64) NOT NULL, -- 角色名称
	description varchar(255) NULL, -- 角色描述
	status int2 DEFAULT 1 NOT NULL, -- 状态：1 启用，0 禁用
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	delete_at int8 DEFAULT 0 NOT NULL, -- 逻辑删除时间戳：0 表示未删除
	CONSTRAINT t_role_pkey PRIMARY KEY (id),
	CONSTRAINT uk_role_code_delete_at UNIQUE (code, delete_at)
);
COMMENT ON TABLE public.t_role IS '角色表';

-- Column comments

COMMENT ON COLUMN public.t_role.id IS '角色 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_role.code IS '角色编码';
COMMENT ON COLUMN public.t_role."name" IS '角色名称';
COMMENT ON COLUMN public.t_role.description IS '角色描述';
COMMENT ON COLUMN public.t_role.status IS '状态：1 启用，0 禁用';
COMMENT ON COLUMN public.t_role.delete_at IS '逻辑删除时间戳：0 表示未删除';


-- public.t_role_permission 定义

-- Drop table

-- DROP TABLE t_role_permission;

CREATE TABLE t_role_permission (
	id varchar(13) NOT NULL,
	role_id varchar(13) NOT NULL, -- 角色 ID
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	permission_code varchar(64) NOT NULL, -- 权限编码，引用 permissions.yml 中的 code
	CONSTRAINT t_role_permission_pkey PRIMARY KEY (id),
	CONSTRAINT uk_role_permission UNIQUE (role_id, permission_code)
);
CREATE INDEX idx_role_permission_code ON public.t_role_permission USING btree (permission_code);
COMMENT ON TABLE public.t_role_permission IS '角色权限关联表';

-- Column comments

COMMENT ON COLUMN public.t_role_permission.role_id IS '角色 ID';
COMMENT ON COLUMN public.t_role_permission.permission_code IS '权限编码，引用 permissions.yml 中的 code';


-- public.t_share 定义

-- Drop table

-- DROP TABLE t_share;

CREATE TABLE t_share (
	id varchar(13) NOT NULL, -- 分享 ID，13 位定长 base36 字符串
	user_id varchar(13) NOT NULL, -- 所有者用户 ID
	"name" varchar(128) NOT NULL, -- 分享名称
	description varchar(512) NULL, -- 分享描述
	share_code varchar(8) NOT NULL, -- 公开访问短码，8 位唯一
	password_hash varchar(128) NULL, -- 访问密码哈希，为空表示无密码
	expire_at timestamp NULL, -- 过期时间，为空表示永久有效
	max_views int8 NULL, -- 最大访问次数，为空表示无限制
	view_count int8 DEFAULT 0 NOT NULL, -- 已访问次数
	status int2 DEFAULT 1 NOT NULL, -- 状态：1 启用，0 停用
	delete_at int8 DEFAULT 0 NOT NULL, -- 逻辑删除时间戳：0 表示未删除
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_share_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_share_create_time ON public.t_share USING btree (create_time);
CREATE INDEX idx_share_user_id ON public.t_share USING btree (user_id);
CREATE UNIQUE INDEX uk_share_code_delete_at ON public.t_share USING btree (share_code, delete_at);
COMMENT ON TABLE public.t_share IS '分享主表';

-- Column comments

COMMENT ON COLUMN public.t_share.id IS '分享 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_share.user_id IS '所有者用户 ID';
COMMENT ON COLUMN public.t_share."name" IS '分享名称';
COMMENT ON COLUMN public.t_share.description IS '分享描述';
COMMENT ON COLUMN public.t_share.share_code IS '公开访问短码，8 位唯一';
COMMENT ON COLUMN public.t_share.password_hash IS '访问密码哈希，为空表示无密码';
COMMENT ON COLUMN public.t_share.expire_at IS '过期时间，为空表示永久有效';
COMMENT ON COLUMN public.t_share.max_views IS '最大访问次数，为空表示无限制';
COMMENT ON COLUMN public.t_share.view_count IS '已访问次数';
COMMENT ON COLUMN public.t_share.status IS '状态：1 启用，0 停用';
COMMENT ON COLUMN public.t_share.delete_at IS '逻辑删除时间戳：0 表示未删除';


-- public.t_share_item 定义

-- Drop table

-- DROP TABLE t_share_item;

CREATE TABLE t_share_item (
	id varchar(13) NOT NULL, -- 分享项 ID，13 位定长 base36 字符串
	share_id varchar(13) NOT NULL, -- 所属分享 ID
	file_node_id varchar(13) NOT NULL, -- 文件节点 ID
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_share_item_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_share_item_file_node_id ON public.t_share_item USING btree (file_node_id);
CREATE UNIQUE INDEX uk_share_item ON public.t_share_item USING btree (share_id, file_node_id);
COMMENT ON TABLE public.t_share_item IS '分享项表，记录分享包含的文件节点';

-- Column comments

COMMENT ON COLUMN public.t_share_item.id IS '分享项 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_share_item.share_id IS '所属分享 ID';
COMMENT ON COLUMN public.t_share_item.file_node_id IS '文件节点 ID';


-- public.t_storage_space 定义

-- Drop table

-- DROP TABLE t_storage_space;

CREATE TABLE t_storage_space (
	id varchar(13) NOT NULL, -- 存储空间 ID，13 位定长 base36 字符串
	"name" varchar(64) NOT NULL, -- 存储空间名称
	"path" varchar(512) NOT NULL, -- 物理路径
	capacity int8 NOT NULL, -- 容量（字节）
	used_space int8 DEFAULT 0 NOT NULL, -- 已用空间（字节）
	free_space int8 DEFAULT 0 NOT NULL, -- 剩余空间（字节）
	is_primary int2 DEFAULT 0 NOT NULL, -- 是否主存储空间：1 是，0 否
	status int2 DEFAULT 1 NOT NULL, -- 状态：1 启用，0 禁用
	remark varchar(255) NULL, -- 备注
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	delete_at int8 DEFAULT 0 NOT NULL, -- 逻辑删除时间戳：0 表示未删除
	CONSTRAINT t_storage_space_pkey PRIMARY KEY (id),
	CONSTRAINT uk_storage_space_path_delete_at UNIQUE (path, delete_at)
);
COMMENT ON TABLE public.t_storage_space IS '存储空间表';

-- Column comments

COMMENT ON COLUMN public.t_storage_space.id IS '存储空间 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_storage_space."name" IS '存储空间名称';
COMMENT ON COLUMN public.t_storage_space."path" IS '物理路径';
COMMENT ON COLUMN public.t_storage_space.capacity IS '容量（字节）';
COMMENT ON COLUMN public.t_storage_space.used_space IS '已用空间（字节）';
COMMENT ON COLUMN public.t_storage_space.free_space IS '剩余空间（字节）';
COMMENT ON COLUMN public.t_storage_space.is_primary IS '是否主存储空间：1 是，0 否';
COMMENT ON COLUMN public.t_storage_space.status IS '状态：1 启用，0 禁用';
COMMENT ON COLUMN public.t_storage_space.remark IS '备注';
COMMENT ON COLUMN public.t_storage_space.delete_at IS '逻辑删除时间戳：0 表示未删除';


-- public.t_system_config 定义

-- Drop table

-- DROP TABLE t_system_config;

CREATE TABLE t_system_config (
	id varchar(13) NOT NULL,
	config_key varchar(128) NOT NULL, -- 配置键
	config_value varchar(512) NULL, -- 配置值
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	delete_at int8 DEFAULT 0 NOT NULL,
	CONSTRAINT t_system_config_pkey PRIMARY KEY (id),
	CONSTRAINT uk_system_config_key_delete_at UNIQUE (config_key, delete_at)
);
COMMENT ON TABLE public.t_system_config IS '系统配置表';

-- Column comments

COMMENT ON COLUMN public.t_system_config.config_key IS '配置键';
COMMENT ON COLUMN public.t_system_config.config_value IS '配置值';


-- public.t_transfer_task 定义

-- Drop table

-- DROP TABLE t_transfer_task;

CREATE TABLE t_transfer_task (
	id varchar(13) NOT NULL, -- 任务 ID，13 位定长 base36 字符串
	user_id varchar(13) NOT NULL, -- 发起用户 ID
	op_type varchar(16) NOT NULL, -- 操作类型：copy 复制 / move 移动
	source_type varchar(16) NOT NULL, -- 来源类型：local / remote
	target_type varchar(16) NOT NULL, -- 目标类型：local / remote
	source_mount_id varchar(13) NULL, -- 源为远程时的挂载 ID，源为本地为空
	target_mount_id varchar(13) NULL, -- 目标为远程时的挂载 ID，目标为本地为空
	target_parent_id varchar(13) NOT NULL, -- 目标父节点 ID，根目录为虚拟根节点占位 ID
	status varchar(32) NOT NULL, -- 任务状态：PENDING / RUNNING / CANCELLING / CANCELED / COMPLETED / FAILED / PARTIAL
	total_count int8 DEFAULT 0 NOT NULL, -- 待传输文件节点总数（含文件夹展开后的文件）
	success_count int8 DEFAULT 0 NOT NULL, -- 成功传输文件数
	fail_count int8 DEFAULT 0 NOT NULL, -- 失败/跳过文件数
	total_bytes int8 DEFAULT 0 NOT NULL, -- 待传输总字节数，用于配额预检与进度展示
	items jsonb DEFAULT '[]'::jsonb NOT NULL, -- 传输项快照 JSON：用户勾选的顶层节点（id、名称、类型、大小、冲突策略、最终名）
	fail_detail varchar(4000) NULL, -- 失败明细 JSON（截断至 4000 字符）：每项含名称与失败原因
	error_msg varchar(4000) NULL, -- 任务级错误信息
	start_time timestamp NULL, -- 任务开始时间
	end_time timestamp NULL, -- 任务结束时间
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL, -- 创建时间
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL, -- 更新时间
	delete_at int8 DEFAULT 0 NOT NULL, -- 逻辑删除时间戳：0 表示未删除
	CONSTRAINT t_transfer_task_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_transfer_task_user_create_time ON public.t_transfer_task USING btree (user_id, create_time);
COMMENT ON TABLE public.t_transfer_task IS '跨来源传输任务表';

-- Column comments

COMMENT ON COLUMN public.t_transfer_task.id IS '任务 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_transfer_task.user_id IS '发起用户 ID';
COMMENT ON COLUMN public.t_transfer_task.op_type IS '操作类型：copy 复制 / move 移动';
COMMENT ON COLUMN public.t_transfer_task.source_type IS '来源类型：local / remote';
COMMENT ON COLUMN public.t_transfer_task.target_type IS '目标类型：local / remote';
COMMENT ON COLUMN public.t_transfer_task.source_mount_id IS '源为远程时的挂载 ID，源为本地为空';
COMMENT ON COLUMN public.t_transfer_task.target_mount_id IS '目标为远程时的挂载 ID，目标为本地为空';
COMMENT ON COLUMN public.t_transfer_task.target_parent_id IS '目标父节点 ID，根目录为虚拟根节点占位 ID';
COMMENT ON COLUMN public.t_transfer_task.status IS '任务状态：PENDING / RUNNING / CANCELLING / CANCELED / COMPLETED / FAILED / PARTIAL';
COMMENT ON COLUMN public.t_transfer_task.total_count IS '待传输文件节点总数（含文件夹展开后的文件）';
COMMENT ON COLUMN public.t_transfer_task.success_count IS '成功传输文件数';
COMMENT ON COLUMN public.t_transfer_task.fail_count IS '失败/跳过文件数';
COMMENT ON COLUMN public.t_transfer_task.total_bytes IS '待传输总字节数，用于配额预检与进度展示';
COMMENT ON COLUMN public.t_transfer_task.items IS '传输项快照 JSON：用户勾选的顶层节点（id、名称、类型、大小、冲突策略、最终名）';
COMMENT ON COLUMN public.t_transfer_task.fail_detail IS '失败明细 JSON（截断至 4000 字符）：每项含名称与失败原因';
COMMENT ON COLUMN public.t_transfer_task.error_msg IS '任务级错误信息';
COMMENT ON COLUMN public.t_transfer_task.start_time IS '任务开始时间';
COMMENT ON COLUMN public.t_transfer_task.end_time IS '任务结束时间';
COMMENT ON COLUMN public.t_transfer_task.create_time IS '创建时间';
COMMENT ON COLUMN public.t_transfer_task.update_time IS '更新时间';
COMMENT ON COLUMN public.t_transfer_task.delete_at IS '逻辑删除时间戳：0 表示未删除';


-- public.t_user 定义

-- Drop table

-- DROP TABLE t_user;

CREATE TABLE t_user (
	id varchar(13) NOT NULL, -- 用户 ID，13 位定长 base36 字符串
	username varchar(32) NOT NULL, -- 用户名，全局唯一，小写，6-32 位，仅含 a-z0-9_；内置管理员 admin 除外
	"password" varchar(128) NOT NULL, -- 加密后的密码
	email varchar(128) NULL, -- 邮箱
	nickname varchar(64) NULL, -- 昵称
	status int2 DEFAULT 1 NOT NULL, -- 状态：1 启用，0 禁用
	is_admin int2 DEFAULT 0 NOT NULL, -- 是否为超级管理员：1 是，0 否
	storage_space_id varchar(13) NULL, -- 默认存储空间 ID
	quota int8 DEFAULT 0 NOT NULL, -- 用户配额（字节）
	used_space int8 DEFAULT 0 NOT NULL, -- 已用空间（字节）
	reserved_space int8 DEFAULT 0 NOT NULL, -- 预占空间（字节）
	read_only int2 DEFAULT 0 NOT NULL, -- 是否只读：0 否，1 是（迁移期间）
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	delete_at int8 DEFAULT 0 NOT NULL, -- 逻辑删除时间戳：0 表示未删除
	webdav_enabled bool DEFAULT false NOT NULL, -- 是否启用 WebDAV 访问
	CONSTRAINT t_user_pkey PRIMARY KEY (id),
	CONSTRAINT uk_user_username UNIQUE (username)
);
CREATE INDEX idx_user_storage_space_id ON public.t_user USING btree (storage_space_id);
COMMENT ON TABLE public.t_user IS '用户表';

-- Column comments

COMMENT ON COLUMN public.t_user.id IS '用户 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_user.username IS '用户名，全局唯一，小写，6-32 位，仅含 a-z0-9_；内置管理员 admin 除外';
COMMENT ON COLUMN public.t_user."password" IS '加密后的密码';
COMMENT ON COLUMN public.t_user.email IS '邮箱';
COMMENT ON COLUMN public.t_user.nickname IS '昵称';
COMMENT ON COLUMN public.t_user.status IS '状态：1 启用，0 禁用';
COMMENT ON COLUMN public.t_user.is_admin IS '是否为超级管理员：1 是，0 否';
COMMENT ON COLUMN public.t_user.storage_space_id IS '默认存储空间 ID';
COMMENT ON COLUMN public.t_user.quota IS '用户配额（字节）';
COMMENT ON COLUMN public.t_user.used_space IS '已用空间（字节）';
COMMENT ON COLUMN public.t_user.reserved_space IS '预占空间（字节）';
COMMENT ON COLUMN public.t_user.read_only IS '是否只读：0 否，1 是（迁移期间）';
COMMENT ON COLUMN public.t_user.delete_at IS '逻辑删除时间戳：0 表示未删除';
COMMENT ON COLUMN public.t_user.webdav_enabled IS '是否启用 WebDAV 访问';


-- public.t_user_migration_task 定义

-- Drop table

-- DROP TABLE t_user_migration_task;

CREATE TABLE t_user_migration_task (
	id varchar(13) NOT NULL,
	user_id varchar(13) NOT NULL,
	source_space_id varchar(13) NOT NULL,
	target_space_id varchar(13) NOT NULL,
	new_quota int8 NOT NULL,
	status varchar(32) DEFAULT 'PENDING'::character varying NOT NULL, -- 任务状态：PENDING / RUNNING / COMPLETED / FAILED
	total_bytes int8 DEFAULT 0 NOT NULL,
	migrated_bytes int8 DEFAULT 0 NOT NULL,
	error_msg varchar(4000) NULL,
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	delete_at int8 DEFAULT 0 NOT NULL,
	CONSTRAINT t_user_migration_task_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_user_migration_task_user_deleted_at ON public.t_user_migration_task USING btree (user_id, delete_at);
COMMENT ON TABLE public.t_user_migration_task IS '用户存储空间迁移任务表';

-- Column comments

COMMENT ON COLUMN public.t_user_migration_task.status IS '任务状态：PENDING / RUNNING / COMPLETED / FAILED';


-- public.t_user_role 定义

-- Drop table

-- DROP TABLE t_user_role;

CREATE TABLE t_user_role (
	id varchar(13) NOT NULL,
	user_id varchar(13) NOT NULL, -- 用户 ID
	role_id varchar(13) NOT NULL, -- 角色 ID
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_user_role_pkey PRIMARY KEY (id),
	CONSTRAINT uk_user_role UNIQUE (user_id, role_id)
);
CREATE INDEX idx_user_role_role_id ON public.t_user_role USING btree (role_id);
COMMENT ON TABLE public.t_user_role IS '用户角色关联表';

-- Column comments

COMMENT ON COLUMN public.t_user_role.user_id IS '用户 ID';
COMMENT ON COLUMN public.t_user_role.role_id IS '角色 ID';


-- public.t_user_sync_config 定义

-- Drop table

-- DROP TABLE t_user_sync_config;

CREATE TABLE t_user_sync_config (
	user_id varchar(13) NOT NULL, -- 用户 ID
	cron_expr varchar(128) NOT NULL, -- 定时同步 cron 表达式
	enabled int2 DEFAULT 1 NOT NULL, -- 是否启用定时同步：1 启用，0 禁用
	next_sync_time timestamp NULL, -- 下次定时同步时间
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL, -- 创建时间
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL, -- 更新时间
	CONSTRAINT t_user_sync_config_pkey PRIMARY KEY (user_id)
);
CREATE INDEX idx_user_sync_config_enabled_next_time ON public.t_user_sync_config USING btree (enabled, next_sync_time);
COMMENT ON TABLE public.t_user_sync_config IS '用户存储空间同步配置表，按用户记录定时同步的 cron 表达式与启用状态';

-- Column comments

COMMENT ON COLUMN public.t_user_sync_config.user_id IS '用户 ID';
COMMENT ON COLUMN public.t_user_sync_config.cron_expr IS '定时同步 cron 表达式';
COMMENT ON COLUMN public.t_user_sync_config.enabled IS '是否启用定时同步：1 启用，0 禁用';
COMMENT ON COLUMN public.t_user_sync_config.next_sync_time IS '下次定时同步时间';
COMMENT ON COLUMN public.t_user_sync_config.create_time IS '创建时间';
COMMENT ON COLUMN public.t_user_sync_config.update_time IS '更新时间';


-- public.t_user_sync_task 定义

-- Drop table

-- DROP TABLE t_user_sync_task;

CREATE TABLE t_user_sync_task (
	id varchar(13) NOT NULL, -- 任务 ID，13 位定长 base36 字符串
	user_id varchar(13) NOT NULL, -- 用户 ID
	"type" varchar(32) NOT NULL, -- 触发方式：manual 手动 / scheduled 定时
	status varchar(32) NOT NULL, -- 任务状态：PENDING / RUNNING / COMPLETED / FAILED / PARTIAL
	start_time timestamp NULL, -- 任务开始时间
	end_time timestamp NULL, -- 任务结束时间
	total_count int8 DEFAULT 0 NOT NULL, -- 扫描到的物理节点总数
	success_count int8 DEFAULT 0 NOT NULL, -- 成功同步节点数
	fail_count int8 DEFAULT 0 NOT NULL, -- 失败/跳过节点数
	error_msg varchar(4000) NULL, -- 错误信息
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL, -- 创建时间
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL, -- 更新时间
	delete_at int8 DEFAULT 0 NOT NULL, -- 逻辑删除时间戳：0 表示未删除
	CONSTRAINT t_user_sync_task_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_user_sync_task_user_create_time ON public.t_user_sync_task USING btree (user_id, create_time);
COMMENT ON TABLE public.t_user_sync_task IS '用户存储空间同步任务表，记录每次同步任务的执行状态与统计';

-- Column comments

COMMENT ON COLUMN public.t_user_sync_task.id IS '任务 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_user_sync_task.user_id IS '用户 ID';
COMMENT ON COLUMN public.t_user_sync_task."type" IS '触发方式：manual 手动 / scheduled 定时';
COMMENT ON COLUMN public.t_user_sync_task.status IS '任务状态：PENDING / RUNNING / COMPLETED / FAILED / PARTIAL';
COMMENT ON COLUMN public.t_user_sync_task.start_time IS '任务开始时间';
COMMENT ON COLUMN public.t_user_sync_task.end_time IS '任务结束时间';
COMMENT ON COLUMN public.t_user_sync_task.total_count IS '扫描到的物理节点总数';
COMMENT ON COLUMN public.t_user_sync_task.success_count IS '成功同步节点数';
COMMENT ON COLUMN public.t_user_sync_task.fail_count IS '失败/跳过节点数';
COMMENT ON COLUMN public.t_user_sync_task.error_msg IS '错误信息';
COMMENT ON COLUMN public.t_user_sync_task.create_time IS '创建时间';
COMMENT ON COLUMN public.t_user_sync_task.update_time IS '更新时间';
COMMENT ON COLUMN public.t_user_sync_task.delete_at IS '逻辑删除时间戳：0 表示未删除';


INSERT INTO public.t_role (id,code,"name",description,status,create_time,update_time,delete_at) VALUES
	 ('0000000000001','super_admin','超级管理员','系统内置超级管理员，拥有所有权限且不可删除',1,'2026-07-25 18:29:52.366246','2026-07-25 18:29:52.366246',0),
	 ('0000000000003','common_user','普通用户','拥有个人文件管理权限',1,'2026-07-25 18:29:52.366246','2026-07-25 18:29:52.366246',0);
INSERT INTO public.t_role_permission (id,role_id,create_time,update_time,permission_code) VALUES
	 ('0d680daf884c4','0000000000003','2026-07-25 18:29:52.366246','2026-07-25 18:29:52.366246','file:menu'),
	 ('f580c17f8c914','0000000000003','2026-07-25 18:29:52.366246','2026-07-25 18:29:52.366246','note:menu'),
	 ('2ba09ddd206d4','0000000000003','2026-07-25 18:29:52.366246','2026-07-25 18:29:52.366246','todo:menu');
