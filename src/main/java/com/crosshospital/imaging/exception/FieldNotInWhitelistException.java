package com.crosshospital.imaging.exception;

/**
 * 业务层读取的 HL7 字段不在本服务段位置白名单内时抛出。
 *
 * <p>源主路径对照：{@code github_ref/dcm4che/dcm4che-hl7/.../HL7Parser.java:55-213} 把 MSH/PID/PV1
 * 全字段以 SAX 回调交给业务方，不做白名单；本服务为「只读 HL7 触发」的最小权限模型，必须在
 * 字段读取层就把没在白名单的字段挡住，避免业务方读到与本方待诊流程无关的字段（HIV 标记、
 * 医保结算字段等）而误入审计或下游 PACS。
 *
 * <p>白名单字段位置参见 {@code src/main/resources/hl7/segment-mappings.yml} 的
 * {@code hl7.whitelist} 块（PID-3 / PID-5 / PID-7 / PV1-19 / OBR-3 / OBR-4 等）。
 */
public class FieldNotInWhitelistException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String segment;
    private final int field;

    public FieldNotInWhitelistException(String segment, int field) {
        super(buildMessage(segment, field));
        this.segment = segment;
        this.field = field;
    }

    public String getSegment() {
        return segment;
    }

    public int getField() {
        return field;
    }

    private static String buildMessage(String segment, int field) {
        return "HL7 字段不在白名单内：segment=" + segment + " field=" + field
                + "（白名单见 hl7/segment-mappings.yml）";
    }
}