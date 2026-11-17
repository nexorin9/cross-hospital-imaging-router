package com.crosshospital.imaging.service.hl7;

/**
 * MLLP 线路层常量。
 *
 * <p>源主路径证据：
 * <ul>
 *   <li>{@code MLLPConnection.java:55-56} —— {@code ACK = 0x06} / {@code NAK = 0x15}；</li>
 *   <li>{@code MLLPOutputStream} 字节码 —— {@code SOM = 11(0x0B)}、{@code finish()} 写 {@code EOM1 = 28(0x1C)}
 *       与 {@code EOM2 = 13(0x0D)}；</li>
 *   <li>{@code MLLPInputStream} 字节码 —— {@code hasMoreInput()} 首字节非 0x0B 抛
 *       {@code "Missing Start Block character"}；{@code read()} 遇 0x1C 后校验下一字节为 0x0D，
 *       否则抛 {@code "Invalid End Block character"}。</li>
 * </ul>
 *
 * <p>线上一条 MLLP 消息的字节形态固定为：{@code <VT> payload <FS><CR>}；ACK/NAK 是
 * payload 只有 1 字节的同形态帧（源产品 {@code write(int)} 路径即
 * {@code writeStartBlock() + write(ACK) + finish()}，产出 {@code 0B 06 1C 0D}）。
 */
public final class MllpConstants {

    /** Start of Message：{@code <VT>} = 0x0B。 */
    public static final byte SOM = 0x0B;

    /** End of Message 第 1 字节：{@code <FS>} = 0x1C。 */
    public static final byte EOM1 = 0x1C;

    /** End of Message 第 2 字节：{@code <CR>} = 0x0D。 */
    public static final byte EOM2 = 0x0D;

    /** ACK = 0x06（源产品 {@code MLLPConnection.ACK}）。 */
    public static final byte ACK = 0x06;

    /** NAK = 0x15（源产品 {@code MLLPConnection.NAK}）。 */
    public static final byte NAK = 0x15;

    /** ACK 帧字节数：SOM + ACK + EOM1 + EOM2。 */
    public static final int ACK_FRAME_LENGTH = 4;

    /** NAK 帧字节数：SOM + NAK + EOM1 + EOM2。 */
    public static final int NAK_FRAME_LENGTH = 4;

    private MllpConstants() {
    }

    /** 把 payload 包成 {@code <VT> payload <FS><CR>}。ACK/NAK 帧即 payload 为单字节的同形态。 */
    public static byte[] frame(byte[] payload) {
        byte[] out = new byte[payload.length + 3];
        out[0] = SOM;
        System.arraycopy(payload, 0, out, 1, payload.length);
        out[out.length - 2] = EOM1;
        out[out.length - 1] = EOM2;
        return out;
    }
}