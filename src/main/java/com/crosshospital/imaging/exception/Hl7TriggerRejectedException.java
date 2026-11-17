package com.crosshospital.imaging.exception;

import com.crosshospital.imaging.domain.Hl7DeadLetter;

/**
 * 触发报文被业务层拒绝 —— 报文本身能被解析，但缺了发起一次跨院调阅所必需的字段。
 *
 * <p>与 {@link Hl7ParseException} 的分工：
 * <ul>
 *   <li>{@link Hl7ParseException} = <b>协议层</b>问题：MSH 段缺失、编码字符异常、消息类型不支持、
 *       PID 缺失 —— 报文 HIS 自己就发错了；</li>
 *   <li>本异常 = <b>业务层</b>问题：报文格式没问题，但缺影像引用（OBR-3）等发起调阅的前提。</li>
 * </ul>
 *
 * <p>两者对线路层的处置一致：<b>回 NAK</b>，让 HIS 知道这条触发没被接住（重投或人工处理），
 * 不能回 ACK —— 回了 ACK 就等于告诉 HIS "已受理"，那次调阅却永远不会发生。
 */
public class Hl7TriggerRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Hl7DeadLetter.FailureCode failureCode;

    public Hl7TriggerRejectedException(Hl7DeadLetter.FailureCode failureCode, String detail) {
        super(buildMessage(failureCode, detail));
        this.failureCode = failureCode;
    }

    /** 死信分类（与 {@code dlq_hl7_messages.failure_code} 一致）。 */
    public Hl7DeadLetter.FailureCode getFailureCode() {
        return failureCode;
    }

    private static String buildMessage(Hl7DeadLetter.FailureCode code, String detail) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("HL7 触发被拒 [").append(code == null ? "unknown" : code.code()).append(']');
        if (detail != null && !detail.isBlank()) {
            sb.append(" 原因=").append(detail);
        }
        return sb.toString();
    }
}
