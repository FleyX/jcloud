-- 媒体模型重构 expand 阶段（issue #20）：t_media_metadata_v2 补齐削刮写回所需字段
-- persist_status：NFO 写回机制载体（V8 旧表同款定义，落盘状态 pending/persisted/failed），#16 遗留缺口，本票补齐；
-- raw_json：TMDB 原始响应 JSON，削刮写回按 poster_path/backdrop_path/still_path 下载图片（沿用现有图片机制），
--   旧表 t_media_metadata 的 raw_json 在新表缺少，导致 TMDB 图片来源图片写回无法工作；
-- genres：类型列表（逗号分隔），NFO 写回（Jellyfin 兼容 <genre> 节点）与旧表输出保持一致。
-- 全部字段带注释；无外键；单迁移文件。

ALTER TABLE public.t_media_metadata_v2 ADD COLUMN persist_status varchar(16) DEFAULT 'pending' NOT NULL; -- 落盘状态：pending 待落盘 / persisted 已写回视频目录 / failed 落盘失败待重试
ALTER TABLE public.t_media_metadata_v2 ADD COLUMN raw_json text NULL; -- TMDB 原始响应 JSON（图片写回按 poster_path/backdrop_path/still_path 下载），local_nfo 来源可为空
ALTER TABLE public.t_media_metadata_v2 ADD COLUMN genres varchar(512) NULL; -- 类型列表，逗号分隔，NFO 写回使用

COMMENT ON COLUMN public.t_media_metadata_v2.persist_status IS '落盘状态：pending 待落盘 / persisted 已写回视频目录 / failed 落盘失败待重试（issue #20，与 V8 旧表定义一致）';
COMMENT ON COLUMN public.t_media_metadata_v2.raw_json IS 'TMDB 原始响应 JSON（图片写回按 poster_path/backdrop_path/still_path 下载），local_nfo 来源可为空';
COMMENT ON COLUMN public.t_media_metadata_v2.genres IS '类型列表，逗号分隔，NFO 写回使用';
