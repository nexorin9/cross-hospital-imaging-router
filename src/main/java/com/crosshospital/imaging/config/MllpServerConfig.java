package com.crosshospital.imaging.config;

import com.crosshospital.imaging.service.hl7.Hl7IngressLogRouter;
import com.crosshospital.imaging.service.hl7.Hl7TriggerRouter;
import com.crosshospital.imaging.service.hl7.MllpFrameDecoder;
import com.crosshospital.imaging.service.hl7.MllpRelease;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * MLLP 触发通道的 Netty 装配面。
 *
 * <p>本配置只负责两件事：把 {@code hl7.*} 配置面翻译成运行时对象，以及给出<b>每条连接一条</b>的
 * {@link ChannelInitializer} 模板。端口绑定与停机归 {@code MllpTriggerService}（{@code @PostConstruct}
 * / {@code @PreDestroy}）——Spring 只调一次 stop()，由它统一做优雅停机，比让容器分别管理
 * ServerBootstrap 与 EventLoopGroup 更不容易漏半边。
 *
 * <p>源主路径证据：{@code github_ref/dcm4che/dcm4che-hl7/.../MLLPConnection.java:64-98}
 * —— 源产品把 {@code MLLPRelease} 与 Socket 绑在一起构造；这里沿用「释放模式是通道级属性、
 * 帧编解码是连接级属性」的分层。
 */
@Configuration
@Profile("!test")
public class MllpServerConfig {

    /**
     * 释放模式 bean。取值非法在此处即失败（{@link MllpRelease#parse}），不会拖到第一条报文进来才炸。
     */
    @Bean
    public MllpRelease mllpRelease(Hl7ConfigProps props) {
        return props.getTrigger().resolveReleaseMode();
    }

    /**
     * 业务路由器：优先用 task 18/19 提供的实现；未提供时退回入门日志实现，保证线路层可独立启动。
     *
     * <p>{@code @ConditionalOnMissingBean(Hl7TriggerRouter.class)} 判的是<b>接口</b>，
     * 因此 task 19 只要往容器里放一个 {@code Hl7TriggerRouter} 实现，本 bean 自动退位。
     */
    @Bean
    @ConditionalOnMissingBean(Hl7TriggerRouter.class)
    public Hl7TriggerRouter mllpTriggerRouter() {
        return new Hl7IngressLogRouter();
    }

    /**
     * 管线工厂。由 {@code MllpTriggerService} 在绑定端口时调用，为每条入站连接新建
     * {@code [IdleStateHandler] -> MllpFrameDecoder -> 每连接独立 handler}。
     */
    @Bean
    public MllpPipelineFactory mllpPipelineFactory(Hl7ConfigProps props) {
        return new MllpPipelineFactory(props);
    }

    /**
     * MLLP 连接管线模板。
     *
     * <p>刻意<b>不</b>做成单例 {@code ChannelInitializer}：Netty 的 {@code ChannelInitializer}
     * 会在被 {@code remove()} 后复用，直接注册单例会让第二条连接复用已被释放的 decoder 状态。
     */
    public static final class MllpPipelineFactory {

        private final Hl7ConfigProps props;

        public MllpPipelineFactory(Hl7ConfigProps props) {
            if (props == null) {
                throw new IllegalArgumentException("Hl7ConfigProps 不能为 null");
            }
            this.props = props;
        }

        /**
         * 为一条新连接装配管线。
         *
         * @param handlerFactory 每条连接<b>各新建一个</b> handler 的工厂。刻意不用单例：
         *                      handler 不是 {@code @Sharable}，把同一个实例挂到第二条连接的
         *                      pipeline 上会抛 {@code ChannelPipelineException} 并静默掐掉那条连接
         *                      （首轮并发用例实测踩到：5 条并发只有 1 条通）。
         */
        public ChannelInitializer<SocketChannel> newInitializer(Supplier<ChannelHandler> handlerFactory) {
            if (handlerFactory == null) {
                throw new IllegalArgumentException("业务 handler 工厂不能为 null");
            }
            Hl7ConfigProps.Trigger cfg = props.getTrigger();
            int maxFrameBytes = cfg.getMaxFrameBytes();
            long idleMs = cfg.getIdleTimeoutMs();
            return new ChannelInitializer<>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelHandler handler = handlerFactory.get();
                    if (handler == null) {
                        throw new IllegalStateException("业务 handler 工厂返回了 null");
                    }
                    if (idleMs > 0) {
                        ch.pipeline().addLast("mllp-idle",
                                new IdleStateHandler(idleMs, 0L, 0L, TimeUnit.MILLISECONDS));
                    }
                    ch.pipeline().addLast("mllp-decoder", new MllpFrameDecoder(maxFrameBytes));
                    ch.pipeline().addLast("mllp-trigger", handler);
                }
            };
        }

        /** 供测试与运维查看生效参数（不打印任何口令位）。 */
        public String describe() {
            Hl7ConfigProps.Trigger cfg = props.getTrigger();
            return "MLLP 触发通道 bind=" + cfg.getBindHost() + ":" + cfg.getPort()
                    + " release=" + cfg.getReleaseMode()
                    + " charset=" + cfg.getCharset()
                    + " maxFrameBytes=" + cfg.getMaxFrameBytes()
                    + " idleTimeoutMs=" + cfg.getIdleTimeoutMs()
                    + " closeOnIdle=" + cfg.isCloseOnIdle();
        }
    }
}