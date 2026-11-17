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
 * 调阅任务死信队列实体（{@code dlq_fetch_tasks}）。
 *
 * <p>当任务重试耗尽 / 黑白名单命中 / 协议不兼容等不可恢复错误时入队；
 * 信息科通过 {@code DlqController} 标记 {@code resolved_at} 完成处置。
 *
 * <p>源主路径证据：通用分布式系统 DLQ 模式。
 */
@Entity
@Table(name = "dlq_fetch_tasks")
public class DlqEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "task_id", nullable = false, columnDefinition = "uuid")
    private UUID taskId;

    @Column(name = "failure_code", nullable = false)
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @Column(name = "retry_attempts", nullable = false)
    private Integer retryAttempts = 0;

    @Column(name = "first_failed_at", nullable = false)
    private Instant firstFailedAt;

    @Column(name = "last_retry_at")
    private Instant lastRetryAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "alert_webhook_sent", nullable = false)
    private Boolean alertWebhookSent = Boolean.FALSE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onInsert() {
        if (createdAt == null) createdAt = Instant.now();
        if (firstFailedAt == null) firstFailedAt = createdAt;
        if (retryAttempts == null) retryAttempts = 0;
        if (alertWebhookSent == null) alertWebhookSent = Boolean.FALSE;
    }

    public DlqEntry() {
    }

    // -- getters / setters --
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }

    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }

    public Integer getRetryAttempts() { return retryAttempts; }
    public void setRetryAttempts(Integer retryAttempts) { this.retryAttempts = retryAttempts; }

    public Instant getFirstFailedAt() { return firstFailedAt; }
    public void setFirstFailedAt(Instant firstFailedAt) { this.firstFailedAt = firstFailedAt; }

    public Instant getLastRetryAt() { return lastRetryAt; }
    public void setLastRetryAt(Instant lastRetryAt) { this.lastRetryAt = lastRetryAt; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }

    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }

    public Boolean getAlertWebhookSent() { return alertWebhookSent; }
    public void setAlertWebhookSent(Boolean alertWebhookSent) { this.alertWebhookSent = alertWebhookSent; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}