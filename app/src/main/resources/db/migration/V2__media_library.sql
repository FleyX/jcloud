-- 视频媒体库
-- t_media_directory：用户勾选的视频目录（关联虚拟文件树文件夹）
-- t_media_metadata：TMDB 元数据全局共享缓存
-- t_media_item：扫描出的媒体条目（电影/剧集/其他），含 ffprobe 探测结果与播放进度

CREATE TABLE t_media_directory (
	id varchar(13) NOT NULL, -- 目录 ID，13 位定长 base36 字符串
	user_id varchar(13) NOT NULL, -- 所属用户 ID
	file_node_id varchar(13) NOT NULL, -- 虚拟文件树文件夹节点 ID（本地或远程均可）
	"name" varchar(128) NOT NULL, -- 显示名，默认取文件夹名
	media_type varchar(16) NOT NULL, -- 媒体类型：movie 电影 / tv 电视 / other 其他
	scan_cron varchar(64) NULL, -- 定时重扫 cron 表达式，为空表示不定时重扫
	next_scan_time timestamp NULL, -- 下次定时重扫时间
	last_scan_time timestamp NULL, -- 上次扫描完成时间
	last_scan_status varchar(16) NULL, -- 上次扫描状态：COMPLETED / FAILED / PARTIAL
	last_scan_error varchar(1024) NULL, -- 上次扫描错误信息
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_media_directory_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_media_directory_user_id ON public.t_media_directory USING btree (user_id);
CREATE INDEX idx_media_directory_next_scan_time ON public.t_media_directory USING btree (next_scan_time);
CREATE UNIQUE INDEX uk_media_directory_user_node ON public.t_media_directory USING btree (user_id, file_node_id);
COMMENT ON TABLE public.t_media_directory IS '视频媒体库目录表';

COMMENT ON COLUMN public.t_media_directory.id IS '目录 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_media_directory.user_id IS '所属用户 ID';
COMMENT ON COLUMN public.t_media_directory.file_node_id IS '虚拟文件树文件夹节点 ID（本地或远程均可）';
COMMENT ON COLUMN public.t_media_directory."name" IS '显示名，默认取文件夹名';
COMMENT ON COLUMN public.t_media_directory.media_type IS '媒体类型：movie 电影 / tv 电视 / other 其他';
COMMENT ON COLUMN public.t_media_directory.scan_cron IS '定时重扫 cron 表达式，为空表示不定时重扫';
COMMENT ON COLUMN public.t_media_directory.next_scan_time IS '下次定时重扫时间';
COMMENT ON COLUMN public.t_media_directory.last_scan_time IS '上次扫描完成时间';
COMMENT ON COLUMN public.t_media_directory.last_scan_status IS '上次扫描状态：COMPLETED / FAILED / PARTIAL';
COMMENT ON COLUMN public.t_media_directory.last_scan_error IS '上次扫描错误信息';


CREATE TABLE t_media_metadata (
	id varchar(13) NOT NULL, -- 元数据 ID，13 位定长 base36 字符串
	tmdb_id int8 NOT NULL, -- TMDB 条目 ID
	media_type varchar(16) NOT NULL, -- 类型：movie 电影 / tv 电视剧
	title varchar(256) NULL, -- 标题（中文）
	original_title varchar(256) NULL, -- 原始标题
	overview text NULL, -- 简介
	poster_path varchar(256) NULL, -- 海报图缓存相对路径（系统数据目录下）
	backdrop_path varchar(256) NULL, -- 背景图缓存相对路径（系统数据目录下）
	release_date varchar(16) NULL, -- 上映/首播日期
	vote_average float8 NULL, -- TMDB 评分
	genres varchar(256) NULL, -- 类型列表，逗号分隔
	season_count int4 NULL, -- 季数，仅电视剧有效
	raw_json text NULL, -- TMDB 原始响应 JSON
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_media_metadata_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_media_metadata_tmdb ON public.t_media_metadata USING btree (tmdb_id, media_type);
COMMENT ON TABLE public.t_media_metadata IS 'TMDB 媒体元数据全局共享缓存表';

COMMENT ON COLUMN public.t_media_metadata.id IS '元数据 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_media_metadata.tmdb_id IS 'TMDB 条目 ID';
COMMENT ON COLUMN public.t_media_metadata.media_type IS '类型：movie 电影 / tv 电视剧';
COMMENT ON COLUMN public.t_media_metadata.title IS '标题（中文）';
COMMENT ON COLUMN public.t_media_metadata.original_title IS '原始标题';
COMMENT ON COLUMN public.t_media_metadata.overview IS '简介';
COMMENT ON COLUMN public.t_media_metadata.poster_path IS '海报图缓存相对路径（系统数据目录下）';
COMMENT ON COLUMN public.t_media_metadata.backdrop_path IS '背景图缓存相对路径（系统数据目录下）';
COMMENT ON COLUMN public.t_media_metadata.release_date IS '上映/首播日期';
COMMENT ON COLUMN public.t_media_metadata.vote_average IS 'TMDB 评分';
COMMENT ON COLUMN public.t_media_metadata.genres IS '类型列表，逗号分隔';
COMMENT ON COLUMN public.t_media_metadata.season_count IS '季数，仅电视剧有效';
COMMENT ON COLUMN public.t_media_metadata.raw_json IS 'TMDB 原始响应 JSON';


CREATE TABLE t_media_item (
	id varchar(13) NOT NULL, -- 条目 ID，13 位定长 base36 字符串
	user_id varchar(13) NOT NULL, -- 所属用户 ID
	directory_id varchar(13) NOT NULL, -- 所属视频目录 ID
	file_node_id varchar(13) NOT NULL, -- 关联文件节点 ID
	item_type varchar(16) NOT NULL, -- 条目类型：movie 电影 / episode 剧集 / other 其他
	metadata_id varchar(13) NULL, -- 匹配到的元数据 ID（movie/episode 有效）
	series_name varchar(256) NULL, -- 所属剧名，仅 episode 有效
	season_no int4 NULL, -- 季号，仅 episode 有效
	episode_no int4 NULL, -- 集号，仅 episode 有效
	match_status varchar(16) DEFAULT 'unmatched' NOT NULL, -- 匹配状态：matched 自动匹配 / manual 手动修正 / unmatched 未识别 / none 无需匹配
	duration_ms int8 NULL, -- 时长（毫秒），ffprobe 探测
	container varchar(16) NULL, -- 封装格式，ffprobe 探测
	video_codec varchar(32) NULL, -- 视频编码，ffprobe 探测
	audio_codec varchar(32) NULL, -- 音频编码，ffprobe 探测
	width int4 NULL, -- 视频宽度，ffprobe 探测
	height int4 NULL, -- 视频高度，ffprobe 探测
	file_size int8 NULL, -- 扫描时文件大小（字节），用于重扫 diff
	file_last_modified int8 NULL, -- 扫描时文件修改时间（毫秒），用于重扫 diff
	progress_ms int8 DEFAULT 0 NOT NULL, -- 播放进度（毫秒）
	last_play_time timestamp NULL, -- 最近播放时间
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_media_item_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_media_item_user_type ON public.t_media_item USING btree (user_id, item_type);
CREATE INDEX idx_media_item_directory_id ON public.t_media_item USING btree (directory_id);
CREATE INDEX idx_media_item_metadata_id ON public.t_media_item USING btree (metadata_id);
CREATE INDEX idx_media_item_series ON public.t_media_item USING btree (user_id, series_name);
CREATE UNIQUE INDEX uk_media_directory_node ON public.t_media_item USING btree (directory_id, file_node_id);
COMMENT ON TABLE public.t_media_item IS '媒体条目表，记录扫描出的视频文件及其探测/匹配/播放进度信息';

COMMENT ON COLUMN public.t_media_item.id IS '条目 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_media_item.user_id IS '所属用户 ID';
COMMENT ON COLUMN public.t_media_item.directory_id IS '所属视频目录 ID';
COMMENT ON COLUMN public.t_media_item.file_node_id IS '关联文件节点 ID';
COMMENT ON COLUMN public.t_media_item.item_type IS '条目类型：movie 电影 / episode 剧集 / other 其他';
COMMENT ON COLUMN public.t_media_item.metadata_id IS '匹配到的元数据 ID（movie/episode 有效）';
COMMENT ON COLUMN public.t_media_item.series_name IS '所属剧名，仅 episode 有效';
COMMENT ON COLUMN public.t_media_item.season_no IS '季号，仅 episode 有效';
COMMENT ON COLUMN public.t_media_item.episode_no IS '集号，仅 episode 有效';
COMMENT ON COLUMN public.t_media_item.match_status IS '匹配状态：matched 自动匹配 / manual 手动修正 / unmatched 未识别 / none 无需匹配';
COMMENT ON COLUMN public.t_media_item.duration_ms IS '时长（毫秒），ffprobe 探测';
COMMENT ON COLUMN public.t_media_item.container IS '封装格式，ffprobe 探测';
COMMENT ON COLUMN public.t_media_item.video_codec IS '视频编码，ffprobe 探测';
COMMENT ON COLUMN public.t_media_item.audio_codec IS '音频编码，ffprobe 探测';
COMMENT ON COLUMN public.t_media_item.width IS '视频宽度，ffprobe 探测';
COMMENT ON COLUMN public.t_media_item.height IS '视频高度，ffprobe 探测';
COMMENT ON COLUMN public.t_media_item.file_size IS '扫描时文件大小（字节），用于重扫 diff';
COMMENT ON COLUMN public.t_media_item.file_last_modified IS '扫描时文件修改时间（毫秒），用于重扫 diff';
COMMENT ON COLUMN public.t_media_item.progress_ms IS '播放进度（毫秒）';
COMMENT ON COLUMN public.t_media_item.last_play_time IS '最近播放时间';

-- 默认普通用户角色授予影视媒体库权限
INSERT INTO public.t_role_permission (id,role_id,create_time,update_time,permission_code)
SELECT '0m3d1ap3rm001','0000000000003',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'media:menu'
WHERE NOT EXISTS (
	SELECT 1 FROM public.t_role_permission WHERE role_id = '0000000000003' AND permission_code = 'media:menu'
);
