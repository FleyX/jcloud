-- 媒体库模型重构：视频目录升级为媒体库，支持多来源目录
-- t_media_directory_source：媒体库来源目录子表
-- t_media_directory 删除 file_node_id（迁往来源目录子表）
-- t_media_item 增加 source_id 关联来源目录
-- 无存量数据迁移（系统尚未上线）

CREATE TABLE t_media_directory_source (
	id varchar(13) NOT NULL, -- 来源目录 ID，13 位定长 base36 字符串
	directory_id varchar(13) NOT NULL, -- 所属媒体库 ID
	file_node_id varchar(13) NOT NULL, -- 虚拟文件树文件夹节点 ID（本地或远程均可）
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_media_directory_source_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_media_source_dir_node ON public.t_media_directory_source USING btree (directory_id, file_node_id);
CREATE INDEX idx_media_source_file_node ON public.t_media_directory_source USING btree (file_node_id);
COMMENT ON TABLE public.t_media_directory_source IS '媒体库来源目录表';

COMMENT ON COLUMN public.t_media_directory_source.id IS '来源目录 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_media_directory_source.directory_id IS '所属媒体库 ID';
COMMENT ON COLUMN public.t_media_directory_source.file_node_id IS '虚拟文件树文件夹节点 ID（本地或远程均可）';

DROP INDEX uk_media_directory_user_node;
ALTER TABLE t_media_directory DROP COLUMN file_node_id;
COMMENT ON TABLE public.t_media_directory IS '媒体库表';
COMMENT ON COLUMN public.t_media_directory.id IS '媒体库 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_media_directory."name" IS '媒体库显示名';

ALTER TABLE t_media_item ADD COLUMN source_id varchar(13) NULL; -- 所属来源目录 ID
COMMENT ON COLUMN public.t_media_item.source_id IS '所属来源目录 ID';
COMMENT ON COLUMN public.t_media_item.directory_id IS '所属媒体库 ID';
COMMENT ON COLUMN public.t_media_item.file_hash IS '来源目录ID+相对路径（相对来源目录）+文件名+文件大小的哈希，用于增量扫描 diff，为空视为已变化';
CREATE INDEX idx_media_item_source_id ON public.t_media_item USING btree (source_id);

-- 继续观看/接下来 按用户+最近播放时间查询
CREATE INDEX idx_media_item_last_play ON public.t_media_item USING btree (user_id, last_play_time);
