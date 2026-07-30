-- 扫描/削刮解耦重构
-- t_media_item 增加 file_hash（增量扫描 diff 依据）
-- t_media_directory 增加削刮状态字段
-- t_media_metadata 支持季/集元数据（season/episode 类型，依附于剧 tmdb_id + 季号 + 集号）
-- t_media_season 增加季元数据关联

ALTER TABLE t_media_item ADD COLUMN file_hash varchar(32) NULL; -- 相对路径+文件名+文件大小的哈希，用于增量扫描 diff，为空视为已变化
COMMENT ON COLUMN public.t_media_item.file_hash IS '相对路径（相对视频目录）+文件名+文件大小的哈希，用于增量扫描 diff，为空视为已变化';

ALTER TABLE t_media_directory ADD COLUMN last_scrape_time timestamp NULL; -- 上次削刮完成时间
ALTER TABLE t_media_directory ADD COLUMN last_scrape_status varchar(16) NULL; -- 上次削刮状态：SCRAPING 削刮中 / COMPLETED 完成 / FAILED 失败 / PARTIAL 部分成功
ALTER TABLE t_media_directory ADD COLUMN last_scrape_error varchar(1024) NULL; -- 上次削刮错误信息
COMMENT ON COLUMN public.t_media_directory.last_scrape_time IS '上次削刮完成时间';
COMMENT ON COLUMN public.t_media_directory.last_scrape_status IS '上次削刮状态：SCRAPING 削刮中 / COMPLETED 完成 / FAILED 失败 / PARTIAL 部分成功';
COMMENT ON COLUMN public.t_media_directory.last_scrape_error IS '上次削刮错误信息';

ALTER TABLE t_media_metadata ADD COLUMN series_tmdb_id int8 NULL; -- 所属剧 TMDB ID，仅 season/episode 类型有效
ALTER TABLE t_media_metadata ADD COLUMN season_no int4 NULL; -- 季号，仅 season/episode 类型有效
ALTER TABLE t_media_metadata ADD COLUMN episode_no int4 NULL; -- 集号，仅 episode 类型有效
COMMENT ON COLUMN public.t_media_metadata.series_tmdb_id IS '所属剧 TMDB ID，仅 season/episode 类型有效';
COMMENT ON COLUMN public.t_media_metadata.season_no IS '季号，仅 season/episode 类型有效';
COMMENT ON COLUMN public.t_media_metadata.episode_no IS '集号，仅 episode 类型有效';
COMMENT ON COLUMN public.t_media_metadata.tmdb_id IS 'TMDB 条目 ID，season/episode 类型为空';
COMMENT ON COLUMN public.t_media_metadata.media_type IS '类型：movie 电影 / tv 电视剧 / season 季 / episode 集';
COMMENT ON COLUMN public.t_media_metadata.poster_path IS '海报图缓存相对路径（系统数据目录下），episode 类型为集剧照';

-- 唯一键调整：season/episode 类型没有独立 tmdb_id，按 类型+剧tmdb_id+季号+集号 键控
DROP INDEX uk_media_metadata_tmdb;
ALTER TABLE t_media_metadata ALTER COLUMN tmdb_id DROP NOT NULL;
CREATE UNIQUE INDEX uk_media_metadata_tmdb ON public.t_media_metadata USING btree (media_type, tmdb_id, series_tmdb_id, season_no, episode_no);

ALTER TABLE t_media_season ADD COLUMN metadata_id varchar(13) NULL; -- 季元数据 ID
COMMENT ON COLUMN public.t_media_season.metadata_id IS '季元数据 ID';
