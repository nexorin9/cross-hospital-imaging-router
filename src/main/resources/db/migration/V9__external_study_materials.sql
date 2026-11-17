-- V9: 外院检查资料（AI 介入点的真实输入）
-- 报告归一需要外院报告原文，互认初筛需要外院模态 / 序列 / 检查项目。
-- 这三类内容不来自任务行（imaging_fetch_tasks 只记路由状态），
-- 由 C-FIND / WADO-RS 拉回后由接入侧登记，AI 增强层按 study_uid 读取。
-- 资料未登记时增强层不产出摘要（不臆造外院报告内容），主路径照常推进 notified。
CREATE TABLE external_study_materials (
    study_uid         TEXT        PRIMARY KEY,
    report_text       TEXT,
    modality          TEXT,
    series            JSONB       NOT NULL DEFAULT '[]'::jsonb,
    study_items       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    local_study_items JSONB       NOT NULL DEFAULT '[]'::jsonb,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE external_study_materials IS '外院检查资料：报告原文 / 模态 / 序列 / 检查项目；AI 两个介入点的输入';
COMMENT ON COLUMN external_study_materials.report_text IS '脱敏后的外院报告文本；为空时报告归一介入点不产出';
