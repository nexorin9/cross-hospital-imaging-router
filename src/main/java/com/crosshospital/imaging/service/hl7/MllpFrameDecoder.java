package com.crosshospital.imaging.service.hl7;

import java.time.Instant;
import java.util.List;
import java.util.function.LongSupplier;

import com.crosshospital.imaging.exception.InvalidMllpFrameException;
import com.crosshospital.imaging.exception.InvalidMllpFrameException.FrameError;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.AttributeKey;

/**
 * MLLP 帧解码器：把任意 TCP 分片的字节流还原成一条条 {@code <VT> payload <FS><CR>} 帧。
 *
 * <p>源主路径证据：{@code github_ref/dcm4che/dcm4che-hl7/.../MLLPConnection.java}（读消息入口
 * {@code readMessage()} → {@code MLLPInputStream.readMessage()}）+ {@code MLLPInputStream} 字节码。
 * 源产品的读路径逐字节推进，语义为：首字节必须是 {@code SOM=0x0B}，随后一直读到 {@code EOM1=0x1C}，
 * 再校验下一字节是 {@code EOM2=0x0D}；流提前结束抛 {@code EOFException}。本类把同一状态机搬到 Netty
 * 的 {@code ByteToMessageDecoder} 上，状态跨 {@code decode()} 调用保持（TCP 分片天然打断读取），
 * 并补三件源产品在阻塞 Socket 上靠「抛 IOException → 上层关连接」隐式承担的事：
 *
 * <ol>
 *   <li><b>容量闸门</b>：payload 累计超 {@code maxFrameBytes} 立即拒收（源产品无上限，
 *       阻塞式读在内存里无限增长，Netty 事件循环里则会把整个 worker 线程拖垮）；</li>
 *   <li><b>可观测</b>：失败抛 {@link InvalidMllpFrameException} 并带失败码与对端地址，
 *       而不是一个看不出根因的 IOException 字符串；</li>
 *   <li><b>连接级语义</b>：一帧未收全对端就断开 → 单独报 {@code CONNECTION_CLOSED_IN_FRAME}，
 *       区别于「HIS 配错了行结束符」与「主动关连接」。</li>
 * </ol>
 *
 * <p>解码产出的 {@link MllpFrame} 不含围栏字节，可直接交给 task 18 的 HL7 段解析。
 */
public class MllpFrameDecoder extends ByteToMessageDecoder {

    /** 挂在 Channel 上的对端地址，便于在解码异常里取用（无需层层传参）。 */
    public static final AttributeKey<String> REMOTE_ADDRESS = AttributeKey.valueOf("mllp.remoteAddress");

    private static final int STATE_WAIT_START = 0;
    private static final int STATE_IN_BLOCK = 1;
    private static final int STATE_GOT_FS = 2;

    private final int maxFrameBytes;
    private final LongSupplier sequenceSupplier;

    private int state = STATE_WAIT_START;
    private ByteBuf payload;
    private int payloadLength;

    public MllpFrameDecoder(int maxFrameBytes) {
        this(maxFrameBytes, new LongSupplier() {
            private long seq;

            @Override
            public long getAsLong() {
                return ++seq;
            }
        });
    }

    public MllpFrameDecoder(int maxFrameBytes, LongSupplier sequenceSupplier) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes 必须为正数，收到 " + maxFrameBytes);
        }
        this.maxFrameBytes = maxFrameBytes;
        this.sequenceSupplier = sequenceSupplier == null ? () -> 0L : sequenceSupplier;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            while (in.isReadable()) {
                switch (state) {
                    case STATE_WAIT_START -> state = readStartBlock(ctx, in);
                    case STATE_IN_BLOCK -> state = readBlockBody(ctx, in);
                    case STATE_GOT_FS -> state = readEndBlock(ctx, in, out);
                    default -> throw new IllegalStateException("未知解码状态: " + state);
                }
            }
        } catch (RuntimeException ex) {
            releasePayload();
            state = STATE_WAIT_START;
            throw ex;
        }
    }

    /** 首字节必须是 {@code <VT>}。采纳源产品 {@code hasMoreInput()} 的严格判定。 */
    private int readStartBlock(ChannelHandlerContext ctx, ByteBuf in) {
        byte b = in.readByte();
        if (b != MllpConstants.SOM) {
            throw new InvalidMllpFrameException(FrameError.MISSING_START_BLOCK, remote(ctx), 1,
                    "首字节 0x" + hex(b) + " 不是 <VT>(0x0B)，请核对 HIS 侧 MLLP 通道是否勾选「块模式/Block Mode」");
        }
        payload = ctx.alloc().buffer(Math.min(maxFrameBytes, 8192));
        payloadLength = 0;
        return STATE_IN_BLOCK;
    }

    /** 一直读到 {@code <FS>}(0x1C)；读不到就把当前可读字节全收进 payload，等下一次 {@code decode()}。 */
    private int readBlockBody(ChannelHandlerContext ctx, ByteBuf in) {
        int eom = in.indexOf(in.readerIndex(), in.writerIndex(), MllpConstants.EOM1);
        if (eom < 0) {
            appendPayload(ctx, in, in.readableBytes());
            return STATE_IN_BLOCK;
        }
        int beforeEom = eom - in.readerIndex();
        if (beforeEom > 0) {
            appendPayload(ctx, in, beforeEom);
        }
        in.readerIndex(eom + 1); // 吃掉 <FS>
        return STATE_GOT_FS;
    }

    /** 校验 {@code <CR>}(0x0D) 并吐帧。采纳源产品 {@code read()} 里 0x1C 后的 CR 校验。 */
    private int readEndBlock(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        byte b = in.readByte();
        if (b != MllpConstants.EOM2) {
            throw new InvalidMllpFrameException(FrameError.MISSING_END_BLOCK, remote(ctx), payloadLength,
                    "0x1C(<FS>) 后应为 0x0D(<CR>)，实收 0x" + hex(b)
                            + "，请核对 HIS 侧 HL7 行结束符配置（应为 CR，不要配成 CRLF/LF）");
        }
        byte[] copy = new byte[payloadLength];
        payload.readBytes(copy);
        releasePayload();
        state = STATE_WAIT_START;
        out.add(new MllpFrame(copy, remote(ctx), Instant.now(), sequenceSupplier.getAsLong()));
        return STATE_WAIT_START;
    }

    private void appendPayload(ChannelHandlerContext ctx, ByteBuf in, int n) {
        if (n <= 0) {
            return;
        }
        long next = (long) payloadLength + n;
        if (next > maxFrameBytes) {
            throw new InvalidMllpFrameException(FrameError.FRAME_TOO_LARGE, remote(ctx), next,
                    "单帧上限 " + maxFrameBytes + " 字节；疑似对端未切块模式，把整份报文一次性灌了进来");
        }
        in.readBytes(payload, n);
        payloadLength += n;
    }

    /** 对端在帧中途断开：源产品抛 {@code EOFException("Connection closed by receiver")}，此处细分为独立失败码。 */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        boolean midFrame = state != STATE_WAIT_START || payloadLength > 0;
        int received = payloadLength;
        releasePayload();
        state = STATE_WAIT_START;
        if (midFrame) {
            ctx.fireExceptionCaught(new InvalidMllpFrameException(
                    FrameError.CONNECTION_CLOSED_IN_FRAME, remote(ctx), received,
                    "帧尚未收全对端就断开连接（多为院内网络中断或 HIS 侧进程重启）"));
        }
        ctx.fireChannelInactive();
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) {
        // ByteToMessageDecoder#handlerRemoved 是 final，释放累计缓冲区走官方扩展点 handlerRemoved0
        releasePayload();
    }

    private void releasePayload() {
        if (payload != null) {
            payload.release();
            payload = null;
        }
        payloadLength = 0;
    }

    private static String remote(ChannelHandlerContext ctx) {
        if (ctx == null || ctx.channel() == null) {
            return "unknown";
        }
        String v = ctx.channel().attr(REMOTE_ADDRESS).get();
        if (v != null) {
            return v;
        }
        return ctx.channel().remoteAddress() == null
                ? "unknown"
                : ctx.channel().remoteAddress().toString();
    }

    private static String hex(byte b) {
        return String.format("%02X", b);
    }

    /** 供测试与运行日志查看解码器当前状态（0=等待起始块 1=帧体 2=已收 FS 等 CR）。 */
    public int currentState() {
        return state;
    }

    /** 已累计收到的 payload 字节数。 */
    public int currentPayloadLength() {
        return payloadLength;
    }
}