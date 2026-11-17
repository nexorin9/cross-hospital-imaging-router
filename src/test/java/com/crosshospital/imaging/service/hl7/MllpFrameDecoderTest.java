package com.crosshospital.imaging.service.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.crosshospital.imaging.exception.InvalidMllpFrameException;
import com.crosshospital.imaging.exception.InvalidMllpFrameException.FrameError;
import com.crosshospital.imaging.support.OracleOrderMessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * {@link MllpFrameDecoder} 单元测试（EmbeddedChannel，无真实端口）。
 *
 * <p>覆盖重点是<b>TCP 分片</b>：MLLP 帧围栏可能落在任意两次 read 之间，解码器必须跨
 * {@code decode()} 调用保持状态。本测试逐字节喂帧来证明这一点。
 *
 * <p>源主路径对照：断言的字节形态与源产品 {@code MLLPInputStream} 读路径完全一致 ——
 * 首字节必须 {@code 0x0B}、遇 {@code 0x1C} 后下一字节必须 {@code 0x0D}。
 */
class MllpFrameDecoderTest {

    private static final int MAX = 64 * 1024;

    private static EmbeddedChannel channel(int maxFrameBytes) {
        return new EmbeddedChannel(new MllpFrameDecoder(maxFrameBytes));
    }

    /** 从异常链里剥出 {@link InvalidMllpFrameException}（Netty 会包一层 DecoderException）。 */
    private static InvalidMllpFrameException expectFrameError(Runnable action) {
        Throwable thrown = assertThrows(Throwable.class, action::run);
        Throwable cur = thrown;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur instanceof InvalidMllpFrameException ex) {
                return ex;
            }
            cur = cur.getCause();
        }
        throw new AssertionError("异常链中没有 InvalidMllpFrameException，实际抛的是 " + thrown, thrown);
    }

    private static List<MllpFrame> drain(EmbeddedChannel ch) {
        List<MllpFrame> frames = new ArrayList<>();
        Object msg;
        while ((msg = ch.readInbound()) != null) {
            assertThat(msg).isInstanceOf(MllpFrame.class);
            frames.add((MllpFrame) msg);
        }
        return frames;
    }

    @Test
    @DisplayName("一条完整帧解出 payload（围栏字节不进入业务载荷）")
    void decodesSingleCompleteFrame() {
        EmbeddedChannel ch = channel(MAX);
        byte[] payload = OracleOrderMessage.ormO01(StandardCharsets.UTF_8);
        ch.writeInbound(Unpooled.wrappedBuffer(OracleOrderMessage.mllpFrame(payload)));

        List<MllpFrame> frames = drain(ch);
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).payload()).isEqualTo(payload);
        assertThat(new String(frames.get(0).payloadRef(), StandardCharsets.UTF_8)).startsWith("MSH|");
        assertThat(frames.get(0).size()).isEqualTo(payload.length);
        ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("一次写入两条粘包帧，解出两帧且顺序不乱")
    void decodesTwoPipelinedFrames() {
        EmbeddedChannel ch = channel(MAX);
        byte[] p1 = OracleOrderMessage.ormO01(StandardCharsets.UTF_8);
        byte[] p2 = OracleOrderMessage.adtA04(StandardCharsets.UTF_8);
        ByteBuf both = Unpooled.wrappedBuffer(OracleOrderMessage.mllpFrame(p1),
                OracleOrderMessage.mllpFrame(p2));
        ch.writeInbound(both);

        List<MllpFrame> frames = drain(ch);
        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).payload()).isEqualTo(p1);
        assertThat(frames.get(1).payload()).isEqualTo(p2);
        assertThat(frames.get(0).sequence()).isLessThan(frames.get(1).sequence());
        ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("逐字节喂入整帧（TCP 最坏分片）仍能解出，说明状态跨 decode() 保持")
    void decodesFrameFedOneByteAtATime() {
        EmbeddedChannel ch = channel(MAX);
        byte[] payload = OracleOrderMessage.ormO01(StandardCharsets.UTF_8);
        byte[] wire = OracleOrderMessage.mllpFrame(payload);

        List<MllpFrame> collected = new ArrayList<>();
        for (byte b : wire) {
            ch.writeInbound(Unpooled.wrappedBuffer(new byte[] { b }));
            collected.addAll(drain(ch));
        }
        assertThat(collected).hasSize(1);
        assertThat(collected.get(0).payload()).isEqualTo(payload);
        ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("帧尾 <FS> 与 <CR> 落在两次 read 之间时仍能正确收帧")
    void decodesFrameWhenTerminatorIsSplitAcrossReads() {
        EmbeddedChannel ch = channel(MAX);
        byte[] payload = OracleOrderMessage.ormO01(StandardCharsets.UTF_8);
        byte[] wire = OracleOrderMessage.mllpFrame(payload);

        ch.writeInbound(Unpooled.wrappedBuffer(java.util.Arrays.copyOfRange(wire, 0, wire.length - 2)));
        assertThat(drain(ch)).isEmpty();
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[] { wire[wire.length - 2] })); // 只给 <FS>
        assertThat(drain(ch)).isEmpty();
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[] { wire[wire.length - 1] })); // 再给 <CR>

        List<MllpFrame> frames = drain(ch);
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).payload()).isEqualTo(payload);
        ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("payload 内的 0x0B 不被误判为新帧起点")
    void payloadMayContainStartBlockByte() {
        EmbeddedChannel ch = channel(MAX);
        byte[] payload = "MSH|^TEST\rPID|1\r".getBytes(StandardCharsets.UTF_8);
        ch.writeInbound(Unpooled.wrappedBuffer(OracleOrderMessage.mllpFrame(payload)));

        List<MllpFrame> frames = drain(ch);
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).payload()).isEqualTo(payload);
        ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("缺 <VT>：首字节不是 0x0B → MISSING_START_BLOCK（源产品同名判定）")
    void rejectsFrameWithoutStartBlock() {
        EmbeddedChannel ch = channel(MAX);
        byte[] payload = OracleOrderMessage.ormO01(StandardCharsets.UTF_8);
        ByteBuf raw = Unpooled.buffer(); // 没有围栏，直接裸发
        raw.writeBytes(payload);
        raw.writeByte(MllpConstants.EOM1);
        raw.writeByte(MllpConstants.EOM2);

        InvalidMllpFrameException ex = expectFrameError(() -> ch.writeInbound(raw));
        assertThat(ex.getError()).isEqualTo(FrameError.MISSING_START_BLOCK);
        assertThat(ex.getMessage()).contains("missing_start_block").contains("Block Mode");
        assertThat(ex.getFrameLength()).isEqualTo(1);
        ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("缺 <CR>：0x1C 后不是 0x0D → MISSING_END_BLOCK（源产品 Invalid End Block character）")
    void rejectsFrameWithoutCarriageReturnAfterFileSeparator() {
        EmbeddedChannel ch = channel(MAX);
        byte[] payload = OracleOrderMessage.ormO01(StandardCharsets.UTF_8);
        ByteBuf raw = Unpooled.buffer();
        raw.writeByte(MllpConstants.SOM);
        raw.writeBytes(payload);
        raw.writeByte(MllpConstants.EOM1);
        raw.writeByte('X'); // 应为 0x0D

        InvalidMllpFrameException ex = expectFrameError(() -> ch.writeInbound(raw));
        assertThat(ex.getError()).isEqualTo(FrameError.MISSING_END_BLOCK);
        assertThat(ex.getMessage()).contains("missing_end_block").contains("行结束符");
        assertThat(ex.getFrameLength()).isEqualTo(payload.length);
        ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("只发 <FS> 不发 <CR> 且随即断连 → CONNECTION_CLOSED_IN_FRAME（区别于 HIS 配错行结束符）")
    void reportsConnectionClosedMidFrame() {
        EmbeddedChannel ch = channel(MAX);
        ByteBuf raw = Unpooled.buffer();
        raw.writeByte(MllpConstants.SOM);
        raw.writeBytes(OracleOrderMessage.ormO01(StandardCharsets.UTF_8));
        raw.writeByte(MllpConstants.EOM1);
        ch.writeInbound(raw);
        assertThat(drain(ch)).isEmpty();

        // finish() 会触发 channelInactive → 帧未收全即报 CONNECTION_CLOSED_IN_FRAME，并由 checkException 重抛
        InvalidMllpFrameException ex = expectFrameError(ch::finish);
        assertThat(ex.getError()).isEqualTo(FrameError.CONNECTION_CLOSED_IN_FRAME);
        assertThat(ex.getFrameLength()).isGreaterThan(0);
        ch.releaseInbound();
    }

    @Test
    @DisplayName("超 max-frame-bytes 立即拒收（宁可不收也不把 worker 线程内存吃光）")
    void rejectsOversizedFrame() {
        EmbeddedChannel ch = channel(256);
        byte[] payload = new byte[4096];
        java.util.Arrays.fill(payload, (byte) 'A');
        ByteBuf raw = Unpooled.wrappedBuffer(OracleOrderMessage.mllpFrame(payload));

        InvalidMllpFrameException ex = expectFrameError(() -> ch.writeInbound(raw));
        assertThat(ex.getError()).isEqualTo(FrameError.FRAME_TOO_LARGE);
        assertThat(ex.getMessage()).contains("frame_too_large").contains("256");
        ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("payload 恰好等于上限时收下，超过 1 字节即拒")
    void boundaryAtMaxFrameBytes() {
        EmbeddedChannel ok = channel(64);
        byte[] exact = new byte[64];
        java.util.Arrays.fill(exact, (byte) 'B');
        ok.writeInbound(Unpooled.wrappedBuffer(OracleOrderMessage.mllpFrame(exact)));
        assertThat(drain(ok)).hasSize(1);
        ok.finishAndReleaseAll();

        EmbeddedChannel tooBig = new EmbeddedChannel(new MllpFrameDecoder(63));
        InvalidMllpFrameException ex = expectFrameError(
                () -> tooBig.writeInbound(Unpooled.wrappedBuffer(OracleOrderMessage.mllpFrame(exact))));
        assertThat(ex.getError()).isEqualTo(FrameError.FRAME_TOO_LARGE);
        tooBig.finishAndReleaseAll();
    }

    @Test
    @DisplayName("空 payload 帧照收但标记 isEmpty（不在线路层掐连接，可读性交给业务路由）")
    void emptyPayloadFrameIsDeliveredAndFlagged() {
        EmbeddedChannel ch = channel(MAX);
        ch.writeInbound(Unpooled.wrappedBuffer(MllpConstants.frame(new byte[0])));

        List<MllpFrame> frames = drain(ch);
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).isEmpty()).isTrue();
        assertThat(frames.get(0).size()).isZero();
        ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("一次坏帧之后解码器状态复位、累计缓冲释放（不泄漏堆、不卡在半帧状态）")
    void stateAndBufferResetAfterBadFrame() {
        MllpFrameDecoder decoder = new MllpFrameDecoder(MAX);
        EmbeddedChannel ch = new EmbeddedChannel(decoder);

        // 裸发一段没有围栏的报文：首字节不是 <VT>，decode() 的 catch 分支必须把状态与缓冲一并复位
        expectFrameError(() -> ch.writeInbound(Unpooled.wrappedBuffer("MSH|GARBAGE".getBytes(StandardCharsets.UTF_8))));

        assertThat(decoder.currentState()).isZero();       // 回到「等待 <VT>」
        assertThat(decoder.currentPayloadLength()).isZero(); // 累计缓冲已归零，未泄漏

        // 新连接复用同一份配置解帧正常：线路层拒的是那条帧，不是整条通道
        byte[] payload = OracleOrderMessage.ormO01(StandardCharsets.UTF_8);
        EmbeddedChannel fresh = new EmbeddedChannel(new MllpFrameDecoder(MAX));
        fresh.writeInbound(Unpooled.wrappedBuffer(OracleOrderMessage.mllpFrame(payload)));
        List<MllpFrame> frames = drain(fresh);
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).payload()).isEqualTo(payload);

        fresh.finishAndReleaseAll();
        ch.releaseInbound();
    }

    @Test
    @DisplayName("非法 maxFrameBytes 在构造期就失败（配置错误不拖到首条报文）")
    void rejectsNonPositiveMaxFrameBytes() {
        assertThrows(IllegalArgumentException.class, () -> new MllpFrameDecoder(0));
        assertThrows(IllegalArgumentException.class, () -> new MllpFrameDecoder(-1));
    }

    @Test
    @DisplayName("MllpFrame.mshLine 扫到第一个 CR 取 MSH 段头，并按上限截断")
    void mshLineExtractsAndTruncates() {
        byte[] payload = OracleOrderMessage.ormO01(StandardCharsets.UTF_8);
        MllpFrame frame = new MllpFrame(payload, "127.0.0.1:5000", null, 7L);

        String msh = frame.mshLine(StandardCharsets.UTF_8);
        assertThat(msh).startsWith("MSH|^~\\&|HIS_APP").doesNotContain("\r");
        assertThat(frame.sequence()).isEqualTo(7L);
        assertThat(frame.remoteAddress()).isEqualTo("127.0.0.1:5000");
        assertThat(frame.summary(StandardCharsets.UTF_8)).contains("seq=7").contains("bytes=" + payload.length);

        byte[] huge = new byte[MllpFrame.MSH_LINE_MAX + 50];
        java.util.Arrays.fill(huge, (byte) 'X');
        MllpFrame longFrame = new MllpFrame(huge, "10.0.0.1:1", null, 1L);
        assertThat(longFrame.mshLine(StandardCharsets.UTF_8)).hasSize(MllpFrame.MSH_LINE_MAX);
    }

    @Test
    @DisplayName("MllpFrame 对 payload 做防御性拷贝，外部改动不影响帧内容")
    void frameCopiesPayloadDefensively() {
        byte[] payload = "MSH|ORIGINAL".getBytes(StandardCharsets.UTF_8);
        MllpFrame frame = new MllpFrame(payload, "p", null, 1L);
        payload[0] = 'X';
        assertThat(new String(frame.payloadRef(), StandardCharsets.UTF_8)).isEqualTo("MSH|ORIGINAL");
        assertThat(frame.payload()).isNotSameAs(frame.payload());
    }
}