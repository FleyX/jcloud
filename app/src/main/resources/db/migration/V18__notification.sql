-- 站内通知表（ADR 0039）：通知模块落库的站内渠道数据。
-- 每用户仅保留最近 100 条，超出部分由站内渠道在写入后物理删除（软删除/回收站不适用）。
CREATE TABLE t_notification (
    id varchar(13) NOT NULL, -- 通知 ID，13 位定长 base36 字符串
    user_id varchar(13) NOT NULL, -- 收件用户 ID
    event_type varchar(64) NOT NULL, -- 事件类型
    title varchar(256) NOT NULL, -- 通知标题
    content varchar(1024) NULL, -- 通知内容
    is_read int2 DEFAULT 0 NOT NULL, -- 是否已读：1 已读，0 未读
    create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL, -- 创建时间
    update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL, -- 更新时间
    CONSTRAINT t_notification_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_notification_user_read ON t_notification (user_id, is_read);
CREATE INDEX idx_notification_user_create_time ON t_notification (user_id, create_time);
