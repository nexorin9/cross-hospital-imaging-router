package com.crosshospital.imaging.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * 跨院影像调阅任务实体（{@code imaging_fetch_tasks}）。
 *
 * <p>状态机由 {@link TaskStatus} 定义（queued → querying → pulling → stored →
 * notified，failed 是终态分支）；数据库 UNIQUE INDEX {@code idx_fetch_tasks_idem}
 * 保证同一 (patient_mpi, study_uid) 在未终结期间只允许存在一行。
 *
 * <p>{@link Version} 乐观锁字段用于并发更新冲突检测；task_id 由调用方生成
 * UUID（不依赖 DB sequence），便于异步任务 ID 在 HIS 工单与本表之间同步。
 *
 * <p>源主路径证据：dcm4che-net-audit 应用层审计 + 路由表 + MPI 寻址（融合增量）。
 */
@Entity
@Table(name = "imaging_fetch_tasks")
public class FetchTask {

    @Id
    @Column(name = "task_id", nullable = false, columnDefinition = "uuid")
    private UUID taskId;

    @Column(name = "patient_mpi", nullable = false)
    private String patientMpi;

    @Column(name = "study_uid", nullable = false)
    private String studyUid;

    @Column(name = "sop_class_uid")
    private String sopClassUid;

    @Column(name = "accession_no")
    private String accessionNo;

    @Column(name = "peer_aet", nullable = false)
    private String peerAet;

    @Convert(converter = TaskStatusConverter.class)
    @Column(name = "status", nullable = false)
    private TaskStatus status = TaskStatus.QUEUED;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "ai_summary_id")
    private Long aiSummaryId;

    @Column(name = "initiated_by", nullable = false)
    private String initiatedBy;

    @Column(name = "initiated_src", nullable = false)
    private String initiatedSrc = "api";

    @Column(name = "sop_instance_count")
    private Integer sopInstanceCount;

    @Column(name = "bytes_total")
    private Long bytesTotal;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version")
    private Long rowVersion;

    @PrePersist
    void onInsert() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = TaskStatus.QUEUED;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * 状态列的 JPA 转换器：实体用枚举，库里存小写码。
     *
     * <p><b>为什么不能用 {@code @Enumerated(EnumType.STRING)}</b>：那会把枚举<b>常量名</b>
     * （{@code QUEUED} / {@code QUERYING}）写进 {@code status} 列，而 V2 迁移给该列的
     * CHECK 约束只允许小写码（{@code queued} / …）。两边对不上时，真实 PostgreSQL 上
     * <b>每一行调阅任务都插不进去</b>（{@code Check constraint violation}）——
     * 任务表永远是空的，而入队那一步的错误又被 {@code FetchTaskService.enqueue()}
     * 往上抛成一句笼统的失败，排障时看到的现象与真相完全相反。
     *
     * <p>本转换器把两边的口径对齐到「小写码」，与本仓库既有约定一致
     * （{@code TaskStatus.toDbValue()}、V2 的 {@code status} CHECK 清单、
     * {@code audit_imaging_fetch.status_terminal} 全是小写）。
     *
     * <p>读回时用 {@link TaskStatus#fromDbValue(String)}：遇到表里出现的未知码就抛异常，
     * 不静默吞掉 —— 那说明代码与库表版本对不上，信息科必须看见。
     */
    @Converter
    public static class TaskStatusConverter implements AttributeConverter<TaskStatus, String> {

        @Override
        public String convertToDatabaseColumn(TaskStatus attribute) {
            return attribute == null ? null : attribute.toDbValue();
        }

        @Override
        public TaskStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : TaskStatus.fromDbValue(dbData);
        }
    }

    // -- getters / setters --
    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public String getPatientMpi() { return patientMpi; }
    public void setPatientMpi(String patientMpi) { this.patientMpi = patientMpi; }

    public String getStudyUid() { return studyUid; }
    public void setStudyUid(String studyUid) { this.studyUid = studyUid; }

    public String getSopClassUid() { return sopClassUid; }
    public void setSopClassUid(String sopClassUid) { this.sopClassUid = sopClassUid; }

    public String getAccessionNo() { return accessionNo; }
    public void setAccessionNo(String accessionNo) { this.accessionNo = accessionNo; }

    public String getPeerAet() { return peerAet; }
    public void setPeerAet(String peerAet) { this.peerAet = peerAet; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }

    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }

    public Long getAiSummaryId() { return aiSummaryId; }
    public void setAiSummaryId(Long aiSummaryId) { this.aiSummaryId = aiSummaryId; }

    public String getInitiatedBy() { return initiatedBy; }
    public void setInitiatedBy(String initiatedBy) { this.initiatedBy = initiatedBy; }

    public String getInitiatedSrc() { return initiatedSrc; }
    public void setInitiatedSrc(String initiatedSrc) { this.initiatedSrc = initiatedSrc; }

    public Integer getSopInstanceCount() { return sopInstanceCount; }
    public void setSopInstanceCount(Integer sopInstanceCount) { this.sopInstanceCount = sopInstanceCount; }

    public Long getBytesTotal() { return bytesTotal; }
    public void setBytesTotal(Long bytesTotal) { this.bytesTotal = bytesTotal; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getRowVersion() { return rowVersion; }
    public void setRowVersion(Long rowVersion) { this.rowVersion = rowVersion; }
}