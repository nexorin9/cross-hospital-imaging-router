package com.crosshospital.imaging.api.dto;

import com.crosshospital.imaging.domain.AuditRow;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 审计查询响应。
 *
 * <p><b>刻意不暴露 row_checksum / prev_checksum</b>：链式校验和是用来验真的内部字段，
 * 把它发给查询方等于把「下一行该长什么样」也一起给了出去。
 * 完整性校验有独立的 {@code AuditChecksumVerifier} 路径，不混进查询接口。
 */
@Schema(name = "AuditRowResponse", description = "一行调阅审计（append-only，不含内部校验和字段）")
public class AuditRowResponse {

    @Schema(description = "审计行 ID")
    private Long id;

    @Schema(description = "操作人工号")
    private String who;

    @Schema(description = "事件时间（ISO8601）")
    private Instant whenTs;

    @Schema(description = "发起方 AET")
    private String fromAet;

    @Schema(description = "目标外院 AET")
    private String toAet;

    @Schema(description = "StudyInstanceUID")
    private String studyUid;

    @Schema(description = "SOP Class UID")
    private String sopClassUid;

    @Schema(description = "传输语法 UID")
    private String transferSyntax;

    @Schema(description = "重试次数")
    private Integer retryCount;

    @Schema(description = "审计终态")
    private String statusTerminal;

    @Schema(description = "失败码（如有）")
    private String failureCode;

    public AuditRowResponse() {
    }

    public static AuditRowResponse from(AuditRow row) {
        AuditRowResponse dto = new AuditRowResponse();
        dto.id = row.getId();
        dto.who = row.getWho();
        dto.whenTs = row.getWhenTs();
        dto.fromAet = row.getFromAet();
        dto.toAet = row.getToAet();
        dto.studyUid = row.getStudyUid();
        dto.sopClassUid = row.getSopClassUid();
        dto.transferSyntax = row.getTransferSyntax();
        dto.retryCount = row.getRetryCount();
        dto.statusTerminal = row.getStatusTerminal();
        dto.failureCode = row.getFailureCode();
        return dto;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getWho() { return who; }
    public void setWho(String who) { this.who = who; }

    public Instant getWhenTs() { return whenTs; }
    public void setWhenTs(Instant whenTs) { this.whenTs = whenTs; }

    public String getFromAet() { return fromAet; }
    public void setFromAet(String fromAet) { this.fromAet = fromAet; }

    public String getToAet() { return toAet; }
    public void setToAet(String toAet) { this.toAet = toAet; }

    public String getStudyUid() { return studyUid; }
    public void setStudyUid(String studyUid) { this.studyUid = studyUid; }

    public String getSopClassUid() { return sopClassUid; }
    public void setSopClassUid(String sopClassUid) { this.sopClassUid = sopClassUid; }

    public String getTransferSyntax() { return transferSyntax; }
    public void setTransferSyntax(String transferSyntax) { this.transferSyntax = transferSyntax; }

    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }

    public String getStatusTerminal() { return statusTerminal; }
    public void setStatusTerminal(String statusTerminal) { this.statusTerminal = statusTerminal; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
}
