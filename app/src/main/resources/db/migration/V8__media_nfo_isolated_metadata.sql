-- 影视元数据模型重构：NFO 机制 + 元数据按用户隔离（ADR 0020）
-- t_media_metadata：从全局共享缓存改为按用户隔离，绑定条目/剧/季；图片改存 FileNode 引用
-- 项目未上线，不做历史数据迁移：旧共享缓存元数据直接清除，条目/剧/季回退为未匹配待重新削刮

UPDATE t_media_item SET metadata_id = NULL, match_status = 'unmatched' WHERE metadata_id IS NOT NULL;
UPDATE t_media_series SET metadata_id = NULL, match_status = 'unmatched' WHERE metadata_id IS NOT NULL;
UPDATE t_media_season SET metadata_id = NULL WHERE metadata_id IS NOT NULL;
DELETE FROM t_media_metadata;

-- 新增字段
ALTER TABLE t_media_metadata ADD COLUMN user_id varchar(13) NOT NULL; -- 所属用户 ID（隔离维度）
ALTER TABLE t_media_metadata ADD COLUMN source varchar(16) NOT NULL; -- 来源：local_nfo 本地NFO / tmdb
ALTER TABLE t_media_metadata ADD COLUMN complete_status varchar(16) NOT NULL; -- 完整性：complete 完整 / incomplete 不完整（本地优先缺字段不补）
ALTER TABLE t_media_metadata ADD COLUMN persist_status varchar(16) DEFAULT 'pending' NOT NULL; -- 落盘状态：pending 待落盘 / persisted 已写回视频目录 / failed 落盘失败待重试
ALTER TABLE t_media_metadata ADD COLUMN poster_file_node_id varchar(13) NULL; -- 海报图文件节点 ID（视频目录下 poster.jpg）
ALTER TABLE t_media_metadata ADD COLUMN backdrop_file_node_id varchar(13) NULL; -- 背景图文件节点 ID（视频目录下 fanart.jpg）

COMMENT ON COLUMN public.t_media_metadata.user_id IS '所属用户 ID（元数据按用户隔离）';
COMMENT ON COLUMN public.t_media_metadata.source IS '来源：local_nfo 本地NFO / tmdb';
COMMENT ON COLUMN public.t_media_metadata.complete_status IS '完整性：complete 完整 / incomplete 不完整（本地优先缺字段不补）';
COMMENT ON COLUMN public.t_media_metadata.persist_status IS '落盘状态：pending 待落盘 / persisted 已写回视频目录 / failed 落盘失败待重试';
COMMENT ON COLUMN public.t_media_metadata.poster_file_node_id IS '海报图文件节点 ID（视频目录下 poster.jpg）';
COMMENT ON COLUMN public.t_media_metadata.backdrop_file_node_id IS '背景图文件节点 ID（视频目录下 fanart.jpg）';
COMMENT ON COLUMN public.t_media_metadata.tmdb_id IS 'TMDB 条目 ID，local_nfo 来源可为空';
COMMENT ON TABLE public.t_media_metadata IS '媒体元数据表，按用户隔离，绑定电影条目/剧/季（ADR 0020）';

-- 废弃字段：系统缓存路径不再需要；season/episode 不再按 tmdb 键控（改由 item/season 直接绑定）
ALTER TABLE t_media_metadata DROP COLUMN poster_path;
ALTER TABLE t_media_metadata DROP COLUMN backdrop_path;
ALTER TABLE t_media_metadata DROP COLUMN series_tmdb_id;
ALTER TABLE t_media_metadata DROP COLUMN season_no;
ALTER TABLE t_media_metadata DROP COLUMN episode_no;

-- 唯一键废弃：隔离模型下去重无意义，改普通索引辅助按用户查同 tmdb 条目
-- 注意：上方 DROP COLUMN series_tmdb_id/season_no/episode_no 会级联删除该索引，故需 IF EXISTS
DROP INDEX IF EXISTS uk_media_metadata_tmdb;
CREATE INDEX idx_media_metadata_user_id ON public.t_media_metadata USING btree (user_id);
CREATE INDEX idx_media_metadata_user_tmdb ON public.t_media_metadata USING btree (user_id, tmdb_id);
