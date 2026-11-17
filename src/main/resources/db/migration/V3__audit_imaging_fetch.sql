-- V3: append-only 链式 checksum 审计表
-- Source main-path reference: dcm4che-net-audit 应用层审计 + 区块链式校验和模式
-- 任何握手指令 / 调阅路由 / 失败重试都必须落一行；触发器禁止 UPDATE/DELETE；每行 row_checksum 由前一行 prev_checksum 链式计算。

CREATE TABLE audit_imaging_fetch (
    id              BIGSERIAL PRIMARY KEY,
    who             TEXT         NOT NULL,
    when_ts         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    from_aet        TEXT,
    to_aet          TEXT,
    study_uid       TEXT,
    sop_class_uid   TEXT,
    transfer_syntax TEXT,
    retry_count     INT          NOT NULL DEFAULT 0,
    status_terminal TEXT         NOT NULL,
    failure_code    TEXT,
    row_checksum    BYTEA        NOT NULL,
    prev_checksum   BYTEA        NOT NULL
);

CREATE INDEX idx_audit_when_ts        ON audit_imaging_fetch(when_ts DESC);
CREATE INDEX idx_audit_from_aet       ON audit_imaging_fetch(from_aet);
CREATE INDEX idx_audit_to_aet         ON audit_imaging_fetch(to_aet);
CREATE INDEX idx_audit_status_terminal ON audit_imaging_fetch(status_terminal);
CREATE INDEX idx_audit_failure_code   ON audit_imaging_fetch(failure_code) WHERE failure_code IS NOT NULL;

COMMENT ON TABLE  audit_imaging_fetch                IS 'append-only 审计表（握手/调阅/失败）';
COMMENT ON COLUMN audit_imaging_fetch.who            IS '操作人（employee_id / system / api-key）';
COMMENT ON COLUMN audit_imaging_fetch.when_ts        IS '事件时间戳';
COMMENT ON COLUMN audit_imaging_fetch.from_aet       IS '源 AET（握手或调阅的发起侧）';
COMMENT ON COLUMN audit_imaging_fetch.to_aet         IS '目标 AET（对端节点 / 本院 PACS）';
COMMENT ON COLUMN audit_imaging_fetch.study_uid      IS '涉及的 DICOM StudyInstanceUID（可空）';
COMMENT ON COLUMN audit_imaging_fetch.status_terminal IS '终态标签（stored/failed/rejected/handshake_compatible/...）';
COMMENT ON COLUMN audit_imaging_fetch.row_checksum   IS 'SHA-256(prev_checksum + 字段拼接)，32 字节';
COMMENT ON COLUMN audit_imaging_fetch.prev_checksum  IS '前一行 row_checksum；空表为 32 字节 0';

-- append-only 触发器：禁止任何 UPDATE/DELETE；这是法规与等保语境下的硬要求。
CREATE OR REPLACE FUNCTION reject_modification() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_imaging_fetch is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_no_update
    BEFORE UPDATE OR DELETE OR TRUNCATE
    ON audit_imaging_fetch
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_modification();