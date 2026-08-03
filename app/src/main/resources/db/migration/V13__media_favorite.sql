-- 收藏功能（工单 01）：t_media_favorite 收藏表
-- 用户对电影/剧集/季/集/其他五类实体均可收藏；按用户隔离，唯一约束 (user_id, owner_type, owner_id)；
-- 无外键（项目约定），级联清理由应用层负责（三个 CascadeSupport 删除实体行时按 owner_type + owner_id 清收藏）。
CREATE TABLE public.t_media_favorite (
    id varchar(13) NOT NULL,
    user_id varchar(13) NOT NULL,
    owner_type varchar(16) NOT NULL,
    owner_id varchar(13) NOT NULL,
    create_time timestamp NOT NULL DEFAULT now(),
    CONSTRAINT t_media_favorite_pkey PRIMARY KEY (id),
    CONSTRAINT t_media_favorite_uk UNIQUE (user_id, owner_type, owner_id)
);

-- 按用户查询收藏记录（我的收藏页 / VO 填充联查）
CREATE INDEX idx_media_favorite_user ON public.t_media_favorite USING btree (user_id);

COMMENT ON TABLE public.t_media_favorite IS '媒体收藏表：用户对电影/剧集/季/集/其他五类实体的收藏记录，按用户隔离';
COMMENT ON COLUMN public.t_media_favorite.id IS '主键，13 位 base36';
COMMENT ON COLUMN public.t_media_favorite.user_id IS '收藏者用户 ID';
COMMENT ON COLUMN public.t_media_favorite.owner_type IS '归属实体类型：movie/series/season/episode/other';
COMMENT ON COLUMN public.t_media_favorite.owner_id IS '归属实体 ID（对应类型表主键）';
COMMENT ON COLUMN public.t_media_favorite.create_time IS '收藏时间（收藏页按此倒序）';
COMMENT ON INDEX public.idx_media_favorite_user IS '按用户查询收藏记录的索引';
