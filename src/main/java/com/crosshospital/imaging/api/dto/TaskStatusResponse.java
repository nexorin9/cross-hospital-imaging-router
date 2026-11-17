package com.crosshospital.imaging.api.dto;

import com.crosshospital.imaging.domain.FetchTask;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** 调阅任务状态查询响应（五态机终值可查）。 */
@Schema(name = "TaskStatusResponse", description = "调阅任务状态（queued / querying / pulling / stored / notified / failed）")
public class TaskStatusResponse {

    @Schema(description = "任务 ID", example = "3f8a2f16-9a5e-4c11-9c53-2b9d1e0a7c44")
    private UUID taskId;

    @Schema(description = "当前状态", example = "notified")
    private String status;

    @Schema(description = "已重试次数", example = "0")
    private Integer retryCount;

    @Schema(description = "失败码（仅 failed 时有值）", example = "connection_refused")
    private String failureCode;

    @Schema(description = "创建时间（ISO8601）")
    private Instant createdAt;

    @Schema(description = "最后更新时间（ISO8601）")
    private Instant updatedAt;

    public TaskStatusResponse() {
    }

    /** 从任务实体映射（只暴露调用方需要的字段，不泄露内部列）。 */
    public static TaskStatusResponse from(FetchTask task) {
        TaskStatusResponse dto = new TaskStatusResponse();
        dto.taskId = task.getTaskId();
        dto.status = task.getStatus() == null ? null : task.getStatus().toDbValue();
        dto.retryCount = task.getRetryCount();
        dto.failureCode = task.getFailureCode();
        dto.createdAt = task.getCreatedAt();
        dto.updatedAt = task.getUpdatedAt();
        return dto;
    }

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
