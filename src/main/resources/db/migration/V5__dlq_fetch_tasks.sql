-- V5: 调阅任务死信队列
-- Source main-path reference: 通用分布式系统 DLQ 模式
-- 当调阅任务重试耗尽或遇到不可恢复错误（黑白名单命中 / 协议不兼容）时入队；
-- 告警 webhook 由 DlqAlertNotifier 异步推送；信息科可手动标记 resolved。

CREATE TABLE dlq_fetch_tasks (
    id                  BIGSERIAL    PRIMARY KEY,
    task_id             UUID         NOT NULL,
    failure_code        TEXT         NOT NULL,
    failure_message     TEXT,
    retry_attempts      INT          NOT NULL DEFAULT 0,
    first_failed_at     TIMESTAMPTZ  NOT NULL,
    last_retry_at       TIMESTAMPTZ,
    resolved_at         TIMESTAMPTZ,
    resolved_by         TEXT,
    resolution_note     TEXT,
    alert_webhook_sent  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_dlq_retry_nonneg CHECK (retry_attempts >= 0)
);

-- 用于「未处置」列表查询的局部索引：resolved_at IS NULL 即为未处置。
CREATE INDEX idx_dlq_unresolved
    ON dlq_fetch_tasks(created_at DESC)
    WHERE resolved_at IS NULL;

CREATE INDEX idx_dlq_task_id        ON dlq_fetch_tasks(task_id);
CREATE INDEX idx_dlq_failure_code   ON dlq_fetch_tasks(failure_code);
CREATE INDEX idx_dlq_resolved_at    ON dlq_fetch_tasks(resolved_at)
    WHERE resolved_at IS NOT NULL;

COMMENT ON TABLE  dlq_fetch_tasks                  IS '调阅任务死信队列（重试耗尽 / 不可恢复错误）';
COMMENT ON COLUMN dlq_fetch_tasks.task_id          IS '关联 imaging_fetch_tasks.task_id';
COMMENT ON COLUMN dlq_fetch_tasks.failure_code     IS '失败码（与 FailureCodeRegistry / ErrorCode 对齐）';
COMMENT ON COLUMN dlq_fetch_tasks.failure_message  IS '失败消息（含异常类型与堆栈摘要）';
COMMENT ON COLUMN dlq_fetch_tasks.retry_attempts   IS '最后一次失败前累计的重试次数';
COMMENT ON COLUMN dlq_fetch_tasks.first_failed_at  IS '首次失败时间戳';
COMMENT ON COLUMN dlq_fetch_tasks.last_retry_at    IS '最后一次重试时间戳';
COMMENT ON COLUMN dlq_fetch_tasks.resolved_at      IS '信息科手动处置时间（NULL 即未处置）';
COMMENT ON COLUMN dlq_fetch_tasks.alert_webhook_sent IS '是否已推告警 webhook（10 分钟冷却）';