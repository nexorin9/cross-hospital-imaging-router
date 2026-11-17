-- V7: 路由 / 协议 / 业务规则配置表
-- Source main-path reference: 通用 KV 配置 + 路由数据落地（融合增量）
-- 用于持久化需要运行时调整但又不能放进 application.yml 的策略（黑白名单例外、
-- 互认模态白名单、AI 提示词版本等）。所有 key-value 配置均可热更新。

CREATE TABLE route_config (
    id          BIGSERIAL    PRIMARY KEY,
    key         TEXT         NOT NULL UNIQUE,
    value       JSONB        NOT NULL,
    description TEXT,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  TEXT
);

CREATE INDEX idx_route_config_key ON route_config(key);

COMMENT ON TABLE  route_config             IS '路由 / 协议 / 业务规则持久化配置（key-value JSONB）';
COMMENT ON COLUMN route_config.key        IS '配置项 key（如 route.default_peer_aet / ai.prompt_version / mod_recognition.whitelist）';
COMMENT ON COLUMN route_config.value      IS '配置项值（JSONB；与具体 key 的 schema 约定对齐）';
COMMENT ON COLUMN route_config.description IS '配置项用途说明';
COMMENT ON COLUMN route_config.updated_by IS '最近一次更新操作者（employee_id / system）';