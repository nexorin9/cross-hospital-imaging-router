-- V6: 模型网关调用审计表
-- Source main-path reference: OpenAI / Anthropic-compatible 协议调用元数据持久化
-- 每次 ModelGateway 调用都落一行（含失败），便于事后归因（成功 / 超时 / 解析失败 / HTTP 错误 / 不可达）。
-- ai_call_audit 不引入 append-only 触发器，因为本表允许运维查询时按 created_at 滚动归档。

CREATE TABLE ai_call_audit (
    id            BIGSERIAL    PRIMARY KEY,
    task_id       UUID,
    kind          TEXT,
    model_name    TEXT,
    latency_ms    INT,
    status        TEXT         NOT NULL
                  CHECK (status IN ('success','timeout','parse_error','http_error','unreachable')),
    error_message TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_latency_nonneg CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

CREATE INDEX idx_ai_call_audit_task_id  ON ai_call_audit(task_id);
CREATE INDEX idx_ai_call_audit_status   ON ai_call_audit(status);
CREATE INDEX idx_ai_call_audit_created  ON ai_call_audit(created_at DESC);

COMMENT ON TABLE  ai_call_audit             IS 'ModelGateway 调用审计（成功/失败/降级）';
COMMENT ON COLUMN ai_call_audit.task_id    IS '关联 imaging_fetch_tasks.task_id（异步调用时可为 NULL）';
COMMENT ON COLUMN ai_call_audit.kind       IS 'report_normalize / feasibility_screener';
COMMENT ON COLUMN ai_call_audit.model_name IS '调用的模型名称（不可达时为 NULL）';
COMMENT ON COLUMN ai_call_audit.latency_ms IS '调用耗时（毫秒）';
COMMENT ON COLUMN ai_call_audit.status     IS 'success / timeout / parse_error / http_error / unreachable';
COMMENT ON COLUMN ai_call_audit.error_message IS '失败时的错误消息（截断到 4KB）';