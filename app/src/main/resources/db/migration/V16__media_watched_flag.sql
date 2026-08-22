-- 已观看标记（工单 01）：五张媒体表各增 watched 布尔列
-- 语义：已观看由播放进度驱动自动置位，也可由用户手动标记；默认 false，不回填存量数据；
-- 剧集/季自身的标记与三级联动在工单 02，本迁移仅让五张表的列就绪。
ALTER TABLE public.t_media_movie ADD COLUMN watched boolean NOT NULL DEFAULT false;
ALTER TABLE public.t_media_episode ADD COLUMN watched boolean NOT NULL DEFAULT false;
ALTER TABLE public.t_media_other ADD COLUMN watched boolean NOT NULL DEFAULT false;
ALTER TABLE public.t_media_series ADD COLUMN watched boolean NOT NULL DEFAULT false;
ALTER TABLE public.t_media_season ADD COLUMN watched boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN public.t_media_movie.watched IS '已观看标记：手动标记或播放进度达看完阈值自动置位，置位时进度清零';
COMMENT ON COLUMN public.t_media_episode.watched IS '已观看标记：手动标记或播放进度达看完阈值自动置位，置位时进度清零';
COMMENT ON COLUMN public.t_media_other.watched IS '已观看标记：手动标记或播放进度达看完阈值自动置位，置位时进度清零';
COMMENT ON COLUMN public.t_media_series.watched IS '已观看标记：剧自身的标记（工单 02 三级联动使用）';
COMMENT ON COLUMN public.t_media_season.watched IS '已观看标记：季自身的标记（工单 02 三级联动使用）';
