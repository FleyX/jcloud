-- 增加 WebDAV 访问开关
alter table t_user
    add column webdav_enabled boolean not null default false;

comment on column t_user.webdav_enabled is '是否启用 WebDAV 访问';
