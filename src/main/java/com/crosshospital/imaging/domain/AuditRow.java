package com.crosshospital.imaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * append-only 审计表实体（{@code audit_imaging_fetch}）。
 *
 * <p>数据库层有 {@code audit_no_update} 触发器（BEFORE UPDATE OR DELETE OR TRUNCATE）
 * 拦截任何修改；本类用 {@link Immutable} 注解 + 显式不暴露 setter / 方法实现双重防御。
 *
 * <p>链式 row_checksum = SHA-256(prev_checksum + 字段拼接)；空表 prev_checksum
 * 为 32 字节 0。完整计算逻辑见
 * {@link com.crosshospital.imaging.security.AuditChecksum}。
 *
 * <p>源主路径证据：dcm4che-net-audit 应用层审计模块 + 区块链式校验和模式。
 */
@Entity
@Immutable
@Table(name = "audit_imaging_fetch")
public class AuditRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "who", nullable = false)
    private String who;

    @Column(name = "when_ts", nullable = false)
    private Instant whenTs;

    @Column(name = "from_aet")
    private String fromAet;

    @Column(name = "to_aet")
    private String toAet;

    @Column(name = "study_uid")
    private String studyUid;

    @Column(name = "sop_class_uid")
    private String sopClassUid;

    @Column(name = "transfer_syntax")
    private String transferSyntax;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "status_terminal", nullable = false)
    private String statusTerminal;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "row_checksum", nullable = false)
    private byte[] rowChecksum;

    @Column(name = "prev_checksum", nullable = false)
    private byte[] prevChecksum;

    @PrePersist
    void onInsert() {
        // 截断到微秒：与 PostgreSQL TIMESTAMPTZ 存储精度一致，
        // 保证「插入时算出的 row_checksum」与「从库里读回后重算的 row_checksum」相同。
        if (whenTs == null) whenTs = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (retryCount == null) retryCount = 0;
    }

    /** 默认无参构造；持久化字段由 {@code AuditService.record} 显式赋值。 */
    public AuditRow() {
    }

    // -- getters only (no setters; append-only by construction) --
    public Long getId() { return id; }
    public String getWho() { return who; }
    public Instant getWhenTs() { return whenTs; }
    public String getFromAet() { return fromAet; }
    public String getToAet() { return toAet; }
    public String getStudyUid() { return studyUid; }
    public String getSopClassUid() { return sopClassUid; }
    public String getTransferSyntax() { return transferSyntax; }
    public Integer getRetryCount() { return retryCount; }
    public String getStatusTerminal() { return statusTerminal; }
    public String getFailureCode() { return failureCode; }
    public byte[] getRowChecksum() { return rowChecksum; }
    public byte[] getPrevChecksum() { return prevChecksum; }

    /**
     * Builder 风格写入接口：append-only 表的合法写入只能"新插入"，所以
     * 字段一次性设置完毕后再 save；不暴露 setter 是为了在编译期阻止对
     * 已持久化对象做字段修改（即便反射也受 {@code @Immutable} 防护）。
     */
    public AuditRow withWho(String who) { this.who = who; return this; }
    public AuditRow withWhenTs(Instant whenTs) { this.whenTs = whenTs; return this; }
    public AuditRow withFromAet(String fromAet) { this.fromAet = fromAet; return this; }
    public AuditRow withToAet(String toAet) { this.toAet = toAet; return this; }
    public AuditRow withStudyUid(String studyUid) { this.studyUid = studyUid; return this; }
    public AuditRow withSopClassUid(String sopClassUid) { this.sopClassUid = sopClassUid; return this; }
    public AuditRow withTransferSyntax(String transferSyntax) { this.transferSyntax = transferSyntax; return this; }
    public AuditRow withRetryCount(Integer retryCount) { this.retryCount = retryCount; return this; }
    public AuditRow withStatusTerminal(String statusTerminal) { this.statusTerminal = statusTerminal; return this; }
    public AuditRow withFailureCode(String failureCode) { this.failureCode = failureCode; return this; }
    public AuditRow withChecksums(byte[] prev, byte[] row) {
        this.prevChecksum = prev;
        this.rowChecksum = row;
        return this;
    }
}