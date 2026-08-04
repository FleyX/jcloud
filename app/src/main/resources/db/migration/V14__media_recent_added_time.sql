-- 媒体入库时间冗余字段：按当前文件明细维护电影最早、剧集最新入库时间
ALTER TABLE public.t_media_movie
    ADD COLUMN added_time timestamp NULL;

ALTER TABLE public.t_media_series
    ADD COLUMN latest_added_time timestamp NULL;

-- 历史电影按当前文件明细最早创建时间回填；无明细的电影保持 NULL。
UPDATE public.t_media_movie movie
SET added_time = files.min_create_time
FROM (
    SELECT movie_id, MIN(create_time) AS min_create_time
    FROM public.t_media_movie_file
    GROUP BY movie_id
) files
WHERE movie.id = files.movie_id;

-- 历史剧集按当前剧下集文件明细最新创建时间回填；无明细的剧集保持 NULL。
UPDATE public.t_media_series series
SET latest_added_time = files.max_create_time
FROM (
    SELECT episode.series_id, MAX(episode_file.create_time) AS max_create_time
    FROM public.t_media_episode_file episode_file
    JOIN public.t_media_episode episode ON episode.id = episode_file.episode_id
    GROUP BY episode.series_id
) files
WHERE series.id = files.series_id;

COMMENT ON COLUMN public.t_media_movie.added_time IS '当前电影文件明细的最早入库时间';
COMMENT ON COLUMN public.t_media_series.latest_added_time IS '当前剧集文件明细的最新入库时间';

CREATE INDEX idx_media_movie_user_added_time
    ON public.t_media_movie USING btree (user_id, added_time DESC)
    WHERE added_time IS NOT NULL;

CREATE INDEX idx_media_series_user_latest_added_time
    ON public.t_media_series USING btree (user_id, latest_added_time DESC)
    WHERE latest_added_time IS NOT NULL;

COMMENT ON INDEX public.idx_media_movie_user_added_time IS '按用户查询非空电影入库时间，按最新入库时间倒序';
COMMENT ON INDEX public.idx_media_series_user_latest_added_time IS '按用户查询非空剧集最新入库时间，按时间倒序';
