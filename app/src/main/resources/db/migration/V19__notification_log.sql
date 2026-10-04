-- 邮件发送记录表（ADR 0039）：记录所有邮件发送结果（含后续邮件分享），供管理端查看。
-- 仅追加不清理，物理删除。
CREATE TABLE t_notification_log (
    id varchar(13) NOT NULL, -- 记录 ID，13 位定长 base36 字符串
    event_type varchar(64) NOT NULL, -- 事件类型（含 email_share 邮件分享）
    recipient varchar(128) NOT NULL, -- 收件邮箱地址
    subject varchar(256) NOT NULL, -- 邮件主题
    success int2 NOT NULL, -- 发送结果：1 成功，0 失败
    error_message varchar(1024) NULL, -- 失败原因，成功时为空
    create_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL, -- 创建时间
    update_time timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL, -- 更新时间
    CONSTRAINT t_notification_log_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_notification_log_create_time ON t_notification_log (create_time);
