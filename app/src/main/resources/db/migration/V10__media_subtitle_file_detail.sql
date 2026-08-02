-- 媒体模型重构 expand 阶段（issue #19）：外部字幕关联对象从旧媒体条目改到文件明细行
-- 语义变更：file_id 现在指向 t_media_movie_file / t_media_episode_file 的文件明细行 ID；
-- 其他库无明细表，直接指向 t_media_other 行 ID（other 行本身即文件级实体）。
-- 本迁移仅重命名列、索引与注释，不改动数据；旧关联数据（指向旧 t_media_item）随 issue #21
-- 旧四表清理一并废弃，不做历史迁移。

ALTER TABLE public.t_media_subtitle RENAME COLUMN item_id TO file_id;
ALTER INDEX idx_media_subtitle_item_id RENAME TO idx_media_subtitle_file_id;
ALTER INDEX uk_media_subtitle_item_node RENAME TO uk_media_subtitle_file_node;

COMMENT ON COLUMN public.t_media_subtitle.file_id IS '所属文件明细行 ID：电影/集为 t_media_movie_file / t_media_episode_file 明细行 ID，其他库直接为 t_media_other 行 ID（issue #19 从旧条目改挂）';
COMMENT ON COLUMN public.t_media_subtitle.file_node_id IS '字幕文件对应的文件节点 ID';
COMMENT ON COLUMN public.t_media_subtitle.format IS '字幕格式：srt / ass / ssa / vtt';
COMMENT ON COLUMN public.t_media_subtitle.label IS '展示标签，从文件名后缀解析（如 chs、简体、English）';
COMMENT ON COLUMN public.t_media_subtitle.is_default IS '是否默认字幕（文件名含 .default 后缀）';
COMMENT ON TABLE public.t_media_subtitle IS '文件明细行外部字幕关联表，扫描时按同目录前缀匹配规则重建（issue #19 关联对象从旧条目改到文件明细行）';
