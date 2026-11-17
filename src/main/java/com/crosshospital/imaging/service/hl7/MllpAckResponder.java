package com.crosshospital.imaging.service.hl7;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;

/**
 * MLLP ACK / NAK 回写器。
 *
 * <p>源主路径证据：{@code github_ref/dcm4che/dcm4che-hl7/.../MLLPConnection.java:110-132}。
 * 源产品的 ACK/NAK 语义是：
 * <pre>
 *   writeACK()   : mllpOut.write(ACK); mllpOut.finish();                 // 0B 06 1C 0D
 *   readACK()    : b = mllpIn.readMessage();
 *                  b == null            -> IOException("Connection closed by receiver")
 *                  b.length == 1 && ACK -> return
 *                  b.length == 1 && NAK -> IOException("NAK received")
 *                  其它长度              -> IOException("&lt;ACK&gt; or &lt;NAK&gt; expected, but received N bytes")
 * </pre>
 *
 * <p>本类把「写」这一半产品化，并原样保留三条语义约束：
 * <ol>
 *   <li>ACK/NAK 自身也是 {@code <VT> payload <FS><CR>} 形态的帧（payload 仅 1 字节）——
 *       源产品走 {@code MLLPOutputStream.write(int)} 的 {@code writeStartBlock + write + finish} 路径正是如此；</li>
 *   <li>只有在 {@link MllpRelease#MLLP2} 下才回 ACK，MLLP1 下静默不回（否则对端会把 ACK 当业务报文）；</li>
 *   <li>NAK 只回 1 字节 {@code 0x15}，**不**把错误说明塞进帧体 —— 源产品 {@code readACK()} 对
 *       {@code b.length != 1} 一律报「expected ACK or NAK」，把文字写进 NAK 帧会让对端直接抛错而不是读到 NAK。
 *       人类可读的失败原因改走结构化日志与后续死信表（task 19）。</li>
 * </ol>
 */
@Component
@Profile("!test")
public class MllpAckResponder {

    private static final Logger LOG = LoggerFactory.getLogger(MllpAckResponder.class);

    private static final byte[] ACK_FRAME = MllpConstants.frame(new byte[] { MllpConstants.ACK });
    private static final byte[] NAK_FRAME = MllpConstants.frame(new byte[] { MllpConstants.NAK });

    private final MllpRelease release;

    public MllpAckResponder(MllpRelease release) {
        if (release == null) {
            throw new IllegalArgumentException("MllpRelease 不能为 null");
        }
        this.release = release;
    }

    /** 当前释放模式下是否需要回 ACK。 */
    public boolean isAckRequired() {
        return release.acknowledgesReceived();
    }

    public MllpRelease release() {
        return release;
    }

    /**
     * 收到合法帧后回 ACK。
     *
     * @return 实际写出的 future；MLLP1 模式下返回<b>已完成但未写出任何字节</b>的 future（调用方无需分支）
     */
    public ChannelFuture sendAck(ChannelHandlerContext ctx, MllpFrame frame) {
        if (!release.acknowledgesReceived()) {
            LOG.debug("MLLP1 模式不回 ACK：peer={} seq={}", peer(ctx), frame == null ? -1 : frame.sequence());
            return ctx.newPromise().setSuccess();
        }
        LOG.debug("{} << <ACK> (seq={})", peer(ctx), frame == null ? -1 : frame.sequence());
        return ctx.writeAndFlush(ctx.alloc().buffer(ACK_FRAME.length).writeBytes(ACK_FRAME));
    }

    /**
     * 收到非法帧后回 NAK，并把人类可读原因写进日志（不回进帧体，见类注释第 3 条）。
     */
    public ChannelFuture sendNak(ChannelHandlerContext ctx, Throwable reason) {
        if (!release.acknowledgesReceived()) {
            LOG.warn("MLLP1 模式不回 NAK：peer={} 原因={}", peer(ctx), reason == null ? "unknown" : reason.toString());
            return ctx.newPromise().setSuccess();
        }
        LOG.warn("{} << <NAK> 原因={}", peer(ctx), reason == null ? "unknown" : reason.toString());
        return ctx.writeAndFlush(ctx.alloc().buffer(NAK_FRAME.length).writeBytes(NAK_FRAME));
    }

    /** ACK 线路帧字节（{@code 0B 06 1C 0D}），供测试与文档断言。 */
    public static byte[] ackFrame() {
        return ACK_FRAME.clone();
    }

    /** NAK 线路帧字节（{@code 0B 15 1C 0D}），供测试与文档断言。 */
    public static byte[] nakFrame() {
        return NAK_FRAME.clone();
    }

    private static String peer(ChannelHandlerContext ctx) {
        if (ctx == null || ctx.channel() == null) {
            return "unknown";
        }
        String v = ctx.channel().attr(MllpFrameDecoder.REMOTE_ADDRESS).get();
        if (v != null) {
            return v;
        }
        return ctx.channel().remoteAddress() == null ? "unknown" : ctx.channel().remoteAddress().toString();
    }
}