-- 影视功能增强
-- t_media_series：电视剧表（电视菜单分页/排序/搜索的直接查询对象，由扫描自动维护）
-- t_media_season：电视剧季表（由扫描自动维护）
-- t_media_item 增加 series_id / season_id 关联
-- 无存量数据迁移（系统尚未上线）

CREATE TABLE t_media_series (
	id varchar(13) NOT NULL, -- 剧 ID，13 位定长 base36 字符串
	user_id varchar(13) NOT NULL, -- 所属用户 ID
	series_name varchar(256) NOT NULL, -- 剧名
	metadata_id varchar(13) NULL, -- 匹配到的 TMDB 元数据 ID
	match_status varchar(16) DEFAULT 'unmatched' NOT NULL, -- 匹配状态：matched 自动匹配 / manual 手动修正 / unmatched 未识别
	min_file_last_modified int8 NULL, -- 剧内最早一集的文件修改时间（毫秒），用于"添加时间"排序
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_media_series_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_media_series_user_name ON public.t_media_series USING btree (user_id, series_name);
CREATE INDEX idx_media_series_metadata ON public.t_media_series USING btree (metadata_id);
COMMENT ON TABLE public.t_media_series IS '电视剧表，按用户+剧名唯一，由扫描自动维护';

COMMENT ON COLUMN public.t_media_series.id IS '剧 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_media_series.user_id IS '所属用户 ID';
COMMENT ON COLUMN public.t_media_series.series_name IS '剧名';
COMMENT ON COLUMN public.t_media_series.metadata_id IS '匹配到的 TMDB 元数据 ID';
COMMENT ON COLUMN public.t_media_series.match_status IS '匹配状态：matched 自动匹配 / manual 手动修正 / unmatched 未识别';
COMMENT ON COLUMN public.t_media_series.min_file_last_modified IS '剧内最早一集的文件修改时间（毫秒），用于添加时间排序';


CREATE TABLE t_media_season (
	id varchar(13) NOT NULL, -- 季 ID，13 位定长 base36 字符串
	series_id varchar(13) NOT NULL, -- 所属剧 ID
	season_no int4 NULL, -- 季号，为空表示未识别季
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_media_season_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_media_season_series_no ON public.t_media_season USING btree (series_id, season_no);
COMMENT ON TABLE public.t_media_season IS '电视剧季表，由扫描自动维护';

COMMENT ON COLUMN public.t_media_season.id IS '季 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_media_season.series_id IS '所属剧 ID';
COMMENT ON COLUMN public.t_media_season.season_no IS '季号，为空表示未识别季';


ALTER TABLE t_media_item ADD COLUMN series_id varchar(13) NULL; -- 所属剧 ID，仅 episode 有效
ALTER TABLE t_media_item ADD COLUMN season_id varchar(13) NULL; -- 所属季 ID，仅 episode 有效
COMMENT ON COLUMN public.t_media_item.series_id IS '所属剧 ID，仅 episode 有效';
COMMENT ON COLUMN public.t_media_item.season_id IS '所属季 ID，仅 episode 有效';
CREATE INDEX idx_media_item_series_id ON public.t_media_item USING btree (series_id);
CREATE INDEX idx_media_item_season_id ON public.t_media_item USING btree (season_id);

COMMENT ON COLUMN public.t_media_directory.last_scan_status IS '上次扫描状态：SCANNING 扫描中 / COMPLETED 完成 / FAILED 失败 / PARTIAL 部分成功';
