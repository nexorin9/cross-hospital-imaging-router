package com.crosshospital.imaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * HIS 工单通知死信（V10）。
 *
 * <p><b>它记的是「成功之后的失败」</b>：影像已写入本院 PACS、任务已到 notified，
 * 但回调 HIS 工单三次都没送达。这类待办不能混进 {@code dlq_fetch_tasks}
 * （那是「调阅失败」），否则信息科会去排查外院网络，而真正该做的是补推一次通知。
 */
@Entity
@Table(name = "dlq_notifications")
public class DlqNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "task_id", nullable = false, columnDefinition = "uuid")
    private java.util.UUID taskId;

    @Column(name = "study_uid")
    private String studyUid;

    @Column(name = "target_pacs_aet")
    private String targetPacsAet;

    @Column(name = "webhook_host")
    private String webhookHost;

    @Column(name = "failure_code", nullable = false)
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @Column(name = "retry_attempts", nullable = false)
    private Integer retryAttempts = 0;

    @Column(name = "alert_webhook_sent", nullable = false)
    private Boolean alertWebhookSent = false;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (retryAttempts == null) retryAttempts = 0;
        if (alertWebhookSent == null) alertWebhookSent = false;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public java.util.UUID getTaskId() { return taskId; }
    public void setTaskId(java.util.UUID taskId) { this.taskId = taskId; }

    public String getStudyUid() { return studyUid; }
    public void setStudyUid(String studyUid) { this.studyUid = studyUid; }

    public String getTargetPacsAet() { return targetPacsAet; }
    public void setTargetPacsAet(String targetPacsAet) { this.targetPacsAet = targetPacsAet; }

    public String getWebhookHost() { return webhookHost; }
    public void setWebhookHost(String webhookHost) { this.webhookHost = webhookHost; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }

    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }

    public Integer getRetryAttempts() { return retryAttempts; }
    public void setRetryAttempts(Integer retryAttempts) { this.retryAttempts = retryAttempts; }

    public Boolean getAlertWebhookSent() { return alertWebhookSent; }
    public void setAlertWebhookSent(Boolean alertWebhookSent) { this.alertWebhookSent = alertWebhookSent; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
