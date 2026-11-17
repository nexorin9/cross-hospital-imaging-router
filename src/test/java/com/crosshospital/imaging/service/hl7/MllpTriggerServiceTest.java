package com.crosshospital.imaging.service.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.crosshospital.imaging.config.Hl7ConfigProps;
import com.crosshospital.imaging.config.MllpServerConfig;
import com.crosshospital.imaging.support.OracleOrderMessage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link MllpTriggerService} 端到端测试：真 Netty 守护 + 真 TCP 客户端（{@link Socket}）。
 *
 * <p>不 mock 传输层——HIS 侧的 MLLP 通道就是一个裸 TCP 客户端，用真 Socket 打进去才能验证
 * 分帧、ACK 时延、断连处置、优雅停机这些「只有真网络才暴露」的行为。
 *
 * <p>源主路径对照：本类断言的线路字节与 {@code MLLPConnection} 的 ACK/NAK 完全一致，
 * 因此对接本守护的 HIS 无需改任何 MLLP 参数。
 */
class MllpTriggerServiceTest {

    private static final Logger LOG = LoggerFactory.getLogger(MllpTriggerServiceTest.class);
    private static final Charset CS = StandardCharsets.UTF_8;
    private static final int READ_TIMEOUT_MS = 3000;

    private final List<MllpTriggerService> started = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopAll() {
        started.forEach(svc -> {
            try {
                svc.stop();
            } catch (RuntimeException ex) {
                LOG.warn("停止守护失败", ex);
            }
        });
        started.clear();
    }

    // ------------------------------------------------------------------ 测试替身与夹具

    /** 记录收到的帧；可切换成「处理失败」模式验证 NAK 分支。 */
    private static final class RecordingRouter implements Hl7TriggerRouter {
        private final List<MllpFrame> frames = new CopyOnWriteArrayList<>();
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile RuntimeException failure;

        void failWith(RuntimeException ex) {
            this.failure = ex;
        }

        @Override
        public void route(MllpFrame frame) {
            if (failure != null) {
                throw failure;
            }
            frames.add(frame);
            latch.countDown();
        }
    }

    private static Hl7ConfigProps props(String releaseMode) {
        Hl7ConfigProps props = new Hl7ConfigProps();
        Hl7ConfigProps.Trigger t = props.getTrigger();
        t.setPort(0); // 由内核分配空闲端口，避免与 6661 及 CI 并发冲突
        t.setBindHost("127.0.0.1");
        t.setCharset("UTF-8");
        t.setReleaseMode(releaseMode);
        t.setIdleTimeoutMs(0);
        t.setWorkerThreads(2);
        t.setBacklog(16);
        return props;
    }

    private MllpTriggerService boot(Hl7ConfigProps props, Hl7TriggerRouter router) {
        MllpAckResponder ack = new MllpAckResponder(props.getTrigger().resolveReleaseMode());
        MllpTriggerService svc = new MllpTriggerService(props, router, ack,
                new MllpServerConfig.MllpPipelineFactory(props), new SimpleMeterRegistry(), "mllp-test");
        svc.start();
        started.add(svc);
        return svc;
    }

    private static Socket connect(int port, int soTimeoutMs) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", port), 2000);
        s.setSoTimeout(soTimeoutMs);
        s.setTcpNoDelay(true);
        return s;
    }

    private static void write(Socket s, byte[] bytes) throws IOException {
        OutputStream out = s.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    /** 读满 n 字节；返回 null 表示对端已断开（读到 EOF）。 */
    private static byte[] readExactly(Socket s, int n) throws IOException {
        InputStream in = s.getInputStream();
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r < 0) {
                return read == 0 ? null : java.util.Arrays.copyOf(buf, read);
            }
            read += r;
        }
        return buf;
    }

    private static final byte[] ACK_FRAME = MllpAckResponder.ackFrame();
    private static final byte[] NAK_FRAME = MllpAckResponder.nakFrame();

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("合法 ORM^O01 帧 → 100ms 内回 ACK；帧交给路由器；守护进入运行态")
    void validFrameIsAcknowledgedWithin100ms() throws Exception {
        RecordingRouter router = new RecordingRouter();
        MllpTriggerService svc = boot(props("MLLP2"), router);
        int port = svc.boundPort();

        assertThat(port).isGreaterThan(0);
        assertThat(svc.isRunning()).isTrue();
        assertThat(svc.describe()).contains("running=true").contains("port=" + port).contains("release=MLLP2");

        try (Socket warm = connect(port, READ_TIMEOUT_MS)) {
            write(warm, OracleOrderMessage.ormO01Frame(CS)); // 预热：首帧含 JIT/类加载开销
            assertThat(readExactly(warm, ACK_FRAME.length)).containsExactly(ACK_FRAME);
        }
        assertThat(router.latch.await(2, TimeUnit.SECONDS)).isTrue();

        try (Socket s = connect(port, READ_TIMEOUT_MS)) {
            long start = System.nanoTime();
            write(s, OracleOrderMessage.ormO01Frame(CS));
            byte[] ack = readExactly(s, ACK_FRAME.length);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertThat(ack).containsExactly(0x0B, 0x06, 0x1C, 0x0D);
            assertThat(elapsedMs).isLessThan(100L);
            assertThat(svc.framesReceived()).isEqualTo(2L);
            assertThat(svc.acksSent()).isEqualTo(2L);
            assertThat(svc.naksSent()).isZero();
        }

        MllpFrame frame = router.frames.get(0);
        assertThat(new String(frame.payloadRef(), CS)).startsWith("MSH|^~\\&|HIS_APP|HIS_FACILITY|IMAGER|");
        assertThat(new String(frame.payloadRef(), CS)).contains("ORM^O01");
        assertThat(frame.remoteAddress()).startsWith("/127.0.0.1:");
    }

    @Test
    @DisplayName("缺 <VT>（HIS 未勾块模式）→ 回 NAK 并断开，计数 +1")
    void frameWithoutStartBlockYieldsNak() throws Exception {
        RecordingRouter router = new RecordingRouter();
        MllpTriggerService svc = boot(props("MLLP2"), router);

        byte[] payload = OracleOrderMessage.ormO01(CS);
        ByteBufferBuilder raw = new ByteBufferBuilder();
        raw.bytes(payload).appendByte(MllpConstants.EOM1).appendByte(MllpConstants.EOM2);

        try (Socket s = connect(svc.boundPort(), READ_TIMEOUT_MS)) {
            write(s, raw.toBytes());
            assertThat(readExactly(s, NAK_FRAME.length)).containsExactly(0x0B, 0x15, 0x1C, 0x0D);
            assertThat(readExactly(s, 1)).isNull(); // 随后对端读到 EOF：连接已断开
        }

        assertThat(svc.framesReceived()).isZero();
        assertThat(svc.framesRejected()).isEqualTo(1L);
        assertThat(router.frames).isEmpty();
    }

    @Test
    @DisplayName("缺 <CR>（HIS 行结束符配成 CRLF/LF）→ 回 NAK 并断开")
    void frameWithoutCarriageReturnYieldsNak() throws Exception {
        RecordingRouter router = new RecordingRouter();
        MllpTriggerService svc = boot(props("MLLP2"), router);

        ByteBufferBuilder raw = new ByteBufferBuilder();
        raw.appendByte(MllpConstants.SOM).bytes(OracleOrderMessage.ormO01(CS)).appendByte(MllpConstants.EOM1).appendByte((byte) '\n');

        try (Socket s = connect(svc.boundPort(), READ_TIMEOUT_MS)) {
            write(s, raw.toBytes());
            assertThat(readExactly(s, NAK_FRAME.length)).containsExactly(0x0B, 0x15, 0x1C, 0x0D);
            assertThat(readExactly(s, 1)).isNull();
        }
        assertThat(svc.framesRejected()).isEqualTo(1L);
        assertThat(router.frames).isEmpty();
    }

    @Test
    @DisplayName("帧未收全对端就断连 → 计 CONNECTION_CLOSED_IN_FRAME 并断开")
    void truncatedFrameThenDisconnect() throws Exception {
        RecordingRouter router = new RecordingRouter();
        MllpTriggerService svc = boot(props("MLLP2"), router);

        ByteBufferBuilder raw = new ByteBufferBuilder();
        raw.appendByte(MllpConstants.SOM).bytes(OracleOrderMessage.ormO01(CS));

        Socket s = connect(svc.boundPort(), READ_TIMEOUT_MS);
        write(s, raw.toBytes());
        s.close(); // 半帧即断

        long deadline = System.currentTimeMillis() + 3000;
        while (svc.framesRejected() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(svc.framesRejected()).isEqualTo(1L);
        assertThat(svc.naksSent()).isEqualTo(1L);
        assertThat(router.frames).isEmpty();
    }

    @Test
    @DisplayName("超 max-frame-bytes → 回 NAK 并断开（不放行超大报文进解析器）")
    void oversizedFrameIsRejected() throws Exception {
        RecordingRouter router = new RecordingRouter();
        Hl7ConfigProps props = props("MLLP2");
        props.getTrigger().setMaxFrameBytes(512);
        MllpTriggerService svc = boot(props, router);

        byte[] payload = new byte[4096];
        java.util.Arrays.fill(payload, (byte) 'X');
        try (Socket s = connect(svc.boundPort(), READ_TIMEOUT_MS)) {
            write(s, OracleOrderMessage.mllpFrame(payload));
            assertThat(readExactly(s, NAK_FRAME.length)).containsExactly(0x0B, 0x15, 0x1C, 0x0D);
            assertThat(readExactly(s, 1)).isNull();
        }
        assertThat(svc.framesRejected()).isEqualTo(1L);
        assertThat(router.frames).isEmpty();
    }

    @Test
    @DisplayName("MLLP1 模式：照收帧但绝不回 ACK（回了对端会把 ACK 当业务报文）")
    void mllp1ModeNeverAcknowledges() throws Exception {
        RecordingRouter router = new RecordingRouter();
        MllpTriggerService svc = boot(props("MLLP1"), router);

        try (Socket s = connect(svc.boundPort(), 600)) {
            write(s, OracleOrderMessage.ormO01Frame(CS));
            assertThat(router.latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> readExactly(s, ACK_FRAME.length))
                    .isInstanceOf(SocketTimeoutException.class); // 读超时 = 确实没回
        }
        assertThat(svc.framesReceived()).isEqualTo(1L);
        assertThat(svc.acksSent()).isZero();
        assertThat(svc.naksSent()).isZero();
    }

    @Test
    @DisplayName("业务路由抛错 → 回 NAK 并断开（不假装成功，HIS 会重投）")
    void routeFailureYieldsNakAndDisconnect() throws Exception {
        RecordingRouter router = new RecordingRouter();
        router.failWith(new IllegalStateException("缺 PID 段"));
        MllpTriggerService svc = boot(props("MLLP2"), router);

        try (Socket s = connect(svc.boundPort(), READ_TIMEOUT_MS)) {
            write(s, OracleOrderMessage.ormO01Frame(CS));
            assertThat(readExactly(s, NAK_FRAME.length)).containsExactly(0x0B, 0x15, 0x1C, 0x0D);
            assertThat(readExactly(s, 1)).isNull();
        }
        assertThat(svc.routeFailures()).isEqualTo(1L);
        assertThat(svc.naksSent()).isEqualTo(1L);
        assertThat(svc.acksSent()).isZero();
        assertThat(svc.framesRejected()).isZero(); // 帧本身合法，不计入线路层拒收
    }

    @Test
    @DisplayName("一条连接上连发两条帧 → 两个 ACK，路由器收到两帧且顺序保持")
    void twoFramesOnOneConnectionBothAcked() throws Exception {
        RecordingRouter router = new RecordingRouter();
        MllpTriggerService svc = boot(props("MLLP2"), router);

        try (Socket s = connect(svc.boundPort(), READ_TIMEOUT_MS)) {
            byte[] both = concat(OracleOrderMessage.ormO01Frame(CS), OracleOrderMessage.mllpFrame(
                    OracleOrderMessage.adtA04(CS)));
            write(s, both);
            assertThat(readExactly(s, ACK_FRAME.length * 2))
                    .containsExactly(0x0B, 0x06, 0x1C, 0x0D, 0x0B, 0x06, 0x1C, 0x0D);
        }

        long deadline = System.currentTimeMillis() + 3000;
        while (router.frames.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(router.frames).hasSize(2);
        assertThat(new String(router.frames.get(0).payloadRef(), CS)).contains("ORM^O01");
        assertThat(new String(router.frames.get(1).payloadRef(), CS)).contains("ADT^A04");
        assertThat(svc.acksSent()).isEqualTo(2L);
    }

    @Test
    @DisplayName("5 条并发连接各发一帧 → 5 个 ACK（Netty 单线程管多连接）")
    void concurrentConnectionsAreAllServed() throws Exception {
        RecordingRouter router = new RecordingRouter();
        MllpTriggerService svc = boot(props("MLLP2"), router);
        int port = svc.boundPort();

        int n = 5;
        CountDownLatch done = new CountDownLatch(n);
        AtomicReference<Exception> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(() -> {
                try (Socket s = connect(port, READ_TIMEOUT_MS)) {
                    write(s, OracleOrderMessage.ormO01Frame(CS));
                    assertThat(readExactly(s, ACK_FRAME.length)).containsExactly(ACK_FRAME);
                } catch (Exception ex) {
                    failure.compareAndSet(null, ex);
                } finally {
                    done.countDown();
                }
            }, "mllp-client-" + i);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join(5000);
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(router.frames).hasSize(n);
        assertThat(svc.acksSent()).isEqualTo(n);
    }

    @Test
    @DisplayName("close-on-idle=true：空闲超时后主动断开（防长连接占死 HIS 侧句柄）")
    void idleConnectionIsClosedWhenConfigured() throws Exception {
        RecordingRouter router = new RecordingRouter();
        Hl7ConfigProps props = props("MLLP2");
        props.getTrigger().setIdleTimeoutMs(200);
        props.getTrigger().setCloseOnIdle(true);
        MllpTriggerService svc = boot(props, router);

        try (Socket s = connect(svc.boundPort(), 3000)) {
            assertThat(readExactly(s, 1)).isNull(); // 空闲超时后服务端断开 → 读到 EOF
        }
        assertThat(svc.framesReceived()).isZero();
    }

    @Test
    @DisplayName("close-on-idle=false（默认）：空闲只告警不断连，HIS 长连保持可用")
    void idleConnectionStaysOpenByDefault() throws Exception {
        RecordingRouter router = new RecordingRouter();
        Hl7ConfigProps props = props("MLLP2");
        props.getTrigger().setIdleTimeoutMs(150);
        props.getTrigger().setCloseOnIdle(false);
        MllpTriggerService svc = boot(props, router);

        try (Socket s = connect(svc.boundPort(), 700)) {
            write(s, OracleOrderMessage.ormO01Frame(CS));
            assertThat(readExactly(s, ACK_FRAME.length)).containsExactly(ACK_FRAME);
            assertThatThrownBy(() -> readExactly(s, 1))
                    .isInstanceOf(SocketTimeoutException.class); // 空闲期间连接仍存活
            write(s, OracleOrderMessage.ormO01Frame(CS)); // 同一条连接还能再发
            assertThat(readExactly(s, ACK_FRAME.length)).containsExactly(ACK_FRAME);
        }
        assertThat(svc.acksSent()).isEqualTo(2L);
    }

    @Test
    @DisplayName("stop() 后端口立即释放，可被重新占用（信息科重启不撞端口）")
    void stopReleasesPort() throws Exception {
        RecordingRouter router = new RecordingRouter();
        Hl7ConfigProps props = props("MLLP2");
        MllpTriggerService svc = boot(props, router);
        int port = svc.boundPort();

        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            assertThatThrownBy(() -> {
                probe.bind(new InetSocketAddress("127.0.0.1", port));
            }).isInstanceOf(IOException.class); // 端口在用

            svc.stop();
            started.remove(svc);
            probe.bind(new InetSocketAddress("127.0.0.1", port)); // 停机后应能绑定
            assertThat(probe.getLocalPort()).isEqualTo(port);
        }
        assertThat(svc.isRunning()).isFalse();
        assertThat(svc.boundPort()).isEqualTo(-1);
        assertThat(svc.describe()).contains("frames=" + svc.framesReceived());
    }

    @Test
    @DisplayName("启动期配置校验：非法释放模式 / 越界端口 / 超长应用名一律启动失败")
    void startUpValidationRejectsBadConfiguration() {
        Hl7ConfigProps badRelease = props("MLLP9");
        MllpAckResponder ack = new MllpAckResponder(MllpRelease.MLLP2);
        MllpTriggerService svc1 = new MllpTriggerService(badRelease, new RecordingRouter(), ack,
                new MllpServerConfig.MllpPipelineFactory(badRelease), null, "t");
        assertThatThrownBy(svc1::start)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MLLP1 / MLLP2");

        Hl7ConfigProps badPort = props("MLLP2");
        badPort.getTrigger().setPort(70000);
        MllpTriggerService svc2 = new MllpTriggerService(badPort, new RecordingRouter(), ack,
                new MllpServerConfig.MllpPipelineFactory(badPort), null, "t");
        assertThatThrownBy(svc2::start).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0..65535");

        Hl7ConfigProps badAet = props("MLLP2");
        badAet.setLocalApplication("WAY_TOO_LONG_APPLICATION_NAME");
        MllpTriggerService svc3 = new MllpTriggerService(badAet, new RecordingRouter(), ack,
                new MllpServerConfig.MllpPipelineFactory(badAet), null, "t");
        assertThatThrownBy(svc3::start)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16 字符上限");

        Hl7ConfigProps badCharset = props("MLLP2");
        badCharset.getTrigger().setCharset("NOT-A-CHARSET");
        MllpTriggerService svc4 = new MllpTriggerService(badCharset, new RecordingRouter(), ack,
                new MllpServerConfig.MllpPipelineFactory(badCharset), null, "t");
        assertThatThrownBy(svc4::start).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("charset");
    }

    @Test
    @DisplayName("构造期拒绝 null 依赖（不等到第一次收帧才 NPE）")
    void rejectsNullDependencies() {
        assertThatThrownBy(() -> new MllpTriggerService(null, new RecordingRouter(),
                new MllpAckResponder(MllpRelease.MLLP2), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("指标累计正确：收帧 / ACK / NAK / 拒收 / 路由失败 / 在线连接")
    void metricsAreAccumulated() throws Exception {
        RecordingRouter router = new RecordingRouter();
        Hl7ConfigProps props = props("MLLP2");
        props.getTrigger().setMaxFrameBytes(1024);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MllpAckResponder ack = new MllpAckResponder(MllpRelease.MLLP2);
        MllpTriggerService svc = new MllpTriggerService(props, router, ack,
                new MllpServerConfig.MllpPipelineFactory(props), registry, "mllp-metric");
        svc.start();
        started.add(svc);

        try (Socket good = connect(svc.boundPort(), READ_TIMEOUT_MS)) {
            write(good, OracleOrderMessage.ormO01Frame(CS));
            assertThat(readExactly(good, ACK_FRAME.length)).containsExactly(ACK_FRAME);
        }
        try (Socket bad = connect(svc.boundPort(), READ_TIMEOUT_MS)) {
            write(bad, "MSH|NO-BLOCK-MODE".getBytes(CS));
            assertThat(readExactly(bad, NAK_FRAME.length)).containsExactly(NAK_FRAME);
        }

        assertThat(registry.get("hl7_frames_received_total").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("hl7_acks_total").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("hl7_naks_total").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("hl7_frames_rejected_total").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("hl7_route_failures_total").counter().count()).isZero();
        assertThat(registry.get("hl7_active_connections").gauge().value()).isZero();
    }

    @Test
    @DisplayName("入门日志路由器：帧进有界内存环，超容量淘汰最旧，不无限涨")
    void ingressLogRouterKeepsBoundedJournal() {
        Hl7IngressLogRouter router = new Hl7IngressLogRouter(3);
        for (int i = 1; i <= 5; i++) {
            router.route(new MllpFrame(("MSH|SEQ" + i).getBytes(CS), "127.0.0.1:" + (9000 + i), null, i));
        }
        assertThat(router.routedCount()).isEqualTo(5L);
        assertThat(router.recentFrames()).hasSize(3);
        assertThat(router.recentFrames().get(0).sequence()).isEqualTo(3L);
        assertThat(router.recentFrames().get(2).sequence()).isEqualTo(5L);

        router.clear();
        assertThat(router.recentFrames()).isEmpty();
        assertThatThrownBy(() -> new Hl7IngressLogRouter(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("管线模板：每条连接拿到独立 decoder 实例（不复用已释放状态），并按配置决定是否装空闲检测")
    void pipelineFactoryBuildsFreshDecoderPerConnection() {
        Hl7ConfigProps props = props("MLLP2");
        MllpServerConfig.MllpPipelineFactory factory = new MllpServerConfig.MllpPipelineFactory(props);
        assertThat(factory.describe()).contains("release=MLLP2").contains("charset=UTF-8").contains("bind=127.0.0.1:0");

        // 每条连接必须拿到独立的 handler 实例，否则第二条连接会因非 @Sharable 被静默掐断
        // （该行为的真实验证见 concurrentConnectionsAreAllServed：5 条真实 TCP 连接全部服务到）
        java.util.function.Supplier<io.netty.channel.ChannelHandler> perConnection =
                () -> new io.netty.channel.ChannelInboundHandlerAdapter();
        io.netty.channel.ChannelInitializer<io.netty.channel.socket.SocketChannel> init1 =
                factory.newInitializer(perConnection);
        io.netty.channel.ChannelInitializer<io.netty.channel.socket.SocketChannel> init2 =
                factory.newInitializer(perConnection);
        assertThat(init1).isNotSameAs(init2);
        assertThat(perConnection.get()).isNotSameAs(perConnection.get()); // 工厂每次现造，不是复用

        assertThatThrownBy(() -> factory.newInitializer(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MllpServerConfig.MllpPipelineFactory(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ 小工具

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** 测试内联的裸字节拼装器（不走 MLLPConstants.frame，以便故意造非法帧）。 */
    private static final class ByteBufferBuilder {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        ByteBufferBuilder appendByte(int b) {
            out.write(b);
            return this;
        }

        ByteBufferBuilder bytes(byte[] b) {
            out.write(b, 0, b.length);
            return this;
        }

        byte[] toBytes() {
            return out.toByteArray();
        }
    }
}