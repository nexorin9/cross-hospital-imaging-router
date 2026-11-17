package com.crosshospital.imaging.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * HL7 触发报文死信实体（{@code dlq_hl7_messages}）。
 *
 * <p><b>与 {@code dlq_fetch_tasks} 的分工</b>：调阅任务死信记的是「一个 task 反复失败」，
 * 本实体记的是「一帧 HL7 报文在成为 task 之前就没被接住」——MSH 段缺失、PID 缺失、
 * 消息类型不支持、缺影像引用、找不到承接外院、入队失败六种。
 *
 * <p><b>为什么只存 MSH 摘要不存报文原文</b>：ORM^O01 报文里带患者姓名、出生日期、就诊号。
 * 死信表是要被运维与审计翻阅的表，把它当成第二份病历存原文，等于给院感与合规审查平白增加一处
 * 个人信息副本。本表只保留 {@code message_control_id}（回查 HIS 侧日志的唯一凭据）、
 * MSH 段头摘要与失败分类 —— 足以定位问题，又不留身份字段。
 *
 * <p>源主路径证据：{@code github_ref/dcm4che/dcm4che-net-hl7/.../HL7ConnectionMonitor.java:54-62}
 * —— {@code onMessageProcessed(conn, socket, msg, rsp, ex)} 同时带回应与异常，
 * 说明「处理结果」必须显式回报，不能默认成功。
 */
@Entity
@Table(name = "dlq_hl7_messages")
public class Hl7DeadLetter {

    /**
     * 死信分类。取值与 {@code hl7/segment-mappings.yml} 的 {@code protocol-errors} 块一一对应，
     * 并额外补了三条业务侧的死信原因（缺影像引用 / 无承接外院 / 入队失败）。
     */
    public enum FailureCode {
        /** MSH 段缺失或长度不足，无法抽取动态分隔符。 */
        INVALID_MSH("invalid_msh"),
        /** MSH-2 编码字符位数 ≠ 4。 */
        INVALID_DYNAMIC_DELIMITERS("dynamic_separator_anomaly"),
        /** MSH-9 消息类型不在本服务支持列表内。 */
        UNSUPPORTED_MESSAGE_TYPE("unsupported_message_type"),
        /** PID 段缺失或 PID-3（患者主索引）为空。 */
        MISSING_PID("missing_pid"),
        /** OBR-3 影像引用为空 —— 没有它就没法定位外院的那份检查。 */
        MISSING_STUDY_REFERENCE("missing_study_reference"),
        /** 报文本身合规，但按患者主索引找不到承接的外院节点（无可调阅对象）。 */
        NO_TARGET_PEER("no_target_peer"),
        /** 入队失败（数据库不可用 / 调阅队列已满）。 */
        ENQUEUE_FAILED("enqueue_failed");

        private final String code;

        FailureCode(String code) {
            this.code = code;
        }

        /** 数据库 / 死信表用的小写下划线码（与 segment-mappings.yml 一致）。 */
        public String code() {
            return code;
        }

        /**
         * 从数据库字符串反解枚举；未知值抛异常（不静默吞掉：死信表里出现未知分类说明
         * 代码与库表版本对不上，信息科必须看到）。
         */
        public static FailureCode fromDbValue(String value) {
            if (value == null) {
                throw new IllegalArgumentException("Hl7DeadLetter.FailureCode 字符串为 null");
            }
            for (FailureCode c : values()) {
                if (c.code.equals(value)) {
                    return c;
                }
            }
            throw new IllegalArgumentException("未知的 HL7 死信分类: '" + value + "'");
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "message_control_id")
    private String messageControlId;

    @Column(name = "route_key")
    private String routeKey;

    @Column(name = "sending_application")
    private String sendingApplication;

    @Column(name = "sending_facility")
    private String sendingFacility;

    @Column(name = "patient_mpi")
    private String patientMpi;

    @Column(name = "accession_number")
    private String accessionNumber;

    @Convert(converter = FailureCodeConverter.class)
    @Column(name = "failure_code", nullable = false)
    private FailureCode failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    /** MSH 段头摘要（不含患者身份字段）。 */
    @Column(name = "msh_excerpt")
    private String mshExcerpt;

    @Column(name = "payload_bytes", nullable = false)
    private Integer payloadBytes = 0;

    @Column(name = "remote_address")
    private String remoteAddress;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "alert_webhook_sent", nullable = false)
    private Boolean alertWebhookSent = Boolean.FALSE;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 死信分类的 JPA 转换器：实体用枚举，库里存小写下划线码。
     *
     * <p><b>为什么不能用 {@code @Enumerated(EnumType.STRING)}</b>：那会把枚举<b>常量名</b>
     * （{@code MISSING_PID} / {@code NO_TARGET_PEER}）写进 {@code failure_code} 列，
     * 而 V8 迁移给该列的 CHECK 约束只允许小写下划线码（{@code missing_pid} / …）。
     * 结果是<b>每一行死信都插不进去</b>（{@code Check constraint violation}），
     * 而 {@code Hl7DeadLetterHandler.persist()} 捕获异常只记一行日志
     * 「HL7 触发报文已进死信」—— 死信表于是永远空着，排障时看到的却是"已进死信"。
     *
     * <p>本转换器把两边的口径对齐到"小写下划线码"，与本仓库既有约定一致
     * （{@code FailureCodeRegistry.code()}、{@code dlq_fetch_tasks.failure_code}、
     * {@code audit_imaging_fetch.failure_code} 全是小写码）。
     *
     * <p>读回时用 {@link FailureCode#fromDbValue(String)}：遇到表里出现的未知码就抛异常，
     * 不静默吞掉 —— 那说明代码与库表版本对不上，信息科必须看见。
     */
    @Converter
    public static class FailureCodeConverter implements AttributeConverter<FailureCode, String> {

        @Override
        public String convertToDatabaseColumn(FailureCode attribute) {
            return attribute == null ? null : attribute.code();
        }

        @Override
        public FailureCode convertToEntityAttribute(String dbData) {
            return dbData == null ? null : FailureCode.fromDbValue(dbData);
        }
    }

    @PrePersist
    void onInsert() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (receivedAt == null) {
            receivedAt = now;
        }
        if (payloadBytes == null) {
            payloadBytes = 0;
        }
        if (alertWebhookSent == null) {
            alertWebhookSent = Boolean.FALSE;
        }
    }

    public Hl7DeadLetter() {
    }

    // -- getters / setters --
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMessageControlId() { return messageControlId; }
    public void setMessageControlId(String v) { this.messageControlId = v; }
    public String getRouteKey() { return routeKey; }
    public void setRouteKey(String v) { this.routeKey = v; }
    public String getSendingApplication() { return sendingApplication; }
    public void setSendingApplication(String v) { this.sendingApplication = v; }
    public String getSendingFacility() { return sendingFacility; }
    public void setSendingFacility(String v) { this.sendingFacility = v; }
    public String getPatientMpi() { return patientMpi; }
    public void setPatientMpi(String v) { this.patientMpi = v; }
    public String getAccessionNumber() { return accessionNumber; }
    public void setAccessionNumber(String v) { this.accessionNumber = v; }
    public FailureCode getFailureCode() { return failureCode; }
    public void setFailureCode(FailureCode v) { this.failureCode = v; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String v) { this.failureMessage = v; }
    public String getMshExcerpt() { return mshExcerpt; }
    public void setMshExcerpt(String v) { this.mshExcerpt = v; }
    public Integer getPayloadBytes() { return payloadBytes; }
    public void setPayloadBytes(Integer v) { this.payloadBytes = v; }
    public String getRemoteAddress() { return remoteAddress; }
    public void setRemoteAddress(String v) { this.remoteAddress = v; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant v) { this.receivedAt = v; }
    public Boolean getAlertWebhookSent() { return alertWebhookSent; }
    public void setAlertWebhookSent(Boolean v) { this.alertWebhookSent = v; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant v) { this.resolvedAt = v; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String v) { this.resolvedBy = v; }
    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String v) { this.resolutionNote = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
