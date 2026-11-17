-- V4: AI 介入点结果表（报告归一 / 互认初筛 / 失败时的规则模板回退）
-- Source main-path reference: 通用 AI 系统降级模式 + AI 输出责任边界
-- 凡是经过模型网关（ModelGateway）的调用都持久化到这里，便于审计与回溯。
-- 模型不可达 / 超时 / 解析失败时 source='rule_fallback'，明示「不依赖模型生成」。

CREATE TABLE ai_summaries (
    id          BIGSERIAL    PRIMARY KEY,
    task_id     UUID         NOT NULL,
    kind        TEXT         NOT NULL
                CHECK (kind IN ('report_normalize','feasibility_screener')),
    payload     JSONB        NOT NULL,
    model_name  TEXT,
    source      TEXT         NOT NULL
                CHECK (source IN ('model','rule_fallback')),
    latency_ms  INT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_latency_nonneg CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

CREATE INDEX idx_ai_summaries_task_id  ON ai_summaries(task_id);
CREATE INDEX idx_ai_summaries_kind     ON ai_summaries(kind);
CREATE INDEX idx_ai_summaries_source   ON ai_summaries(source);
CREATE INDEX idx_ai_summaries_created  ON ai_summaries(created_at DESC);

COMMENT ON TABLE  ai_summaries              IS 'AI 介入点结果持久化（模型 / 规则回退均落）';
COMMENT ON COLUMN ai_summaries.task_id      IS '关联 imaging_fetch_tasks.task_id';
COMMENT ON COLUMN ai_summaries.kind         IS '报告归一 / 互认初筛';
COMMENT ON COLUMN ai_summaries.payload      IS '结构化输出（JSONB；与对应 JSON Schema 对齐）';
COMMENT ON COLUMN ai_summaries.model_name   IS '使用的模型名称（rule_fallback 时为 NULL）';
COMMENT ON COLUMN ai_summaries.source       IS 'model 来自 ModelGateway；rule_fallback 来自 RuleTemplateEngine';
COMMENT ON COLUMN ai_summaries.latency_ms   IS '模型调用耗时（rule_fallback 通常 < 50ms）';