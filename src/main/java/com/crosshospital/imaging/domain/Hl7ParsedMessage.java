package com.crosshospital.imaging.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条已解析的 HL7 v2.x 触发报文（不可变值对象）。
 *
 * <p>由 {@link com.crosshospital.imaging.service.hl7.Hl7MessageParser#parse} 产出；
 * 业务路由器（task 19）拿到此对象后按 {@link #messageType()} 分发到 {@code OrderMessageHandler}
 * 或 {@code AdmitMessageHandler} 等具体处理器。
 *
 * <p>字段命名沿用 HL7 v2.x 段位置口径（PID-3 → patientMpi / OBR-3 → accessionNumber / 等），
 * 命名不替换为产品内部黑话，便于对接 HL7 工程师复核。
 *
 * <p><b>字段缺失语义</b>：本产品只读必要字段，未在白名单内的字段不会被填入容器，
 * 也<b>不会</b>被无脑置空或填占位串——调用方拿到 {@code null} 即「HIS 这条报文里没带」。
 */
public final class Hl7ParsedMessage {

    /** ORM^O01 — 影像 / 检验医嘱（新单 / 撤销 / 状态变更）。 */
    public static final String MSG_TYPE_ORM = "ORM";
    /** ADT^A04 — 患者入院登记。 */
    public static final String MSG_TYPE_ADT = "ADT";
    /** ORM^O01 的 trigger event。 */
    public static final String TRIGGER_O01 = "O01";
    /** ADT^A04 的 trigger event。 */
    public static final String TRIGGER_A04 = "A04";
    /** ADT^A08 的 trigger event（修改患者基本信息）。 */
    public static final String TRIGGER_A08 = "A08";

    private final String messageType;
    private final String triggerEvent;
    private final String messageControlId;
    private final String sendingApplication;
    private final String sendingFacility;
    private final String patientMpi;
    private final String patientName;
    private final String patientDob;
    private final String visitNumber;
    private final String accessionNumber;
    private final Instant receivedAt;
    private final String remoteAddress;
    private final String fieldSeparator;

    private Hl7ParsedMessage(Builder b) {
        this.messageType = b.messageType;
        this.triggerEvent = b.triggerEvent;
        this.messageControlId = b.messageControlId;
        this.sendingApplication = b.sendingApplication;
        this.sendingFacility = b.sendingFacility;
        this.patientMpi = b.patientMpi;
        this.patientName = b.patientName;
        this.patientDob = b.patientDob;
        this.visitNumber = b.visitNumber;
        this.accessionNumber = b.accessionNumber;
        this.receivedAt = b.receivedAt;
        this.remoteAddress = b.remoteAddress == null ? "unknown" : b.remoteAddress;
        this.fieldSeparator = b.fieldSeparator;
    }

    /**
     * HL7 消息类型（MSH-9.1），如 {@code ORM} / {@code ADT}。
     * 路由器按此字段做一级分发。
     */
    public String messageType() {
        return messageType;
    }

    /**
     * HL7 trigger event（MSH-9.2），如 {@code O01} / {@code A04} / {@code A08}。
     * 路由器按 {@code messageType + "^" + triggerEvent} 拼出唯一路由键。
     */
    public String triggerEvent() {
        return triggerEvent;
    }

    /** HL7 消息控制 ID（MSH-10），用于 ACK 回执的 MSA-2 与跨段对账。 */
    public String messageControlId() {
        return messageControlId;
    }

    public String sendingApplication() {
        return sendingApplication;
    }

    public String sendingFacility() {
        return sendingFacility;
    }

    /**
     * 患者主索引（PID-3 Patient Identifier List），跨院调阅的主键之一；
     * 与 {@link #accessionNumber()} 一起构成调阅任务的复合幂等键。
     */
    public String patientMpi() {
        return patientMpi;
    }

    /** 患者姓名（PID-5 Patient Name），仅入审计日志，不入业务状态。 */
    public String patientName() {
        return patientName;
    }

    /** 患者出生日期（PID-7），格式 yyyyMMdd，供影像科人工核对（同名同姓甄别）。 */
    public String patientDob() {
        return patientDob;
    }

    /** 就诊号（PV1-19 Visit Number），供本院 HIS 工单回执关联。 */
    public String visitNumber() {
        return visitNumber;
    }

    /**
     * 检查申请号（OBR-3 Filler Order Number），即 RIS/PACS 的 accession number；
     * 在跨院场景下作为外院影像的调阅主键（外院可能以 accession 号代替 StudyInstanceUID
     * 暴露给本院），与 {@link #patientMpi()} 一起构成调阅任务的复合幂等键。
     */
    public String accessionNumber() {
        return accessionNumber;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public String remoteAddress() {
        return remoteAddress;
    }

    /** MSH-1 字段分隔符（{@code |} 或自定义），仅日志/排障使用。 */
    public String fieldSeparator() {
        return fieldSeparator;
    }

    /** 拼出 {@code messageType^triggerEvent} 的路由键（如 {@code ORM^O01}）。 */
    public String routeKey() {
        return messageType + "^" + triggerEvent;
    }

    /**
     * 患者标识 —— PID-3（Patient Identifier List）的<b>第一个分量</b>。
     *
     * <p>PID-3 在 HL7 v2.x 里是一个复合字段，形如
     * {@code PAT_MPI_EXAMPLE^^^HIS_MPI^MR}：第 1 分量是患者标识值，
     * 第 2 分量是赋号机构，第 3 分量是标识类型码；多个标识之间用 {@code ~} 分隔。
     *
     * <p>跨院调阅要的是<b>稳定的患者标识</b>，不是整串复合值：
     * 整串带着赋号机构与类型码，不同 HIS 的版本写法可能不同，
     * 直接当 {@code patient_mpi} 落库会让幂等键（patient_mpi + study_uid）失准。
     */
    public String patientIdentifier() {
        return firstComponent(patientMpi);
    }

    /**
     * 取 HL7 复合字段的第一个分量：先按重复符 {@code ~} 截断，再按分量符 {@code ^} 截断。
     *
     * <p>{@code "A^^^HIS^MR"} → {@code "A"}；{@code "A^^^X~B^^^Y"} → {@code "A"}；
     * {@code "A"} → {@code "A"}；空白结果返回 {@code null}。
     */
    public static String firstComponent(String fieldValue) {
        if (fieldValue == null) {
            return null;
        }
        String s = fieldValue;
        int rep = s.indexOf('~');
        if (rep >= 0) {
            s = s.substring(0, rep);
        }
        int comp = s.indexOf('^');
        if (comp >= 0) {
            s = s.substring(0, comp);
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /** 此消息是否包含调阅所需的最少字段（patient_mpi + accession_number）。 */
    public boolean hasMinimumFetchFields() {
        return patientMpi != null && !patientMpi.isBlank()
                && accessionNumber != null && !accessionNumber.isBlank();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Hl7ParsedMessage that)) {
            return false;
        }
        return Objects.equals(messageType, that.messageType)
                && Objects.equals(triggerEvent, that.triggerEvent)
                && Objects.equals(messageControlId, that.messageControlId)
                && Objects.equals(patientMpi, that.patientMpi)
                && Objects.equals(accessionNumber, that.accessionNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageType, triggerEvent, messageControlId, patientMpi, accessionNumber);
    }

    @Override
    public String toString() {
        return "Hl7ParsedMessage{route=" + routeKey()
                + " ctrl=" + messageControlId
                + " mpi=" + patientMpi
                + " acc=" + accessionNumber
                + " visit=" + visitNumber
                + " sender=" + sendingApplication + "/" + sendingFacility
                + " peer=" + remoteAddress + "}";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String messageType;
        private String triggerEvent;
        private String messageControlId;
        private String sendingApplication;
        private String sendingFacility;
        private String patientMpi;
        private String patientName;
        private String patientDob;
        private String visitNumber;
        private String accessionNumber;
        private Instant receivedAt;
        private String remoteAddress;
        private String fieldSeparator;

        public Builder messageType(String v) {
            this.messageType = v;
            return this;
        }

        public Builder triggerEvent(String v) {
            this.triggerEvent = v;
            return this;
        }

        public Builder messageControlId(String v) {
            this.messageControlId = v;
            return this;
        }

        public Builder sendingApplication(String v) {
            this.sendingApplication = v;
            return this;
        }

        public Builder sendingFacility(String v) {
            this.sendingFacility = v;
            return this;
        }

        public Builder patientMpi(String v) {
            this.patientMpi = v;
            return this;
        }

        public Builder patientName(String v) {
            this.patientName = v;
            return this;
        }

        public Builder patientDob(String v) {
            this.patientDob = v;
            return this;
        }

        public Builder visitNumber(String v) {
            this.visitNumber = v;
            return this;
        }

        public Builder accessionNumber(String v) {
            this.accessionNumber = v;
            return this;
        }

        public Builder receivedAt(Instant v) {
            this.receivedAt = v;
            return this;
        }

        public Builder remoteAddress(String v) {
            this.remoteAddress = v;
            return this;
        }

        public Builder fieldSeparator(String v) {
            this.fieldSeparator = v;
            return this;
        }

        public Hl7ParsedMessage build() {
            return new Hl7ParsedMessage(this);
        }
    }
}