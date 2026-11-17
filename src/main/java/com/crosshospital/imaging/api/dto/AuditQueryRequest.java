package com.crosshospital.imaging.api.dto;

import com.crosshospital.imaging.exception.ValidationException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** 审计只读查询条件（{@code GET /api/imaging/audit}）。 */
@Schema(name = "AuditQueryRequest", description = "审计查询条件：时间窗 + AET + 终态 + 操作人，条件为空即不筛选")
public class AuditQueryRequest {

    @Schema(description = "时间窗下界（ISO8601，含）", example = "2026-10-20T00:00:00Z")
    private String from;

    @Schema(description = "时间窗上界（ISO8601，不含）", example = "2026-10-21T00:00:00Z")
    private String to;

    @Schema(description = "发起方 AET", example = "IMAGER")
    private String fromAet;

    @Schema(description = "目标外院 AET", example = "PEER_HZ")
    private String toAet;

    @Schema(description = "审计终态", example = "task_notified")
    private String statusTerminal;

    @Schema(description = "操作人工号", example = "employee_007")
    private String who;

    @Schema(description = "页码（0 起）", example = "0")
    @Min(0)
    private int page = 0;

    @Schema(description = "每页行数（1..200）", example = "20")
    @Min(1)
    @Max(200)
    private int size = 20;

    /** ISO8601 字符串 → Instant；非法格式抛 400 而不是 500。 */
    private static Instant parse(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new ValidationException(field + " 不是合法 ISO8601 时间：" + value);
        }
    }

    public Instant fromInstant() { return parse(from, "from"); }
    public Instant toInstant() { return parse(to, "to"); }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    public String getFromAet() { return fromAet; }
    public void setFromAet(String fromAet) { this.fromAet = fromAet; }

    public String getToAet() { return toAet; }
    public void setToAet(String toAet) { this.toAet = toAet; }

    public String getStatusTerminal() { return statusTerminal; }
    public void setStatusTerminal(String statusTerminal) { this.statusTerminal = statusTerminal; }

    public String getWho() { return who; }
    public void setWho(String who) { this.who = who; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
}
