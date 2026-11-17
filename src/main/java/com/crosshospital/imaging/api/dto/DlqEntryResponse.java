package com.crosshospital.imaging.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.crosshospital.imaging.domain.DlqEntry;
import com.crosshospital.imaging.domain.FetchTask;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 死信队列响应 DTO。
 *
 * <p>信息科在「未处置死信」列表上要能一眼看出三件事：
 * <ol>
 *   <li><b>哪条单子卡住了</b> —— {@code taskId} + 任务上下文（外院 AET / 病人主索引 / StudyInstanceUID）；</li>
 *   <li><b>为什么卡住</b> —— {@code failureCode}（与失败码词表同一口径）+ {@code failureMessage}；</li>
 *   <li><b>试了几次</b> —— {@code retryAttempts}（0 = 一次就挂，3 = 重试三次还挂，两者的处置优先级完全不同）。</li>
 * </ol>
 *
 * <p>任务上下文（{@code peerAet / patientMpi / studyUid / taskStatus}）不在死信表里 ——
 * 死信表只记任务号，上下文从 {@code imaging_fetch_tasks} 关联补齐；任务行已清理时
 * 这几项为 {@code null}，{@code taskContextPresent=false} 明确标出「这条死信没有任务上下文」。
 */
@Schema(name = "DlqEntryResponse", description = "调阅任务死信（未处置列表项 / 处置结果）")
public class DlqEntryResponse {

    @Schema(description = "死信行 ID", example = "17")
    private Long id;

    @Schema(description = "关联的调阅任务 UUID；无任务上下文时为零值 UUID", example = "3f1b…")
    private String taskId;

    @Schema(description = "是否带真实任务上下文（false = 协议层失败，未走任务体系）", example = "true")
    private Boolean taskContextPresent;

    @Schema(description = "失败码（与 FailureCodeRegistry 同一词表）", example = "connection_refused")
    private String failureCode;

    @Schema(description = "失败消息原文")
    private String failureMessage;

    @Schema(description = "失败前累计的重试次数", example = "3")
    private Integer retryAttempts;

    @Schema(description = "首次失败时间（ISO-8601）")
    private Instant firstFailedAt;

    @Schema(description = "最近一次重试时间（ISO-8601）")
    private Instant lastRetryAt;

    @Schema(description = "处置时间（ISO-8601）；未处置为 null")
    private Instant resolvedAt;

    @Schema(description = "处置人（工号）")
    private String resolvedBy;

    @Schema(description = "处置说明")
    private String resolutionNote;

    @Schema(description = "是否已推送告警 webhook", example = "true")
    private Boolean alertWebhookSent;

    @Schema(description = "入队时间（ISO-8601）")
    private Instant createdAt;

    @Schema(description = "外院 AE Title（任务上下文）", example = "PEER_HZ_CITY_HOSP")
    private String peerAet;

    @Schema(description = "病人主索引（任务上下文）")
    private String patientMpi;

    @Schema(description = "StudyInstanceUID（任务上下文）")
    private String studyUid;

    @Schema(description = "任务当前状态（任务上下文）", example = "failed")
    private String taskStatus;

    /**
     * 由死信行 + （可选）任务上下文组装。
     */
    public static DlqEntryResponse from(DlqEntry entry, FetchTask task) {
        DlqEntryResponse r = new DlqEntryResponse();
        if (entry == null) {
            return r;
        }
        r.id = entry.getId();
        r.taskId = entry.getTaskId() == null ? null : entry.getTaskId().toString();
        r.taskContextPresent = task != null;
        r.failureCode = entry.getFailureCode();
        r.failureMessage = entry.getFailureMessage();
        r.retryAttempts = entry.getRetryAttempts();
        r.firstFailedAt = entry.getFirstFailedAt();
        r.lastRetryAt = entry.getLastRetryAt();
        r.resolvedAt = entry.getResolvedAt();
        r.resolvedBy = entry.getResolvedBy();
        r.resolutionNote = entry.getResolutionNote();
        r.alertWebhookSent = entry.getAlertWebhookSent();
        r.createdAt = entry.getCreatedAt();
        if (task != null) {
            r.peerAet = task.getPeerAet();
            r.patientMpi = task.getPatientMpi();
            r.studyUid = task.getStudyUid();
            r.taskStatus = task.getStatus() == null ? null : task.getStatus().toDbValue();
        }
        return r;
    }

    public Long getId() { return id; }
    public String getTaskId() { return taskId; }
    public Boolean getTaskContextPresent() { return taskContextPresent; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public Integer getRetryAttempts() { return retryAttempts; }
    public Instant getFirstFailedAt() { return firstFailedAt; }
    public Instant getLastRetryAt() { return lastRetryAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public String getResolutionNote() { return resolutionNote; }
    public Boolean getAlertWebhookSent() { return alertWebhookSent; }
    public Instant getCreatedAt() { return createdAt; }
    public String getPeerAet() { return peerAet; }
    public String getPatientMpi() { return patientMpi; }
    public String getStudyUid() { return studyUid; }
    public String getTaskStatus() { return taskStatus; }
}
