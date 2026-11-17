package com.crosshospital.imaging.service.hl7;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

/**
 * 一条已收全并通过 {@code <FS><CR>} 校验的 MLLP 帧（不可变值对象）。
 *
 * <p>{@link #mshLine()} 直接采纳源产品 {@code MLLPConnection.log(byte[], int, int)}（行 134-143）的
 * 日志技巧：HL7 v2 报文用 {@code \r} 作段分隔符，所以从首字节扫到第一个 {@code \r} 就等于
 * 取到 MSH 段头（MSH-9 消息类型就在其中），无需先做完整解析就能在日志里打出「谁发来的什么消息」。
 */
public final class MllpFrame {

    /** MSH 段头在日志里的最大显示长度，避免一条畸形超长行打爆日志。 */
    public static final int MSH_LINE_MAX = 256;

    private final byte[] payload;
    private final String remoteAddress;
    private final Instant receivedAt;
    private final long sequence;

    public MllpFrame(byte[] payload, String remoteAddress, Instant receivedAt, long sequence) {
        if (payload == null) {
            throw new IllegalArgumentException("MLLP 帧 payload 不能为 null");
        }
        this.payload = payload.clone();
        this.remoteAddress = remoteAddress == null ? "unknown" : remoteAddress;
        this.receivedAt = receivedAt == null ? Instant.now() : receivedAt;
        this.sequence = sequence;
    }

    /** 帧载荷（已去掉 {@code <VT>} 与 {@code <FS><CR>} 围栏）的副本。 */
    public byte[] payload() {
        return payload.clone();
    }

    /** 直接读内部数组，**调用方不得修改**（解码热路径，避免每次复制）。 */
    public byte[] payloadRef() {
        return payload;
    }

    public String remoteAddress() {
        return remoteAddress;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    /** 本进程内单调递增的帧序号，用于日志把 ACK 与来帧对上。 */
    public long sequence() {
        return sequence;
    }

    public int size() {
        return payload.length;
    }

    public boolean isEmpty() {
        return payload.length == 0;
    }

    /** MSH 段头文本（扫到第一个 {@code \r} 为止，超长截断）——采纳源产品日志取段方式。 */
    public String mshLine(Charset charset) {
        Charset cs = charset == null ? StandardCharsets.UTF_8 : charset;
        int end = 0;
        while (end < payload.length && payload[end] != '\r') {
            end++;
        }
        int limit = Math.min(end, MSH_LINE_MAX);
        return new String(payload, 0, limit, cs);
    }

    /** 人可读的一行摘要，直接进服务运行日志。 */
    public String summary(Charset charset) {
        return "seq=" + sequence
                + " peer=" + remoteAddress
                + " bytes=" + payload.length
                + " msh=[" + mshLine(charset).trim() + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MllpFrame other)) {
            return false;
        }
        return sequence == other.sequence && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(sequence) + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "MllpFrame{seq=" + sequence + ", peer=" + remoteAddress + ", bytes=" + payload.length + '}';
    }
}