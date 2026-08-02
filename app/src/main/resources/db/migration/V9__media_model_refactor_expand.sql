-- 媒体模型重构 expand 阶段（issue #16，ADR 0021）
-- 新建 8 张新表：t_media_movie / t_media_movie_file / t_media_episode / t_media_episode_file / t_media_other 使用最终名；
-- t_media_series_v2 / t_media_season_v2 / t_media_metadata_v2 为与旧表并行的临时命名（旧 t_media_series / t_media_season /
-- t_media_metadata 仍存在），contract 阶段（issue #21）drop 旧四表后再 rename 回最终名。
-- 本迁移仅建新表，旧四表与旧代码路径完全不动，应用行为不变。
-- 字段清单以 issue #15 已人工确认的 DDL 为准：无外键，全部字段带注释，锚定字段（folder_node_id / file_node_id）与
-- (season_id, episode_no) 加唯一约束，元数据表含 owner_type + owner_id 反向指针（一对一唯一约束）。

-- ===================== 电影（标题级，文件夹锚定） =====================
CREATE TABLE t_media_movie (
	id varchar(13) NOT NULL, -- 电影 ID，13 位定长 base36 字符串
	user_id varchar(13) NOT NULL, -- 所属用户 ID（冗余存储，便于按用户清理）
	directory_id varchar(13) NOT NULL, -- 所属媒体库 ID
	source_id varchar(13) NULL, -- 所属来源目录 ID
	folder_node_id varchar(13) NOT NULL, -- 锚：电影文件夹的虚拟文件树节点 ID（唯一）
	title varchar(256) NOT NULL, -- 电影标题（默认取文件夹名）
	release_year int4 NULL, -- 文件夹名解析出的发行年份，用于 TMDB 匹配消歧
	metadata_id varchar(13) NULL, -- 匹配到的元数据 ID，未匹配时为空
	match_status varchar(16) DEFAULT 'unmatched' NOT NULL, -- 匹配状态：matched 自动匹配 / manual 手动修正 / unmatched 未识别
	metadata_complete bool DEFAULT false NOT NULL, -- 元数据完整性标志：必备字段（标题/简介/海报/发行日期/评分）是否齐全
	progress_ms int8 DEFAULT 0 NOT NULL, -- 播放进度（毫秒），多版本共享
	last_play_time timestamp NULL, -- 最近播放时间
	last_play_file_id varchar(13) NULL, -- 最近播放的电影文件明细 ID，用于续播定位版本
	scan_time timestamp NULL, -- 批次扫描时间（扫描开始时刻），用于批次清理判定
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_media_movie_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_media_movie_folder_node ON public.t_media_movie USING btree (folder_node_id);
CREATE INDEX idx_media_movie_user_id ON public.t_media_movie USING btree (user_id);
CREATE INDEX idx_media_movie_directory_id ON public.t_media_movie USING btree (directory_id);
CREATE INDEX idx_media_movie_source_id ON public.t_media_movie USING btree (source_id);
CREATE INDEX idx_media_movie_metadata_id ON public.t_media_movie USING btree (metadata_id);
CREATE INDEX idx_media_movie_last_play ON public.t_media_movie USING btree (user_id, last_play_time);
COMMENT ON TABLE public.t_media_movie IS '电影表（标题级，文件夹锚定），同一电影文件夹下多个视频聚合为多版本';

COMMENT ON COLUMN public.t_media_movie.id IS '电影 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_media_movie.user_id IS '所属用户 ID（冗余存储，便于按用户清理）';
COMMENT ON COLUMN public.t_media_movie.directory_id IS '所属媒体库 ID';
COMMENT ON COLUMN public.t_media_movie.source_id IS '所属来源目录 ID';
COMMENT ON COLUMN public.t_media_movie.folder_node_id IS '锚：电影文件夹的虚拟文件树节点 ID（唯一）';
COMMENT ON COLUMN public.t_media_movie.title IS '电影标题（默认取文件夹名）';
COMMENT ON COLUMN public.t_media_movie.release_year IS '文件夹名解析出的发行年份，用于 TMDB 匹配消歧';
COMMENT ON COLUMN public.t_media_movie.metadata_id IS '匹配到的元数据 ID，未匹配时为空';
COMMENT ON COLUMN public.t_media_movie.match_status IS '匹配状态：matched 自动匹配 / manual 手动修正 / unmatched 未识别';
COMMENT ON COLUMN public.t_media_movie.metadata_complete IS '元数据完整性标志：必备字段（标题/简介/海报/发行日期/评分）是否齐全';
COMMENT ON COLUMN public.t_media_movie.progress_ms IS '播放进度（毫秒），多版本共享';
COMMENT ON COLUMN public.t_media_movie.last_play_time IS '最近播放时间';
COMMENT ON COLUMN public.t_media_movie.last_play_file_id IS '最近播放的电影文件明细 ID，用于续播定位版本';
COMMENT ON COLUMN public.t_media_movie.scan_time IS '批次扫描时间（扫描开始时刻），用于批次清理判定';


-- ===================== 电影文件明细（文件级，文件锚定） =====================
CREATE TABLE t_media_movie_file (
	id varchar(13) NOT NULL, -- 电影文件明细 ID，13 位定长 base36 字符串
	movie_id varchar(13) NOT NULL, -- 所属电影 ID
	file_node_id varchar(13) NOT NULL, -- 锚：视频文件的虚拟文件树节点 ID（唯一）
	file_hash varchar(32) NULL, -- 来源目录ID+相对路径（相对来源目录）+文件名+文件大小的哈希，用于增量扫描 diff，为空视为已变化
	duration_ms int8 NULL, -- 时长（毫秒），ffprobe 探测
	container varchar(16) NULL, -- 封装格式，ffprobe 探测
	video_codec varchar(32) NULL, -- 视频编码，ffprobe 探测
	audio_codec varchar(32) NULL, -- 音频编码，ffprobe 探测
	width int4 NULL, -- 视频宽度，ffprobe 探测
	height int4 NULL, -- 视频高度，ffprobe 探测
	file_size int8 NULL, -- 扫描时文件大小（字节），用于重扫 diff
	file_last_modified int8 NULL, -- 扫描时文件修改时间（毫秒），用于重扫 diff
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_media_movie_file_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_media_movie_file_node ON public.t_media_movie_file USING btree (file_node_id);
CREATE INDEX idx_media_movie_file_movie_id ON public.t_media_movie_file USING btree (movie_id);
COMMENT ON TABLE public.t_media_movie_file IS '电影文件明细表，一行一个视频文件（多版本），承载 ffprobe 探测结果与文件变更哈希';

COMMENT ON COLUMN public.t_media_movie_file.id IS '电影文件明细 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_media_movie_file.movie_id IS '所属电影 ID';
COMMENT ON COLUMN public.t_media_movie_file.file_node_id IS '锚：视频文件的虚拟文件树节点 ID（唯一）';
COMMENT ON COLUMN public.t_media_movie_file.file_hash IS '来源目录ID+相对路径（相对来源目录）+文件名+文件大小的哈希，用于增量扫描 diff，为空视为已变化';
COMMENT ON COLUMN public.t_media_movie_file.duration_ms IS '时长（毫秒），ffprobe 探测';
COMMENT ON COLUMN public.t_media_movie_file.container IS '封装格式，ffprobe 探测';
COMMENT ON COLUMN public.t_media_movie_file.video_codec IS '视频编码，ffprobe 探测';
COMMENT ON COLUMN public.t_media_movie_file.audio_codec IS '音频编码，ffprobe 探测';
COMMENT ON COLUMN public.t_media_movie_file.width IS '视频宽度，ffprobe 探测';
COMMENT ON COLUMN public.t_media_movie_file.height IS '视频高度，ffprobe 探测';
COMMENT ON COLUMN public.t_media_movie_file.file_size IS '扫描时文件大小（字节），用于重扫 diff';
COMMENT ON COLUMN public.t_media_movie_file.file_last_modified IS '扫描时文件修改时间（毫秒），用于重扫 diff';


-- ===================== 剧集（标题级，文件夹锚定，并行新表） =====================
CREATE TABLE t_media_series_v2 (
	id varchar(13) NOT NULL, -- 剧 ID，13 位定长 base36 字符串
	user_id varchar(13) NOT NULL, -- 所属用户 ID（冗余存储，便于按用户清理）
	directory_id varchar(13) NOT NULL, -- 所属媒体库 ID
	source_id varchar(13) NULL, -- 所属来源目录 ID
	folder_node_id varchar(13) NOT NULL, -- 锚：剧文件夹的虚拟文件树节点 ID（唯一）
	series_name varchar(256) NOT NULL, -- 剧名（默认取文件夹名）
	release_year int4 NULL, -- 剧文件夹名解析出的首播年份，用于 TMDB 匹配消歧
	metadata_id varchar(13) NULL, -- 匹配到的元数据 ID，未匹配时为空
	match_status varchar(16) DEFAULT 'unmatched' NOT NULL, -- 匹配状态：matched 自动匹配 / manual 手动修正 / unmatched 未识别
	metadata_complete bool DEFAULT false NOT NULL, -- 元数据完整性标志（聚合语义：剧自身且所有季、集均完整才为完整）
	min_file_last_modified int8 NULL, -- 剧内最早一集的文件修改时间（毫秒），用于添加时间排序
	scan_time timestamp NULL, -- 批次扫描时间（扫描开始时刻），用于批次清理判定
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_media_series_v2_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_media_series_v2_folder_node ON public.t_media_series_v2 USING btree (folder_node_id);
CREATE INDEX idx_media_series_v2_user_id ON public.t_media_series_v2 USING btree (user_id);
CREATE INDEX idx_media_series_v2_directory_id ON public.t_media_series_v2 USING btree (directory_id);
CREATE INDEX idx_media_series_v2_source_id ON public.t_media_series_v2 USING btree (source_id);
CREATE INDEX idx_media_series_v2_metadata_id ON public.t_media_series_v2 USING btree (metadata_id);
COMMENT ON TABLE public.t_media_series_v2 IS '剧集表（标题级，文件夹锚定，库级归属），expand 阶段并行新表，contract 阶段 rename 为 t_media_series';

COMMENT ON COLUMN public.t_media_series_v2.id IS '剧 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_media_series_v2.user_id IS '所属用户 ID（冗余存储，便于按用户清理）';
COMMENT ON COLUMN public.t_media_series_v2.directory_id IS '所属媒体库 ID';
COMMENT ON COLUMN public.t_media_series_v2.source_id IS '所属来源目录 ID';
COMMENT ON COLUMN public.t_media_series_v2.folder_node_id IS '锚：剧文件夹的虚拟文件树节点 ID（唯一）';
COMMENT ON COLUMN public.t_media_series_v2.series_name IS '剧名（默认取文件夹名）';
COMMENT ON COLUMN public.t_media_series_v2.release_year IS '剧文件夹名解析出的首播年份，用于 TMDB 匹配消歧';
COMMENT ON COLUMN public.t_media_series_v2.metadata_id IS '匹配到的元数据 ID，未匹配时为空';
COMMENT ON COLUMN public.t_media_series_v2.match_status IS '匹配状态：matched 自动匹配 / manual 手动修正 / unmatched 未识别';
COMMENT ON COLUMN public.t_media_series_v2.metadata_complete IS '元数据完整性标志（聚合语义：剧自身且所有季、集均完整才为完整）';
COMMENT ON COLUMN public.t_media_series_v2.min_file_last_modified IS '剧内最早一集的文件修改时间（毫秒），用于添加时间排序';
COMMENT ON COLUMN public.t_media_series_v2.scan_time IS '批次扫描时间（扫描开始时刻），用于批次清理判定';


-- ===================== 季（文件夹锚定，并行新表） =====================
CREATE TABLE t_media_season_v2 (
	id varchar(13) NOT NULL, -- 季 ID，13 位定长 base36 字符串
	series_id varchar(13) NOT NULL, -- 所属剧 ID
	folder_node_id varchar(13) NOT NULL, -- 锚：季文件夹的虚拟文件树节点 ID（唯一）
	season_no int4 NULL, -- 季号，为空表示未识别季
	metadata_id varchar(13) NULL, -- 季元数据 ID，未匹配时为空
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_media_season_v2_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_media_season_v2_folder_node ON public.t_media_season_v2 USING btree (folder_node_id);
CREATE INDEX idx_media_season_v2_series_id ON public.t_media_season_v2 USING btree (series_id);
COMMENT ON TABLE public.t_media_season_v2 IS '电视剧季表（文件夹锚定），expand 阶段并行新表，contract 阶段 rename 为 t_media_season';

COMMENT ON COLUMN public.t_media_season_v2.id IS '季 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_media_season_v2.series_id IS '所属剧 ID';
COMMENT ON COLUMN public.t_media_season_v2.folder_node_id IS '锚：季文件夹的虚拟文件树节点 ID（唯一）';
COMMENT ON COLUMN public.t_media_season_v2.season_no IS '季号，为空表示未识别季';
COMMENT ON COLUMN public.t_media_season_v2.metadata_id IS '季元数据 ID，未匹配时为空';


-- ===================== 集（标题级，(season_id, episode_no) 唯一键） =====================
CREATE TABLE t_media_episode (
	id varchar(13) NOT NULL, -- 集 ID，13 位定长 base36 字符串
	series_id varchar(13) NOT NULL, -- 所属剧 ID
	season_id varchar(13) NOT NULL, -- 所属季 ID
	episode_no int4 NOT NULL, -- 集号
	metadata_id varchar(13) NULL, -- 集元数据 ID（由剧级匹配派生），未匹配时为空
	progress_ms int8 DEFAULT 0 NOT NULL, -- 播放进度（毫秒），多版本共享
	last_play_time timestamp NULL, -- 最近播放时间
	last_play_file_id varchar(13) NULL, -- 最近播放的集文件明细 ID，用于续播定位版本
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_media_episode_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_media_episode_season_no ON public.t_media_episode USING btree (season_id, episode_no);
CREATE INDEX idx_media_episode_series_id ON public.t_media_episode USING btree (series_id);
COMMENT ON TABLE public.t_media_episode IS '集表（标题级），以 (season_id, episode_no) 唯一键为身份，不挂匹配状态';

COMMENT ON COLUMN public.t_media_episode.id IS '集 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_media_episode.series_id IS '所属剧 ID';
COMMENT ON COLUMN public.t_media_episode.season_id IS '所属季 ID';
COMMENT ON COLUMN public.t_media_episode.episode_no IS '集号';
COMMENT ON COLUMN public.t_media_episode.metadata_id IS '集元数据 ID（由剧级匹配派生），未匹配时为空';
COMMENT ON COLUMN public.t_media_episode.progress_ms IS '播放进度（毫秒），多版本共享';
COMMENT ON COLUMN public.t_media_episode.last_play_time IS '最近播放时间';
COMMENT ON COLUMN public.t_media_episode.last_play_file_id IS '最近播放的集文件明细 ID，用于续播定位版本';


-- ===================== 集文件明细（文件级，文件锚定） =====================
CREATE TABLE t_media_episode_file (
	id varchar(13) NOT NULL, -- 集文件明细 ID，13 位定长 base36 字符串
	episode_id varchar(13) NOT NULL, -- 所属集 ID
	file_node_id varchar(13) NOT NULL, -- 锚：视频文件的虚拟文件树节点 ID（唯一）
	file_hash varchar(32) NULL, -- 来源目录ID+相对路径（相对来源目录）+文件名+文件大小的哈希，用于增量扫描 diff，为空视为已变化
	duration_ms int8 NULL, -- 时长（毫秒），ffprobe 探测
	container varchar(16) NULL, -- 封装格式，ffprobe 探测
	video_codec varchar(32) NULL, -- 视频编码，ffprobe 探测
	audio_codec varchar(32) NULL, -- 音频编码，ffprobe 探测
	width int4 NULL, -- 视频宽度，ffprobe 探测
	height int4 NULL, -- 视频高度，ffprobe 探测
	file_size int8 NULL, -- 扫描时文件大小（字节），用于重扫 diff
	file_last_modified int8 NULL, -- 扫描时文件修改时间（毫秒），用于重扫 diff
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_media_episode_file_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_media_episode_file_node ON public.t_media_episode_file USING btree (file_node_id);
CREATE INDEX idx_media_episode_file_episode_id ON public.t_media_episode_file USING btree (episode_id);
COMMENT ON TABLE public.t_media_episode_file IS '集文件明细表，一行一个视频文件（多版本），承载 ffprobe 探测结果与文件变更哈希';

COMMENT ON COLUMN public.t_media_episode_file.id IS '集文件明细 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_media_episode_file.episode_id IS '所属集 ID';
COMMENT ON COLUMN public.t_media_episode_file.file_node_id IS '锚：视频文件的虚拟文件树节点 ID（唯一）';
COMMENT ON COLUMN public.t_media_episode_file.file_hash IS '来源目录ID+相对路径（相对来源目录）+文件名+文件大小的哈希，用于增量扫描 diff，为空视为已变化';
COMMENT ON COLUMN public.t_media_episode_file.duration_ms IS '时长（毫秒），ffprobe 探测';
COMMENT ON COLUMN public.t_media_episode_file.container IS '封装格式，ffprobe 探测';
COMMENT ON COLUMN public.t_media_episode_file.video_codec IS '视频编码，ffprobe 探测';
COMMENT ON COLUMN public.t_media_episode_file.audio_codec IS '音频编码，ffprobe 探测';
COMMENT ON COLUMN public.t_media_episode_file.width IS '视频宽度，ffprobe 探测';
COMMENT ON COLUMN public.t_media_episode_file.height IS '视频高度，ffprobe 探测';
COMMENT ON COLUMN public.t_media_episode_file.file_size IS '扫描时文件大小（字节），用于重扫 diff';
COMMENT ON COLUMN public.t_media_episode_file.file_last_modified IS '扫描时文件修改时间（毫秒），用于重扫 diff';


-- ===================== 其他（文件级，文件锚定） =====================
CREATE TABLE t_media_other (
	id varchar(13) NOT NULL, -- 其他条目 ID，13 位定长 base36 字符串
	user_id varchar(13) NOT NULL, -- 所属用户 ID（冗余存储，便于按用户清理）
	directory_id varchar(13) NOT NULL, -- 所属媒体库 ID
	source_id varchar(13) NULL, -- 所属来源目录 ID
	file_node_id varchar(13) NOT NULL, -- 锚：视频文件的虚拟文件树节点 ID（唯一）
	"name" varchar(256) NOT NULL, -- 条目名称（默认取文件名）
	file_hash varchar(32) NULL, -- 来源目录ID+相对路径（相对来源目录）+文件名+文件大小的哈希，用于增量扫描 diff，为空视为已变化
	duration_ms int8 NULL, -- 时长（毫秒），ffprobe 探测
	container varchar(16) NULL, -- 封装格式，ffprobe 探测
	video_codec varchar(32) NULL, -- 视频编码，ffprobe 探测
	audio_codec varchar(32) NULL, -- 音频编码，ffprobe 探测
	width int4 NULL, -- 视频宽度，ffprobe 探测
	height int4 NULL, -- 视频高度，ffprobe 探测
	thumbnail_path varchar(256) NULL, -- 缩略图路径（ffmpeg 截图）
	progress_ms int8 DEFAULT 0 NOT NULL, -- 播放进度（毫秒）
	last_play_time timestamp NULL, -- 最近播放时间
	scan_time timestamp NULL, -- 批次扫描时间（扫描开始时刻），用于批次清理判定
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_media_other_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_media_other_file_node ON public.t_media_other USING btree (file_node_id);
CREATE INDEX idx_media_other_user_id ON public.t_media_other USING btree (user_id);
CREATE INDEX idx_media_other_directory_id ON public.t_media_other USING btree (directory_id);
CREATE INDEX idx_media_other_source_id ON public.t_media_other USING btree (source_id);
CREATE INDEX idx_media_other_last_play ON public.t_media_other USING btree (user_id, last_play_time);
COMMENT ON TABLE public.t_media_other IS '其他媒体库条目表（文件级，每个视频文件一行），不刮元数据，缩略图网格展示';

COMMENT ON COLUMN public.t_media_other.id IS '其他条目 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_media_other.user_id IS '所属用户 ID（冗余存储，便于按用户清理）';
COMMENT ON COLUMN public.t_media_other.directory_id IS '所属媒体库 ID';
COMMENT ON COLUMN public.t_media_other.source_id IS '所属来源目录 ID';
COMMENT ON COLUMN public.t_media_other.file_node_id IS '锚：视频文件的虚拟文件树节点 ID（唯一）';
COMMENT ON COLUMN public.t_media_other."name" IS '条目名称（默认取文件名）';
COMMENT ON COLUMN public.t_media_other.file_hash IS '来源目录ID+相对路径（相对来源目录）+文件名+文件大小的哈希，用于增量扫描 diff，为空视为已变化';
COMMENT ON COLUMN public.t_media_other.duration_ms IS '时长（毫秒），ffprobe 探测';
COMMENT ON COLUMN public.t_media_other.container IS '封装格式，ffprobe 探测';
COMMENT ON COLUMN public.t_media_other.video_codec IS '视频编码，ffprobe 探测';
COMMENT ON COLUMN public.t_media_other.audio_codec IS '音频编码，ffprobe 探测';
COMMENT ON COLUMN public.t_media_other.width IS '视频宽度，ffprobe 探测';
COMMENT ON COLUMN public.t_media_other.height IS '视频高度，ffprobe 探测';
COMMENT ON COLUMN public.t_media_other.thumbnail_path IS '缩略图路径（ffmpeg 截图）';
COMMENT ON COLUMN public.t_media_other.progress_ms IS '播放进度（毫秒）';
COMMENT ON COLUMN public.t_media_other.last_play_time IS '最近播放时间';
COMMENT ON COLUMN public.t_media_other.scan_time IS '批次扫描时间（扫描开始时刻），用于批次清理判定';


-- ===================== 元数据（按用户隔离，owner 反向指针一对一，并行新表） =====================
CREATE TABLE t_media_metadata_v2 (
	id varchar(13) NOT NULL, -- 元数据 ID，13 位定长 base36 字符串
	user_id varchar(13) NOT NULL, -- 所属用户 ID（元数据按用户隔离）
	owner_type varchar(16) NOT NULL, -- 归属实体类型：movie 电影 / series 剧集 / season 季 / episode 集
	owner_id varchar(13) NOT NULL, -- 归属实体 ID（反向指针，用于级联校验、孤儿排查与清理）
	tmdb_id int8 NULL, -- TMDB 条目 ID，local_nfo 来源可为空
	source varchar(16) NOT NULL, -- 来源：local_nfo 本地NFO / tmdb
	title varchar(256) NULL, -- 标题（中文）
	original_title varchar(256) NULL, -- 原始标题
	overview text NULL, -- 简介
	release_date varchar(16) NULL, -- 上映/首播日期
	vote_average float8 NULL, -- TMDB 评分
	poster_file_node_id varchar(13) NULL, -- 海报图文件节点 ID（视频目录下 poster.jpg）
	backdrop_file_node_id varchar(13) NULL, -- 背景图文件节点 ID（视频目录下 fanart.jpg）
	create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT t_media_metadata_v2_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_media_metadata_v2_owner ON public.t_media_metadata_v2 USING btree (owner_type, owner_id);
CREATE INDEX idx_media_metadata_v2_user_id ON public.t_media_metadata_v2 USING btree (user_id);
CREATE INDEX idx_media_metadata_v2_user_tmdb ON public.t_media_metadata_v2 USING btree (user_id, tmdb_id);
COMMENT ON TABLE public.t_media_metadata_v2 IS '媒体元数据表，按用户隔离，与电影/剧集/季/集一对一绑定（owner_type + owner_id 反向指针），expand 阶段并行新表，contract 阶段 rename 为 t_media_metadata';

COMMENT ON COLUMN public.t_media_metadata_v2.id IS '元数据 ID，13 位定长 base36 字符串';
COMMENT ON COLUMN public.t_media_metadata_v2.user_id IS '所属用户 ID（元数据按用户隔离）';
COMMENT ON COLUMN public.t_media_metadata_v2.owner_type IS '归属实体类型：movie 电影 / series 剧集 / season 季 / episode 集';
COMMENT ON COLUMN public.t_media_metadata_v2.owner_id IS '归属实体 ID（反向指针，用于级联校验、孤儿排查与清理）';
COMMENT ON COLUMN public.t_media_metadata_v2.tmdb_id IS 'TMDB 条目 ID，local_nfo 来源可为空';
COMMENT ON COLUMN public.t_media_metadata_v2.source IS '来源：local_nfo 本地NFO / tmdb';
COMMENT ON COLUMN public.t_media_metadata_v2.title IS '标题（中文）';
COMMENT ON COLUMN public.t_media_metadata_v2.original_title IS '原始标题';
COMMENT ON COLUMN public.t_media_metadata_v2.overview IS '简介';
COMMENT ON COLUMN public.t_media_metadata_v2.release_date IS '上映/首播日期';
COMMENT ON COLUMN public.t_media_metadata_v2.vote_average IS 'TMDB 评分';
COMMENT ON COLUMN public.t_media_metadata_v2.poster_file_node_id IS '海报图文件节点 ID（视频目录下 poster.jpg）';
COMMENT ON COLUMN public.t_media_metadata_v2.backdrop_file_node_id IS '背景图文件节点 ID（视频目录下 fanart.jpg）';
