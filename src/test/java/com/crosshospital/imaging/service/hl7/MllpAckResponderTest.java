package com.crosshospital.imaging.service.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MllpAckResponder} 与 {@link MllpRelease} 单元测试。
 *
 * <p>最关键的一条是 {@link #nakFrameIsSingleBytePayloadSoDcm4cheStylePeersAcceptIt()}：
 * 源产品的 {@code readACK()} 只在 {@code b.length == 1} 时才把帧当 ACK/NAK 认下来，
 * 任何更长/更短的内容都会走「expected ACK or NAK」分支直接抛错。因此 NAK 帧的 payload
 * <b>必须</b>恰好 1 字节，错误说明只能走日志，不能塞进帧体 —— 否则对端收到的是
 * 「协议错误」而不是「NAK」，两者在 HIS 侧排查时的结论完全不同。
 */
class MllpAckResponderTest {

    private static byte[] outbound(EmbeddedChannel ch) {
        ByteBuf buf = ch.readOutbound();
        if (buf == null) {
            return null;
        }
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    @Test
    @DisplayName("ACK 线路帧 = <VT> 0x06 <FS><CR>，与源产品 writeStartBlock+write+finish 一致")
    void ackFrameMatchesSourceWireFormat() {
        assertThat(MllpAckResponder.ackFrame())
                .containsExactly(0x0B, 0x06, 0x1C, 0x0D)
                .hasSize(MllpConstants.ACK_FRAME_LENGTH);
    }

    @Test
    @DisplayName("NAK 线路帧 = <VT> 0x15 <FS><CR>")
    void nakFrameMatchesSourceWireFormat() {
        assertThat(MllpAckResponder.nakFrame())
                .containsExactly(0x0B, 0x15, 0x1C, 0x0D)
                .hasSize(MllpConstants.NAK_FRAME_LENGTH);
    }

    @Test
    @DisplayName("NAK 帧 payload 恰好 1 字节，源产品式对端才不会误判成协议错误")
    void nakFrameIsSingleBytePayloadSoDcm4cheStylePeersAcceptIt() {
        byte[] frame = MllpAckResponder.nakFrame();
        // 剥掉围栏后必须正好是 1 字节的 0x15 —— 源产品 MLLPConnection.readACK 的 b.length==1 判据
        assertThat(frame.length - 3).isEqualTo(1);
        assertThat(frame[1]).isEqualTo((byte) MllpConstants.NAK);
    }

    @Test
    @DisplayName("MLLP2：收到合法帧回 ACK 并真的写进 channel")
    void mllp2WritesAckOnChannel() {
        EmbeddedChannel ch = new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        MllpAckResponder responder = new MllpAckResponder(MllpRelease.MLLP2);
        MllpFrame frame = new MllpFrame("MSH|TEST".getBytes(StandardCharsets.UTF_8), "p", null, 1L);

        assertThat(responder.isAckRequired()).isTrue();
        responder.sendAck(ch.pipeline().firstContext(), frame);

        assertThat(outbound(ch)).containsExactly(0x0B, 0x06, 0x1C, 0x0D);
        assertThat(ch.pipeline().firstContext().channel().isActive()).isTrue();
        ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("MLLP1：收到了也不回 ACK（否则对端把 ACK 当业务报文）")
    void mllp1DoesNotReply() {
        EmbeddedChannel ch = new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        MllpAckResponder responder = new MllpAckResponder(MllpRelease.MLLP1);
        MllpFrame frame = new MllpFrame("MSH|TEST".getBytes(StandardCharsets.UTF_8), "p", null, 1L);

        assertThat(responder.isAckRequired()).isFalse();
        responder.sendAck(ch.pipeline().firstContext(), frame);
        assertThat(outbound(ch)).isNull();

        responder.sendNak(ch.pipeline().firstContext(), new IllegalStateException("boom"));
        assertThat(outbound(ch)).isNull();
        ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("MLLP2：非法帧回 NAK，通道仍存活（由 handler 决定何时断）")
    void mllp2WritesNak() {
        EmbeddedChannel ch = new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        MllpAckResponder responder = new MllpAckResponder(MllpRelease.MLLP2);

        responder.sendNak(ch.pipeline().firstContext(), new IllegalStateException("测试用原因"));
        assertThat(outbound(ch)).containsExactly(0x0B, 0x15, 0x1C, 0x0D);
        assertThat(ch.isOpen()).isTrue();
        ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("发送方向语义：MLLP1 不等 ACK，MLLP2 等 ACK")
    void awaitsAckOnSendFollowsReleaseMode() {
        assertThat(MllpRelease.MLLP1.awaitsAckOnSend()).isFalse();
        assertThat(MllpRelease.MLLP2.awaitsAckOnSend()).isTrue();
        assertThat(MllpRelease.MLLP1.acknowledgesReceived()).isFalse();
        assertThat(MllpRelease.MLLP2.acknowledgesReceived()).isTrue();
    }

    @Test
    @DisplayName("release mode 解析：大小写/别名可用，未知取值启动即失败")
    void releaseModeParsing() {
        assertThat(MllpRelease.parse("MLLP2")).isEqualTo(MllpRelease.MLLP2);
        assertThat(MllpRelease.parse("mllp1")).isEqualTo(MllpRelease.MLLP1);
        assertThat(MllpRelease.parse("2")).isEqualTo(MllpRelease.MLLP2);
        assertThat(MllpRelease.parse("  Mllp2  ")).isEqualTo(MllpRelease.MLLP2);
        assertThatThrownBy(() -> MllpRelease.parse("MLLP9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MLLP1 / MLLP2");
        assertThatThrownBy(() -> MllpRelease.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("响应器构造期拒绝 null 释放模式")
    void rejectsNullReleaseMode() {
        assertThatThrownBy(() -> new MllpAckResponder(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ACK 常量与源产品 MLLPConnection.ACK/NAK 逐字节对齐")
    void constantsMatchSource() {
        assertThat(MllpConstants.ACK).isEqualTo((byte) 0x06);
        assertThat(MllpConstants.NAK).isEqualTo((byte) 0x15);
        assertThat(MllpConstants.SOM).isEqualTo((byte) 0x0B);
        assertThat(MllpConstants.EOM1).isEqualTo((byte) 0x1C);
        assertThat(MllpConstants.EOM2).isEqualTo((byte) 0x0D);
        assertThat(MllpConstants.frame(new byte[] { 1, 2, 3 }))
                .containsExactly(0x0B, 1, 2, 3, 0x1C, 0x0D);
    }

    @Test
    @DisplayName("MLLP 围栏字节为 HL7 段内不会出现的高位控制符，裸出现即说明线路配置错了")
    void framingBytesAreControlCharacters() {
        assertThat(MllpConstants.SOM & 0xFF).isEqualTo(11);
        assertThat(MllpConstants.EOM1 & 0xFF).isEqualTo(28);
        assertThat(MllpConstants.EOM2 & 0xFF).isEqualTo(13);
    }
}