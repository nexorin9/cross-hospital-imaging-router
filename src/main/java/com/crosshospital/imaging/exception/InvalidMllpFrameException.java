package com.crosshospital.imaging.exception;

/**
 * MLLP 线路层帧异常：对端发来的字节流不构成一条合法 {@code <VT> payload <FS><CR>} 帧。
 *
 * <p>源主路径证据：源产品在 {@code MLLPInputStream} 里把同一类问题以
 * {@code IOException("Missing Start Block character")} / {@code IOException("Invalid End Block character")}
 * 表达，{@code MLLPConnection.readACK()} 则把对端半关连接表达为
 * {@code IOException("Connection closed by receiver")}。本产品把这些线路层失败细分成
 * {@link FrameError} 枚举值，供信息科从日志一眼区分「HIS 配置错了」与「网络断了」——
 * 这是把源产品的 IOException 字符串升级为可聚合的失败码，语义不变。
 */
public class InvalidMllpFrameException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 帧错误的可聚合分类，直接落日志与后续死信表。 */
    public enum FrameError {
        /** 首字节不是 0x0B（{@code <VT>}）。典型成因：HIS 侧 MLLP 通道配置未勾「带块模式」，直接裸发报文。 */
        MISSING_START_BLOCK("missing_start_block"),
        /** 收到 0x1C（{@code <FS>}）后下一字节不是 0x0D（{@code <CR>}）。典型成因：行结束符配成 {@code \r\n} 或 {@code \n}。 */
        MISSING_END_BLOCK("missing_end_block"),
        /** payload 超过 {@code hl7.trigger.max-frame-bytes}。宁可断开也不把内存吃光。 */
        FRAME_TOO_LARGE("frame_too_large"),
        /** 帧尚未收全对端就断开连接（收到 0x0B 或 0x1C 后 TCP 关闭）。 */
        CONNECTION_CLOSED_IN_FRAME("connection_closed_in_frame");

        private final String code;

        FrameError(String code) {
            this.code = code;
        }

        /** 数据库 / 死信表用的小写下划线码。 */
        public String code() {
            return code;
        }
    }

    private final FrameError error;
    private final String remoteAddress;
    private final long frameLength;

    public InvalidMllpFrameException(FrameError error, String remoteAddress, long frameLength, String detail) {
        super(buildMessage(error, remoteAddress, frameLength, detail));
        this.error = error;
        this.remoteAddress = remoteAddress == null ? "unknown" : remoteAddress;
        this.frameLength = frameLength;
    }

    public FrameError getError() {
        return error;
    }

    /** 出问题的对端地址（IP:port），供信息科定位是哪台 HIS 主机在发坏帧。 */
    public String getRemoteAddress() {
        return remoteAddress;
    }

    /** 异常发生时已累计收到的字节数。 */
    public long getFrameLength() {
        return frameLength;
    }

    private static String buildMessage(FrameError error, String remoteAddress, long frameLength, String detail) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("MLLP 帧非法 [").append(error.code()).append("] 对端=")
          .append(remoteAddress == null ? "unknown" : remoteAddress)
          .append(" 已收字节=").append(frameLength);
        if (detail != null && !detail.isBlank()) {
            sb.append(" 原因=").append(detail);
        }
        return sb.toString();
    }
}