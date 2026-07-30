-- 剧表增加首播年份：从剧文件夹名解析，用于 TMDB 匹配消歧
ALTER TABLE t_media_series ADD COLUMN release_year INTEGER;
COMMENT ON COLUMN t_media_series.release_year IS '剧文件夹名解析出的首播年份，用于 TMDB 匹配消歧';
