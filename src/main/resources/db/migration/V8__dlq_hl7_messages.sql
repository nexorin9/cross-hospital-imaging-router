-- V8: HL7 触发报文死信队列
-- Source main-path reference: github_ref/dcm4che/dcm4che-net-hl7/.../HL7ConnectionMonitor.java:54-62
--   （onMessageReceived → onMessageProcessed(conn, socket, msg, rsp, ex)：
--     处理失败必须显式回报，不能"收到即算成功"）
--
-- 为什么单独建表而不复用 dlq_fetch_tasks：
--   一条 HL7 触发报文可能在"成为调阅任务"之前就失败（MSH 段缺失、PID 缺失、消息类型不支持），
--   那时它没有 task_id、只有一帧报文。塞进 dlq_fetch_tasks 会把 task_id 写空，
--   破坏该表"一个 task 一行"的语义，也让信息科按 task_id 排查时看到一堆空指针行。
--
-- 报文原文不落库：ORM^O01 里带患者姓名、出生日期、就诊号等身份信息，
--   死信表是要被运维和审计翻阅的表，不该成为第二份病历。本表只存：
--   MSH 段头摘要（不含患者身份字段）+ 消息控制 ID + 失败分类 + 字节数。
--   信息科凭 message_control_id 回 HIS 侧日志即可定位原始报文。

CREATE TABLE dlq_hl7_messages (
    id                  BIGSERIAL    PRIMARY KEY,
    message_control_id  TEXT,
    route_key           TEXT,
    sending_application TEXT,
    sending_facility    TEXT,
    patient_mpi         TEXT,
    accession_number    TEXT,
    failure_code        TEXT         NOT NULL,
    failure_message     TEXT,
    msh_excerpt         TEXT,
    payload_bytes       INT          NOT NULL DEFAULT 0,
    remote_address      TEXT,
    received_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    alert_webhook_sent  BOOLEAN      NOT NULL DEFAULT FALSE,
    resolved_at         TIMESTAMPTZ,
    resolved_by         TEXT,
    resolution_note     TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_dlq_hl7_failure_code CHECK (failure_code IN (
        'invalid_msh',
        'dynamic_separator_anomaly',
        'unsupported_message_type',
        'missing_pid',
        'missing_study_reference',
        'no_target_peer',
        'enqueue_failed'
    )),
    CONSTRAINT chk_dlq_hl7_payload_bytes CHECK (payload_bytes >= 0)
);

-- 未处置列表（与 dlq_fetch_tasks 的局部索引同构）
CREATE INDEX idx_dlq_hl7_unresolved
    ON dlq_hl7_messages(created_at DESC)
    WHERE resolved_at IS NULL;

-- 凭消息控制 ID 回查 HIS 侧原始报文
CREATE INDEX idx_dlq_hl7_control_id   ON dlq_hl7_messages(message_control_id);
CREATE INDEX idx_dlq_hl7_failure_code ON dlq_hl7_messages(failure_code);

COMMENT ON TABLE  dlq_hl7_messages                    IS 'HL7 触发报文死信队列（解析失败 / 缺关键字段 / 无承接节点 / 入队失败）';
COMMENT ON COLUMN dlq_hl7_messages.message_control_id IS 'MSH-10 消息控制 ID（回查 HIS 侧原始报文的唯一凭据）';
COMMENT ON COLUMN dlq_hl7_messages.route_key          IS 'MSH-9 路由键（ORM^O01 / ADT^A04 / ADT^A08）';
COMMENT ON COLUMN dlq_hl7_messages.failure_code       IS '失败分类（与 segment-mappings.yml protocol-errors 块对齐）';
COMMENT ON COLUMN dlq_hl7_messages.msh_excerpt        IS 'MSH 段头摘要（只含应用/设施/控制 ID，不含患者身份字段）';
COMMENT ON COLUMN dlq_hl7_messages.payload_bytes      IS '原始报文字节数（不落原文，避免死信表变成第二份病历）';
COMMENT ON COLUMN dlq_hl7_messages.alert_webhook_sent IS '是否已推告警 webhook';
COMMENT ON COLUMN dlq_hl7_messages.resolved_at        IS '信息科手动处置时间（NULL 即未处置）';
