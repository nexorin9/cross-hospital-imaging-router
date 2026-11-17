package com.crosshospital.imaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * AI 介入点结果表（{@code ai_summaries}）。
 *
 * <p>报告归一（{@code report_normalize}）与互认初筛（{@code feasibility_screener}）
 * 都会落一行；{@code source} 字段区分 {@code model}（来自 ModelGateway）与
 * {@code rule_fallback}（来自 RuleTemplateEngine）。
 *
 * <p>{@code payload} 为 JSONB；暂以 String 持久化，应用层负责 JSON 序列化。
 *
 * <p>源主路径证据：通用 AI 系统降级模式 + AI 输出责任边界。
 */
@Entity
@Table(name = "ai_summaries")
public class AiSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "task_id", nullable = false, columnDefinition = "uuid")
    private UUID taskId;

    @Column(name = "kind", nullable = false)
    private String kind;

    /**
     * 载荷（JSONB）。
     *
     * <p><b>{@code @JdbcTypeCode(SqlTypes.JSON)} 不是装饰</b>：V4 迁移把这一列建成
     * {@code jsonb}，而字段类型是 {@link String}。不标这个注解时，Hibernate 会按
     * {@code varchar} 绑参，PostgreSQL 直接拒掉：
     * {@code column "payload" is of type jsonb but expression is of type character varying}
     * —— 本地用 Mockito / H2 验不出来（替身不执行 PG 的类型检查），
     * 上了真库才炸。标上之后 Hibernate 走 JSON 类型的绑定，PG 才肯收。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onInsert() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public AiSummary() {
    }

    // -- getters / setters --
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}