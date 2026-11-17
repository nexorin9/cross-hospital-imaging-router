package com.crosshospital.imaging.service.hl7;

import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import com.crosshospital.imaging.config.Hl7ConfigProps;
import com.crosshospital.imaging.config.MllpServerConfig;
import com.crosshospital.imaging.exception.InvalidMllpFrameException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * MLLP 触发守护：常驻监听本院 HIS 下发的调阅触发消息。
 *
 * <p><b>源主路径采纳</b>：{@code github_ref/dcm4che/dcm4che-hl7/.../MLLPConnection.java:52-149}
 * （{@code readMessage} → {@code writeACK} 的 MLLP2 确认语义、{@code writeMessage} → {@code readACK}
 * 的失败语义、MSH 段头取段日志）与 {@code dcm4che-net-hl7/.../HL7Connection.java:66-87}
 * （写完/读完都回调 monitor，失败也回调）。融合后的产品路径是：
 *
 * <pre>
 *   本院 HIS ──TCP:6661 MLLP帧──▶ [IdleState] ─▶ MllpFrameDecoder ─▶ MllpTriggerHandler
 *                                                                       ├─ Hl7TriggerRouter.route(frame)
 *                                                                       ├─ ACK 0x0B06 0x1C0D   （MLLP2）
 *                                                                       └─ NAK + 断连           （帧非法/路由抛错）
 * </pre>
 *
 * <p>与源产品的差别只有一处、且是刻意的：源产品用 {@code java.net.Socket} 阻塞读 + 每条连接一个线程，
 * 接入十几家 HIS 时线程数与 HIS 侧的心跳/超时策略强耦合。本守护改用 Netty（单线程管多连接），
 * 但<b>帧协议字节、ACK/NAK 字节、MLLP1/MLLP2 分支、失败即断连的处置全部照搬</b> —— 对 HIS 而言
 * 线上看到的字节流与对接一个标准 MLLP 监听端口完全一致。
 *
 * <p>生命周期：{@code @PostConstruct} 绑定端口（配置非法即启动失败，不拖到首条报文），
 * {@code @PreDestroy} 先停 accept、再让 worker 处理完手上帧、最后释放端口 —— 信息科重启本服务时
 * 不会把正发到一半的 ORM^O01 打断在半路。
 */
@Service
@Profile("!test")
public class MllpTriggerService {

    private static final Logger LOG = LoggerFactory.getLogger(MllpTriggerService.class);

    /** 停机时等待在途报文处理完的最长时间。 */
    public static final long SHUTDOWN_QUIET_PERIOD_MS = 500L;
    public static final long SHUTDOWN_TIMEOUT_MS = 5_000L;

    private final Hl7ConfigProps props;
    private final Hl7TriggerRouter router;
    private final MeterRegistry meterRegistry;
    private final MllpAckResponder ackResponder;
    private final MllpServerConfig.MllpPipelineFactory pipelineFactory;
    private final String threadFactoryName;

    private final AtomicReference<Channel> serverChannel = new AtomicReference<>();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicLong framesReceived = new AtomicLong();
    private final AtomicLong acksSent = new AtomicLong();
    private final AtomicLong naksSent = new AtomicLong();
    private final AtomicLong framesRejected = new AtomicLong();
    private final AtomicLong routeFailures = new AtomicLong();

    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile boolean running;

    /**
     * Spring 装配入口。
     *
     * <p><b>必须标 {@code @Autowired}</b>：本类有两个公开构造器（另一个供测试传入线程名前缀），
     * Spring 在"多构造器且无默认构造器"时无法自行选择 —— 不标就会退化成
     * 「找默认构造器 → 找不到 → {@code No default constructor found}」，
     * <b>整个容器起不来</b>（本轮 Task 19 核查时用真实容器才暴露出来）。
     */
    @Autowired
    public MllpTriggerService(Hl7ConfigProps props,
                              Hl7TriggerRouter router,
                              MllpAckResponder ackResponder,
                              MllpServerConfig.MllpPipelineFactory pipelineFactory,
                              MeterRegistry meterRegistry) {
        this(props, router, ackResponder, pipelineFactory, meterRegistry, null);
    }

    /**
     * 显式构造入口（测试用）。
     *
     * @param threadFactoryName worker 线程名前缀；为 null 时用默认名
     */
    public MllpTriggerService(Hl7ConfigProps props,
                              Hl7TriggerRouter router,
                              MllpAckResponder ackResponder,
                              MllpServerConfig.MllpPipelineFactory pipelineFactory,
                              MeterRegistry meterRegistry,
                              String threadFactoryName) {
        if (props == null || router == null || ackResponder == null || pipelineFactory == null) {
            throw new IllegalArgumentException("MllpTriggerService 依赖不能为 null");
        }
        this.props = props;
        this.router = router;
        this.meterRegistry = meterRegistry;
        this.ackResponder = ackResponder;
        this.pipelineFactory = pipelineFactory;
        this.threadFactoryName = threadFactoryName == null ? "mllp-trigger" : threadFactoryName;
        registerMetrics();
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 绑定触发端口。配置非法在此处即失败（端口占用、参数越界都会抛），不拖到第一条报文进来才暴露。
     *
     * @return 实际监听端口（配置为 0 时返回内核分配的端口）
     */
    @PostConstruct
    public int start() {
        Hl7ConfigProps.Trigger cfg = props.getTrigger();
        props.validateIdentity();
        int requestedPort = cfg.validate();

        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory(threadFactoryName + "-boss"));
        workerGroup = new NioEventLoopGroup(effectiveWorkerThreads(cfg),
                new DefaultThreadFactory(threadFactoryName + "-worker"));

        // 每条连接各建一个 MllpTriggerHandler：handler 非 @Sharable，复用会掐掉并发连接
        ChannelInitializer<SocketChannel> initializer =
                pipelineFactory.newInitializer(() -> new MllpTriggerHandler(cfg, ackResponder, router));

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, cfg.getBacklog())
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(initializer);

        try {
            Channel channel = bootstrap.bind(cfg.getBindHost(), requestedPort).sync().channel();
            serverChannel.set(channel);
            running = true;
            int port = ((InetSocketAddress) channel.localAddress()).getPort();
            LOG.info("MLLP 触发守护已启动：{}（本方应用={} 设施={}）", pipelineFactory.describe(),
                    props.getLocalApplication(), props.getLocalFacility());
            return port;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            shutdownGroupsQuietly();
            throw new IllegalStateException("MLLP 触发守护绑定端口被中断", ex);
        } catch (RuntimeException ex) {
            shutdownGroupsQuietly();
            throw ex;
        }
    }

    /** 优雅停机：先停 accept，再让 worker 收尾，最后释放端口。 */
    @PreDestroy
    public void stop() {
        running = false;
        Channel channel = serverChannel.getAndSet(null);
        if (channel != null) {
            try {
                channel.close().sync();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            LOG.info("MLLP 触发守护已停止接收新连接");
        }
        shutdownGroupsQuietly();
        LOG.info("MLLP 触发守护已释放端口；本进程共收到 {} 帧，ACK {} 次，NAK {} 次，拒收 {} 帧",
                framesReceived.get(), acksSent.get(), naksSent.get(), framesRejected.get());
    }

    private void shutdownGroupsQuietly() {
        EventLoopGroup w = workerGroup;
        EventLoopGroup b = bossGroup;
        workerGroup = null;
        bossGroup = null;
        if (w != null) {
            w.shutdownGracefully(SHUTDOWN_QUIET_PERIOD_MS, SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        if (b != null) {
            b.shutdownGracefully(0, SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    // ------------------------------------------------------------------ 运行状态

    /** 守护是否已绑定端口且未停机（供 {@code /actuator/health} 的 hl7 分项）。 */
    public boolean isRunning() {
        return running && serverChannel.get() != null;
    }

    /** 实际监听端口；未启动返回 -1。 */
    public int boundPort() {
        Channel channel = serverChannel.get();
        if (channel == null || channel.localAddress() == null) {
            return -1;
        }
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    public long framesReceived() {
        return framesReceived.get();
    }

    public long acksSent() {
        return acksSent.get();
    }

    public long naksSent() {
        return naksSent.get();
    }

    public long framesRejected() {
        return framesRejected.get();
    }

    public long routeFailures() {
        return routeFailures.get();
    }

    public int activeConnections() {
        return activeConnections.get();
    }

    private static int effectiveWorkerThreads(Hl7ConfigProps.Trigger cfg) {
        int n = cfg.getWorkerThreads();
        if (n > 0) {
            return n;
        }
        return Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
    }

    private void registerMetrics() {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("hl7_frames_received_total")
                .description("MLLP 触发通道收全的帧数")
                .register(meterRegistry);
        Counter.builder("hl7_acks_total").description("回写的 ACK 数").register(meterRegistry);
        Counter.builder("hl7_naks_total").description("回写的 NAK 数").register(meterRegistry);
        Counter.builder("hl7_frames_rejected_total")
                .description("线路层判定非法而拒收的帧数")
                .register(meterRegistry);
        Counter.builder("hl7_route_failures_total")
                .description("帧合法但业务路由抛错的次数")
                .register(meterRegistry);
        meterRegistry.gauge("hl7_active_connections", activeConnections, AtomicInteger::get);
    }

    private void increment(String meter) {
        if (meterRegistry == null) {
            return;
        }
        Counter c = meterRegistry.find(meter).counter();
        if (c != null) {
            c.increment();
        }
    }

    // ------------------------------------------------------------------ 连接级 handler

    /**
     * 单连接的 MLLP 业务 handler。
     *
     * <p>源产品对照：{@code HL7Connection.writeMessage/readMessage}（行 66-87）无论成功失败都回调
     * monitor；本 handler 对应位置是 {@link #exceptionCaught} —— 帧非法与路由异常都走同一条
     * 「NAK + 断连 + 计数 + 日志」出口，不吞异常、不静默重连。
     */
    public final class MllpTriggerHandler extends SimpleChannelInboundHandler<MllpFrame> {

        private final Hl7ConfigProps.Trigger cfg;
        private final MllpAckResponder ack;
        private final Hl7TriggerRouter target;
        private final Charset charset;

        MllpTriggerHandler(Hl7ConfigProps.Trigger cfg, MllpAckResponder ack, Hl7TriggerRouter target) {
            this.cfg = cfg;
            this.ack = ack;
            this.target = target;
            this.charset = cfg.resolveCharset();
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            String peer = peerOf(ctx);
            ctx.channel().attr(MllpFrameDecoder.REMOTE_ADDRESS).set(peer);
            activeConnections.incrementAndGet();
            LOG.info("HL7 触发通道接入：{}（模式 {}）", peer, ack.release());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, MllpFrame frame) {
            framesReceived.incrementAndGet();
            increment("hl7_frames_received_total");
            LOG.info("收到 HL7 触发帧：{}", frame.summary(charset));
            try {
                target.route(frame);
                ack.sendAck(ctx, frame);
                if (ack.isAckRequired()) {
                    // 只有真写出去才计数：MLLP1 下 sendAck 不写任何字节，计进去会让
                    // 「发了多少 ACK」这个排障口径在 MLLP1 部署上凭空虚高
                    acksSent.incrementAndGet();
                    increment("hl7_acks_total");
                }
            } catch (Exception ex) {
                routeFailures.incrementAndGet();
                increment("hl7_route_failures_total");
                LOG.error("HL7 触发帧处理失败，回 NAK 并断开连接：seq={} peer={} bytes={}",
                        frame.sequence(), frame.remoteAddress(), frame.size(), ex);
                sendNakAndCount(ctx, ex);
                ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Throwable root = unwrapDecoderWrapper(cause);
            framesRejected.incrementAndGet();
            increment("hl7_frames_rejected_total");
            if (root instanceof InvalidMllpFrameException frameEx) {
                LOG.warn("HL7 触发帧非法，已回 NAK 并断开连接：error={} peer={} 已收字节={}",
                        frameEx.getError().code(), frameEx.getRemoteAddress(), frameEx.getFrameLength());
            } else {
                LOG.warn("HL7 触发通道异常，已回 NAK 并断开连接：peer={}", peerOf(ctx), root);
            }
            sendNakAndCount(ctx, root);
            ctx.close();
        }

        private void sendNakAndCount(ChannelHandlerContext ctx, Throwable cause) {
            ack.sendNak(ctx, cause);
            if (ack.isAckRequired()) {
                naksSent.incrementAndGet();
                increment("hl7_naks_total");
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
                if (cfg.isCloseOnIdle()) {
                    LOG.warn("HL7 触发连接空闲超过 {}ms（close-on-idle=true），主动断开：{}", cfg.getIdleTimeoutMs(), peerOf(ctx));
                    ctx.close();
                } else {
                    LOG.debug("HL7 触发连接空闲超过 {}ms（close-on-idle=false，保持长连）：{}",
                            cfg.getIdleTimeoutMs(), peerOf(ctx));
                }
                return;
            }
            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            int now = activeConnections.decrementAndGet();
            LOG.info("HL7 触发通道断开：{}（当前在线连接 {} 条）", peerOf(ctx), now);
            ctx.fireChannelInactive();
        }
    }

    /** Netty 会把解码器抛出的异常包一层 {@code DecoderException}，此处剥掉以便识别自家失败码。 */
    private static Throwable unwrapDecoderWrapper(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur instanceof InvalidMllpFrameException || cur instanceof io.netty.handler.codec.DecoderException) {
                break;
            }
            if (cur.getCause() == null || cur.getCause() == cur) {
                break;
            }
            cur = cur.getCause();
        }
        return cur == null ? t : cur;
    }

    private static String peerOf(ChannelHandlerContext ctx) {
        if (ctx == null || ctx.channel() == null) {
            return "unknown";
        }
        String v = ctx.channel().attr(MllpFrameDecoder.REMOTE_ADDRESS).get();
        if (v != null) {
            return v;
        }
        return ctx.channel().remoteAddress() == null ? "unknown" : ctx.channel().remoteAddress().toString();
    }

    /** 供启动日志与健康检查使用的一行摘要（不含任何口令位）。 */
    public String describe() {
        Hl7ConfigProps.Trigger cfg = props.getTrigger();
        return "MLLP 触发守护 running=" + isRunning()
                + " port=" + boundPort()
                + " release=" + cfg.getReleaseMode()
                + " charset=" + cfg.getCharset()
                + " frames=" + framesReceived.get()
                + " acks=" + acksSent.get()
                + " naks=" + naksSent.get()
                + " rejected=" + framesRejected.get();
    }
}