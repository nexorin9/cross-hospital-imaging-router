package com.crosshospital.imaging.service.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.net.http.HttpClient;

import com.crosshospital.imaging.config.Hl7ConfigProps;
import com.crosshospital.imaging.config.MllpServerConfig;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.repository.Hl7DeadLetterRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.repository.RouteConfigRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.routing.PeerRoutingResolver;
import com.crosshospital.imaging.service.task.FetchTaskService;
import com.crosshospital.imaging.service.task.InMemoryFetchTaskQueue;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Phase 4 · Task 19：触发路由器的<b>装配</b>验证。
 *
 * <p>本类验证的是"容器里到底装进了哪个路由器"这件事 ——
 * {@code MllpServerConfig} 里有一个只记入门日志的兜底实现
 * （{@code Hl7IngressLogRouter}），靠 {@code @ConditionalOnMissingBean(Hl7TriggerRouter.class)} 让位。
 * 一旦让位失败，触发通道会安静地退化成"记一行日志就回 ACK"：
 * HIS 以为调阅已受理，库里却没有任务 —— 这类"看起来成功、其实什么都没做"的故障最难查。
 *
 * <p>因此这里用真实 Spring 容器跑一遍装配，确认：
 * <ul>
 *   <li>业务路由器在场时，兜底实现<b>不</b>被创建，容器里只有一个 {@code Hl7TriggerRouter}；</li>
 *   <li>业务路由器缺席时，兜底实现接管（线路层仍可独立启动）。</li>
 * </ul>
 */
@DisplayName("Task 19 — 触发路由器装配（业务实现 vs 入门日志兜底）")
class Hl7RouterWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MllpServerConfig.class)
            .withBean(Hl7ConfigProps.class, Hl7RouterWiringTest::props);

    private static Hl7ConfigProps props() {
        Hl7ConfigProps props = new Hl7ConfigProps();
        props.getTrigger().setPort(0);
        props.getTrigger().setBindHost("127.0.0.1");
        props.getTrigger().setCharset("UTF-8");
        props.getTrigger().setReleaseMode("MLLP2");
        return props;
    }

    /** 审计替身（{@code AuditService} 是具体类，本机 JVM 上 ByteBuddy 无法内联改写）。 */
    private static final class NoopAuditService extends AuditService {
        NoopAuditService() {
            super(null);
        }

        @Override
        public AuditRow record(AuditRow row) {
            return row;
        }
    }

    private Hl7MessageRouter businessRouter() {
        ObjectMapper mapper = new ObjectMapper();
        Hl7ConfigProps props = props();
        Hl7DeadLetterHandler deadLetterHandler = new Hl7DeadLetterHandler(
                mock(Hl7DeadLetterRepository.class), props, mapper,
                HttpClient.newHttpClient(), Runnable::run);
        return new Hl7MessageRouter(
                new Hl7MessageParser(new Hl7SegmentFieldExtractor()),
                new OrderMessageHandler(
                        new FetchTaskService(mock(FetchTaskRepository.class), new InMemoryFetchTaskQueue()),
                        new NoopAuditService(),
                        new PeerRoutingResolver(mock(RouteConfigRepository.class), mock(PeerRepository.class), mapper),
                        deadLetterHandler),
                new AdmitMessageHandler(new NoopAuditService()),
                deadLetterHandler,
                props);
    }

    @Test
    @DisplayName("业务路由器在场 → 入门日志兜底退位，容器里只有一个 Hl7TriggerRouter")
    void businessRouterWinsOverIngressLogFallback() {
        runner.withBean(Hl7MessageRouter.class, this::businessRouter)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(Hl7TriggerRouter.class);
                    assertThat(ctx.getBean(Hl7TriggerRouter.class))
                            .isInstanceOf(Hl7MessageRouter.class);
                });
    }

    @Test
    @DisplayName("业务路由器缺席 → 入门日志兜底接管（线路层仍可独立启动）")
    void ingressLogFallbackTakesOverWhenBusinessRouterAbsent() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(Hl7TriggerRouter.class);
            assertThat(ctx.getBean(Hl7TriggerRouter.class))
                    .isInstanceOf(Hl7IngressLogRouter.class);
        });
    }
}
