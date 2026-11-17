package com.crosshospital.imaging.exception;

/**
 * HL7 v2.x 报文解析失败：MSH 段缺失 / 动态分隔符异常 / PID 缺失等结构性问题。
 *
 * <p>由 {@link com.crosshospital.imaging.service.hl7.Hl7MessageParser} 在段切分或字段抽取时抛出；
 * 触发路由器捕获后回 NAK 并断开连接（不假装成功），同时把 {@link FailureCode} 写进死信队列。
 */
public class Hl7ParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 解析失败的可聚合分类，与 {@code hl7/segment-mappings.yml} 中 {@code protocol-errors} 块一一对应。
     */
    public enum FailureCode {
        /** MSH 段缺失或长度 < 8（无法抽取动态分隔符）。 */
        INVALID_MSH("invalid_msh"),
        /** MSH-2 编码字符不合法（位数 ≠ 4 或含重复）。 */
        INVALID_DYNAMIC_DELIMITERS("dynamic_separator_anomaly"),
        /** 消息类型（MSH-9）不在本服务支持列表内（ORM^O01 / ADT^A04 / ADT^A08）。 */
        UNSUPPORTED_MESSAGE_TYPE("unsupported_message_type"),
        /** PID 段缺失或 PID-3（Patient Identifier List / MPI）为空。 */
        MISSING_PID("missing_pid");

        private final String code;

        FailureCode(String code) {
            this.code = code;
        }

        /** 数据库 / 死信表用的小写下划线码（与 segment-mappings.yml 一致）。 */
        public String code() {
            return code;
        }
    }

    private final FailureCode code;

    public Hl7ParseException(FailureCode code, String detail) {
        super(buildMessage(code, detail));
        this.code = code;
    }

    public FailureCode getCode() {
        return code;
    }

    private static String buildMessage(FailureCode code, String detail) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("HL7 解析失败 [").append(code.code()).append("]");
        if (detail != null && !detail.isBlank()) {
            sb.append(" 原因=").append(detail);
        }
        return sb.toString();
    }
}