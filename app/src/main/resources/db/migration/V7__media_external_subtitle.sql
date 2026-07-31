-- 外部字幕
-- t_media_subtitle：与视频文件同目录、主文件名前缀匹配的外部字幕文件关联记录，扫描时重建

CREATE TABLE t_media_subtitle (
	id varchar(13) NOT NULL, -- 字幕记录 ID，13 位定长 base36 字符串
	item_id varchar(13) NOT NULL, -- 所属媒体条目 ID
	file_node_id varchar(13) NOT NULL, -- 字幕文件对应的文件节点 ID
	format varchar(8) NOT NULL, -- 字幕格式：srt / ass / ssa / vtt
	label varchar(128) NULL, -- 展示标签，从文件名后缀解析（如 chs、简体、English）
	is_default bool NOT NULL DEFAULT false, -- 是否默认字幕（文件名含 .default 后缀）
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_media_subtitle_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_media_subtitle_item_id ON public.t_media_subtitle USING btree (item_id);
CREATE UNIQUE INDEX uk_media_subtitle_item_node ON public.t_media_subtitle USING btree (item_id, file_node_id);
COMMENT ON TABLE public.t_media_subtitle IS '媒体条目外部字幕关联表，扫描时按同目录前缀匹配规则重建';

COMMENT ON COLUMN public.t_media_subtitle.id IS '字幕记录 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_media_subtitle.item_id IS '所属媒体条目 ID';
COMMENT ON COLUMN public.t_media_subtitle.file_node_id IS '字幕文件对应的文件节点 ID';
COMMENT ON COLUMN public.t_media_subtitle.format IS '字幕格式：srt / ass / ssa / vtt';
COMMENT ON COLUMN public.t_media_subtitle.label IS '展示标签，从文件名后缀解析（如 chs、简体、English）';
COMMENT ON COLUMN public.t_media_subtitle.is_default IS '是否默认字幕（文件名含 .default 后缀）';
