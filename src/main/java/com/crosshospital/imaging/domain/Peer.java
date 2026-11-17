package com.crosshospital.imaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 外院 DICOM 节点准入表（{@code imaging_peers}）。
 *
 * <p>每次握手、健康检查、调阅路由都基于本表的 AET/host/port/协商参数建立关联。
 * AET 全局唯一，长度 1..16 由 V1 CHECK 约束保证；端口范围 1..65535。
 *
 * <p>JSONB 三列（present_contexts / transfer_syntaxes / blacklist_aets）在 Java 侧
 * 仍是 {@link String}，应用层负责 JSON 解析与序列化；但每个字段都必须标
 * {@link JdbcTypeCode}({@code SqlTypes.JSON})，否则 Hibernate 按 varchar 绑参，
 * 真 PostgreSQL 会以 {@code 42804: column ... is of type jsonb but expression is of type
 * character varying} 拒掉写入。此类问题只在真库上暴露（见
 * {@code PeerJsonbPostgresTest}）。
 *
 * <p>源主路径证据：dcm4che ReconnectDirContext（LDAP 自动重连）→ 改写为
 * PostgreSQL + HikariCP，应用层维护 AET 准入表。
 */
@Entity
@Table(name = "imaging_peers")
public class Peer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "peer_aet", nullable = false, unique = true, length = 16)
    private String peerAet;

    @Column(name = "host", nullable = false)
    private String host;

    @Column(name = "port", nullable = false)
    private Integer port;

    @Column(name = "tls_mode", nullable = false)
    private String tlsMode = "none";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "present_contexts", nullable = false, columnDefinition = "jsonb")
    private String presentContexts = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transfer_syntaxes", nullable = false, columnDefinition = "jsonb")
    private String transferSyntaxes = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "blacklist_aets", nullable = false, columnDefinition = "jsonb")
    private String blacklistAets = "[]";

    @Column(name = "max_retries", nullable = false)
    private Integer maxRetries = 3;

    @Column(name = "backoff_ms", nullable = false)
    private Integer backoffMs = 1000;

    @Column(name = "cache_ttl_hours", nullable = false)
    private Integer cacheTtlHours = 24;

    @Column(name = "max_cache_mb", nullable = false)
    private Long maxCacheMb = 5120L;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onInsert() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // -- getters / setters --
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPeerAet() { return peerAet; }
    public void setPeerAet(String peerAet) { this.peerAet = peerAet; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getTlsMode() { return tlsMode; }
    public void setTlsMode(String tlsMode) { this.tlsMode = tlsMode; }

    public String getPresentContexts() { return presentContexts; }
    public void setPresentContexts(String presentContexts) { this.presentContexts = presentContexts; }

    public String getTransferSyntaxes() { return transferSyntaxes; }
    public void setTransferSyntaxes(String transferSyntaxes) { this.transferSyntaxes = transferSyntaxes; }

    public String getBlacklistAets() { return blacklistAets; }
    public void setBlacklistAets(String blacklistAets) { this.blacklistAets = blacklistAets; }

    public Integer getMaxRetries() { return maxRetries; }
    public void setMaxRetries(Integer maxRetries) { this.maxRetries = maxRetries; }

    public Integer getBackoffMs() { return backoffMs; }
    public void setBackoffMs(Integer backoffMs) { this.backoffMs = backoffMs; }

    public Integer getCacheTtlHours() { return cacheTtlHours; }
    public void setCacheTtlHours(Integer cacheTtlHours) { this.cacheTtlHours = cacheTtlHours; }

    public Long getMaxCacheMb() { return maxCacheMb; }
    public void setMaxCacheMb(Long maxCacheMb) { this.maxCacheMb = maxCacheMb; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}