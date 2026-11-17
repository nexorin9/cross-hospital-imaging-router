package com.crosshospital.imaging.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.UUID;

/**
 * 调阅受理回执（202 Accepted）。
 *
 * <p>异步任务入口只回 {@code taskId + queued}，任务状态请按 {@code links.self} 轮询。
 */
@Schema(name = "FetchResponse", description = "调阅任务受理回执")
public class FetchResponse {

    @Schema(description = "任务 ID（幂等键；同一 (patientMpi, studyUid) 未终结时复用）",
            example = "3f8a2f16-9a5e-4c11-9c53-2b9d1e0a7c44")
    private UUID taskId;

    @Schema(description = "受理时的状态，恒为 queued", example = "queued")
    private String status;

    @Schema(description = "后续可访问的资源链接", example = "{\"self\":\"/api/imaging/tasks/{taskId}\"}")
    private Map<String, String> links;

    public FetchResponse() {
    }

    public FetchResponse(UUID taskId, String status, Map<String, String> links) {
        this.taskId = taskId;
        this.status = status;
        this.links = links;
    }

    public static FetchResponse accepted(UUID taskId) {
        return new FetchResponse(taskId, "queued",
                Map.of("self", "/api/imaging/tasks/" + taskId));
    }

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, String> getLinks() { return links; }
    public void setLinks(Map<String, String> links) { this.links = links; }
}
