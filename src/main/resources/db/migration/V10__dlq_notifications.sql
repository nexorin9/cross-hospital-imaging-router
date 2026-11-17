-- V10: HIS 通知 webhook 死信
-- 与 dlq_fetch_tasks（一个调阅任务反复失败）分开：
-- 这一行的语义是「影像已经写入本院 PACS，但 HIS 工单没收到完成通知」，
-- 任务本身是成功的，不能把它塞进失败任务死信里让信息科去排查网络。
-- 不落完整 webhook URL（URL 里可能带 token），只留主机摘要。
CREATE TABLE dlq_notifications (
    id                  BIGSERIAL    PRIMARY KEY,
    task_id             UUID         NOT NULL,
    study_uid           TEXT,
    target_pacs_aet     TEXT,
    webhook_host        TEXT,
    failure_code        TEXT         NOT NULL,
    failure_message     TEXT,
    retry_attempts      INT          NOT NULL DEFAULT 0,
    alert_webhook_sent  BOOLEAN      NOT NULL DEFAULT FALSE,
    resolved_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_dlq_notification_code CHECK (failure_code IN (
        'webhook_failed', 'webhook_timeout', 'webhook_unreachable'))
);

CREATE INDEX idx_dlq_notifications_unresolved
    ON dlq_notifications (created_at)
    WHERE resolved_at IS NULL;

COMMENT ON TABLE dlq_notifications IS '调阅成功但 HIS 工单回调三次重试仍失败的待办；信息科按 task_id 补推';
