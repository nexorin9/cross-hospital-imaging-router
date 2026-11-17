-- V2: 跨院调阅任务表（五态机状态持久化）
-- Source main-path reference: dcm4che-net-audit application-layer audit + 路由表 + MPI 寻址（融合增量）
-- 每次 HL7 触发 / API 异步触发都对应一行；状态机在 queued / querying / pulling / stored / notified / failed 之间单向推进。

CREATE TABLE imaging_fetch_tasks (
    task_id        UUID         PRIMARY KEY,
    patient_mpi    TEXT         NOT NULL,
    study_uid      TEXT         NOT NULL,
    sop_class_uid  TEXT,
    accession_no   TEXT,
    peer_aet       TEXT         NOT NULL,
    status         TEXT         NOT NULL DEFAULT 'queued'
                    CHECK (status IN ('queued','querying','pulling','stored','notified','failed')),
    retry_count    INT          NOT NULL DEFAULT 0,
    failure_code   TEXT,
    ai_summary_id  BIGINT,
    initiated_by   TEXT         NOT NULL,
    initiated_src  TEXT         NOT NULL DEFAULT 'api'
                    CHECK (initiated_src IN ('api','hl7','retry','stuck_recovery')),
    sop_instance_count INT,
    bytes_total    BIGINT,
    duration_ms    BIGINT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 乐观锁版本列（实体 @Version 映射）：并发改同一行任务时用它发现「我改的已经不是最新那一版」。
    -- 这一列必须在表里 —— 实体映射了它，INSERT 就会带上它；缺列则**每一行任务都插不进去**
    -- （Hibernate 建表按实体建、测试环境看不出来；Flyway 建表按本脚本建，生产上直接炸）。
    row_version    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_retry_nonneg  CHECK (retry_count >= 0),
    CONSTRAINT chk_bytes_nonneg  CHECK (bytes_total IS NULL OR bytes_total >= 0)
);

-- 幂等去重的核心约束：同一 (patient_mpi, study_uid) 在「未终结」期间只允许存在一行。
-- 这是数据库层防 race condition 的硬约束；应用层 IdempotencyGuard 也会先查再写。
CREATE UNIQUE INDEX idx_fetch_tasks_idem
    ON imaging_fetch_tasks (patient_mpi, study_uid)
    WHERE status IN ('queued','querying','pulling','stored');

CREATE INDEX idx_fetch_tasks_status        ON imaging_fetch_tasks(status);
CREATE INDEX idx_fetch_tasks_status_updated ON imaging_fetch_tasks(status, updated_at);
CREATE INDEX idx_fetch_tasks_peer_aet      ON imaging_fetch_tasks(peer_aet);
CREATE INDEX idx_fetch_tasks_created_at    ON imaging_fetch_tasks(created_at DESC);

COMMENT ON TABLE  imaging_fetch_tasks                IS '跨院影像调阅任务（按五态机推进）';
COMMENT ON COLUMN imaging_fetch_tasks.task_id        IS 'UUID 主键，对外也是异步任务 ID';
COMMENT ON COLUMN imaging_fetch_tasks.patient_mpi    IS '主索引（医院内 MPI）；同一患者跨院随访的关联锚';
COMMENT ON COLUMN imaging_fetch_tasks.study_uid      IS 'DICOM StudyInstanceUID（外院唯一）';
COMMENT ON COLUMN imaging_fetch_tasks.sop_class_uid  IS '调阅的 SOP Class UID（CT/MR Image Storage 等）';
COMMENT ON COLUMN imaging_fetch_tasks.peer_aet       IS '发起路由的目标节点 AET';
COMMENT ON COLUMN imaging_fetch_tasks.status         IS '五态机：queued→querying→pulling→stored→notified，failed 为终态分支';
COMMENT ON COLUMN imaging_fetch_tasks.retry_count    IS '已重试次数';
COMMENT ON COLUMN imaging_fetch_tasks.failure_code   IS '失败码（与 FailureCodeRegistry / ErrorCode 对齐）';
COMMENT ON COLUMN imaging_fetch_tasks.ai_summary_id  IS '关联 ai_summaries.id（互认初筛 / 报告归一结果）';
COMMENT ON COLUMN imaging_fetch_tasks.initiated_by   IS '发起人（API 调用方的 employee_id 或 HL7 sending application）';
COMMENT ON COLUMN imaging_fetch_tasks.initiated_src  IS '任务来源：api / hl7 / retry / stuck_recovery';
COMMENT ON COLUMN imaging_fetch_tasks.row_version    IS '乐观锁版本（实体 @Version）；并发改同一行任务时用来发现版本冲突';