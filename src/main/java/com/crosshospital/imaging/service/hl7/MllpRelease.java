package com.crosshospital.imaging.service.hl7;

/**
 * MLLP 释放模式（Minimal Lower Layer Protocol release）。
 *
 * <p>源主路径证据：{@code github_ref/dcm4che/dcm4che-hl7/.../MLLPConnection.java:64-65, 96-97, 111}。
 * 源产品用 {@code MLLPRelease.MLLP1 / MLLP2} 决定两个对称行为：
 *
 * <pre>
 *   readMessage()  : b = mllpIn.readMessage();  if (release == MLLP2) writeACK();   // 收到消息要回 ACK
 *   writeMessage() : mllpOut.writeMessage(b); if (release == MLLP2) readACK();    // 发出消息要等 ACK
 * </pre>
 *
 * <p>本产品只做「接收触发」一侧（对端 = 本院 HIS），因此只消费
 * {@link #acknowledgesReceived()}；{@link #awaitsAckOnSend()} 保留是为了在回写回执
 * （task 19 的 HIS webhook / HL7 回执）时按同一模式决定是否等待对端 ACK。
 */
public enum MllpRelease {

    /** MLLP1：无确认模式。收到消息不回 ACK，对端也不期待 ACK。 */
    MLLP1(false, false),

    /** MLLP2：确认模式。收到一条完整帧必须回 ACK（0x06），出错回 NAK（0x15）。院内 HIS 绝大多数走这一档。 */
    MLLP2(true, true);

    private final boolean acknowledgesReceived;
    private final boolean awaitsAckOnSend;

    MllpRelease(boolean acknowledgesReceived, boolean awaitsAckOnSend) {
        this.acknowledgesReceived = acknowledgesReceived;
        this.awaitsAckOnSend = awaitsAckOnSend;
    }

    /** 收到完整帧后是否必须回写 ACK。 */
    public boolean acknowledgesReceived() {
        return acknowledgesReceived;
    }

    /** 本方主动发消息后是否必须等待对端 ACK（对端异常时按超时处理）。 */
    public boolean awaitsAckOnSend() {
        return awaitsAckOnSend;
    }

    /**
     * 宽松解析：环境变量 {@code HL7_MLLP_RELEASE_MODE} 允许 {@code mllp1/mllp2/1/2} 与大小写混写。
     *
     * @throws IllegalArgumentException 取值不可识别时抛出（配置型失败须尽早暴露，不静默回退到默认值）
     */
    public static MllpRelease parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("MLLP release mode 不能为 null（可选值：MLLP1 / MLLP2）");
        }
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (v.equals("MLLP1") || v.equals("1")) {
            return MLLP1;
        }
        if (v.equals("MLLP2") || v.equals("2")) {
            return MLLP2;
        }
        throw new IllegalArgumentException("MLLP release mode 取值非法: '" + raw + "'（可选值：MLLP1 / MLLP2）");
    }
}