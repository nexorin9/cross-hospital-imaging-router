-- V1: 外院 DICOM 节点准入表
-- Source main-path reference: dcm4che-net-audit application-layer audit model
-- 该表持久化允许接入跨院影像互认网络的 PACS/影像设备节点元数据，
-- 每次握手、健康检查、调阅路由都基于该表的 AET/host/port/协商参数建立关联。

CREATE TABLE imaging_peers (
    id              BIGSERIAL PRIMARY KEY,
    peer_aet        TEXT        NOT NULL UNIQUE,
    host            TEXT        NOT NULL,
    port            INT         NOT NULL DEFAULT 11112,
    tls_mode        TEXT        NOT NULL DEFAULT 'none'
                    CHECK (tls_mode IN ('none','start_tls','tls')),
    present_contexts JSONB      NOT NULL DEFAULT '[]'::jsonb,
    transfer_syntaxes JSONB     NOT NULL DEFAULT '[]'::jsonb,
    blacklist_aets  JSONB       NOT NULL DEFAULT '[]'::jsonb,
    max_retries     INT         NOT NULL DEFAULT 3,
    backoff_ms      INT         NOT NULL DEFAULT 1000,
    cache_ttl_hours INT         NOT NULL DEFAULT 24,
    max_cache_mb    BIGINT      NOT NULL DEFAULT 5120,
    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_peer_aet_len CHECK (char_length(peer_aet) BETWEEN 1 AND 16),
    CONSTRAINT chk_peer_port    CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT chk_peer_cache   CHECK (max_cache_mb >= 0)
);

CREATE INDEX idx_imaging_peers_enabled ON imaging_peers(enabled) WHERE enabled = TRUE;
CREATE INDEX idx_imaging_peers_host   ON imaging_peers(host);

COMMENT ON TABLE  imaging_peers                 IS '外院 DICOM 节点准入表';
COMMENT ON COLUMN imaging_peers.peer_aet       IS '对端 AET，1-16 字符，全局唯一';
COMMENT ON COLUMN imaging_peers.host           IS '对端 PACS/设备主机/IP';
COMMENT ON COLUMN imaging_peers.port           IS '对端 DICOM 监听端口';
COMMENT ON COLUMN imaging_peers.tls_mode       IS 'none | start_tls | tls';
COMMENT ON COLUMN imaging_peers.present_contexts  IS '允许协商的 SOP Class UID 列表（JSONB 数组）';
COMMENT ON COLUMN imaging_peers.transfer_syntaxes IS '允许协商的 Transfer Syntax UID 列表（JSONB 数组）';
COMMENT ON COLUMN imaging_peers.blacklist_aets  IS '同节点下需屏蔽的 AET（JSONB 数组）';
COMMENT ON COLUMN imaging_peers.max_retries     IS '调阅路由最大重试次数';
COMMENT ON COLUMN imaging_peers.backoff_ms      IS '重试基础退避毫秒数';
COMMENT ON COLUMN imaging_peers.cache_ttl_hours IS '拉回影像在 PACS 缓存中的存活时间';
COMMENT ON COLUMN imaging_peers.max_cache_mb    IS '该节点允许占用的本院缓存上限（MB）';