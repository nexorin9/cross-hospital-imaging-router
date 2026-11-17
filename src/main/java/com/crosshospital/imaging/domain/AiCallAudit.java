package com.crosshospital.imaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * ModelGateway 调用审计实体（{@code ai_call_audit}）。
 *
 * <p>每次模型调用（成功或失败）落一行；status 五态（success / timeout /
 * parse_error / http_error / unreachable）覆盖 ModelGateway 失败谱。
 *
 * <p>本表不引入 append-only 触发器，因为允许运维按 created_at 滚动归档。
 *
 * <p>源主路径证据：OpenAI / Anthropic-compatible 协议调用元数据持久化。
 */
@Entity
@Table(name = "ai_call_audit")
public class AiCallAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "task_id", columnDefinition = "uuid")
    private UUID taskId;

    @Column(name = "kind")
    private String kind;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onInsert() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public AiCallAudit() {
    }

    // -- getters / setters --
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}