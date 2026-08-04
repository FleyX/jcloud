-- 媒体模型重构 contract 阶段（issue #21，ADR 0021）
-- 旧四表（t_media_item / t_media_series / t_media_season / t_media_metadata）与旧代码路径已成死代码
-- （电视/电影/其他扫描、削刮、播放、首页在 #17-#20 已全部切新模型），本迁移：
--   1. drop 旧四表（无外键；t_media_subtitle 在 V10 已改挂 file_id 指向文件明细行，无 FK 依赖，
--      其中指向旧 t_media_item 的存量关联数据一并清理）
--   2. 并行新表 rename 回最终名：t_media_series_v2 → t_media_series、t_media_season_v2 → t_media_season、
--      t_media_metadata_v2 → t_media_metadata（索引/约束名顺带规范为最终名，旧同名索引随旧表 drop 已移除，无冲突）
--   3. 补 t_media_episode 续播查询索引（#16 审查遗留：首页继续观看按 series_id 范围取集后
--      按 last_play_time 倒序/过滤，见 MediaHomeQuerySupport）
-- 全部语句幂等性不保证（Flyway 单次执行），无外键，全注释。

-- ===================== 1. 清理 t_media_subtitle 中指向旧 t_media_item 的存量关联 =====================
-- V10 改挂 file_id 后旧数据仍指向旧 t_media_item 行 ID，随旧表删除成为脏数据；
-- 删除 file_id 在新文件明细表（t_media_movie_file / t_media_episode_file / t_media_other）中不存在的行。
DELETE FROM public.t_media_subtitle
WHERE file_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM public.t_media_movie_file  WHERE id = t_media_subtitle.file_id)
  AND NOT EXISTS (SELECT 1 FROM public.t_media_episode_file WHERE id = t_media_subtitle.file_id)
  AND NOT EXISTS (SELECT 1 FROM public.t_media_other       WHERE id = t_media_subtitle.file_id);

-- ===================== 2. drop 旧四表 =====================
-- 旧表无外键、无其他表依赖，直接删除；索引随表级联删除。
DROP TABLE IF EXISTS public.t_media_item;
DROP TABLE IF EXISTS public.t_media_series;
DROP TABLE IF EXISTS public.t_media_season;
DROP TABLE IF EXISTS public.t_media_metadata;

-- ===================== 3. 并行新表 rename 回最终名（索引/约束名顺带规范） =====================

-- 3.1 剧集：t_media_series_v2 → t_media_series
ALTER TABLE public.t_media_series_v2 RENAME TO t_media_series;
ALTER INDEX public.t_media_series_v2_pkey RENAME TO t_media_series_pkey;
ALTER INDEX public.uk_media_series_v2_folder_node RENAME TO uk_media_series_folder_node;
ALTER INDEX public.idx_media_series_v2_user_id RENAME TO idx_media_series_user_id;
ALTER INDEX public.idx_media_series_v2_directory_id RENAME TO idx_media_series_directory_id;
ALTER INDEX public.idx_media_series_v2_source_id RENAME TO idx_media_series_source_id;
ALTER INDEX public.idx_media_series_v2_metadata_id RENAME TO idx_media_series_metadata_id;
COMMENT ON TABLE public.t_media_series IS '剧集表（标题级，文件夹锚定，库级归属），媒体模型重构 contract 阶段由 t_media_series_v2 rename 而来';

-- 3.2 季：t_media_season_v2 → t_media_season
ALTER TABLE public.t_media_season_v2 RENAME TO t_media_season;
ALTER INDEX public.t_media_season_v2_pkey RENAME TO t_media_season_pkey;
ALTER INDEX public.uk_media_season_v2_folder_node RENAME TO uk_media_season_folder_node;
ALTER INDEX public.idx_media_season_v2_series_id RENAME TO idx_media_season_series_id;
COMMENT ON TABLE public.t_media_season IS '电视剧季表（文件夹锚定），媒体模型重构 contract 阶段由 t_media_season_v2 rename 而来';

-- 3.3 元数据：t_media_metadata_v2 → t_media_metadata
ALTER TABLE public.t_media_metadata_v2 RENAME TO t_media_metadata;
ALTER INDEX public.t_media_metadata_v2_pkey RENAME TO t_media_metadata_pkey;
ALTER INDEX public.uk_media_metadata_v2_owner RENAME TO uk_media_metadata_owner;
ALTER INDEX public.idx_media_metadata_v2_user_id RENAME TO idx_media_metadata_user_id;
ALTER INDEX public.idx_media_metadata_v2_user_tmdb RENAME TO idx_media_metadata_user_tmdb;
COMMENT ON TABLE public.t_media_metadata IS '媒体元数据表，按用户隔离，与电影/剧集/季/集一对一绑定（owner_type + owner_id 反向指针），媒体模型重构 contract 阶段由 t_media_metadata_v2 rename 而来';

-- ===================== 4. 补 t_media_episode 续播查询索引（#16 审查遗留） =====================
-- 首页继续观看/接下来（MediaHomeQuerySupport）：按剧集行的 series_id 范围取集后，内存过滤
-- progress/last_play_time 并按 last_play_time 倒序；(series_id, last_play_time) 组合索引同时支撑
-- 「某剧的集按最近播放倒序」与 series_id 维度范围查询（series_id 前缀）。
CREATE INDEX idx_media_episode_series_play ON public.t_media_episode USING btree (series_id, last_play_time);
COMMENT ON INDEX public.idx_media_episode_series_play IS '剧集行的续播查询索引：series_id 维度取集 + 按 last_play_time 倒序（issue #21 补）';
