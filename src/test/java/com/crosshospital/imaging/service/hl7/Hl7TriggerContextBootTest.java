package com.crosshospital.imaging.service.hl7;

import static org.assertj.core.api.Assertions.assertThat;

import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.repository.Hl7DeadLetterRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.repository.RouteConfigRepository;
import com.crosshospital.imaging.service.task.FetchTaskService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Phase 4 · Task 19 真实容器装配核验：整个应用上下文能否起来、触发链路的 bean 是否装对。
 *
 * <p><b>为什么必须补这个测试</b>：本仓库原有的 {@code @SpringBootTest} 只用了
 * {@code @ActiveProfiles("test")}，而该 profile 把
 * {@code JpaRepositoriesAutoConfiguration} 与 {@code HibernateJpaAutoConfiguration}
 * 整个排除掉了 —— 也就是说<b>所有 Spring Data Repository 与全部 {@code @Query} 从未在
 * 真实容器里校验过</b>。后果是：某个 {@code @Query} 写错属性名时，
 * Spring Data 在建仓阶段就抛 {@code QueryCreationException}，
 * <b>整个应用起不来</b>，而测试全绿（本仓库曾以 {@code a.source} 引用实体上不存在的属性，
 * 靠这条测试才暴露出来）。
 *
 * <p>本测试用<b>默认 profile</b>（不走那个"排除 JPA"的测试 profile）起真实容器：
 * <ul>
 *   <li>上下文能加载 —— 证明所有 Repository 的 JPQL 都通过校验；</li>
 *   <li>Task 19 的业务 bean 都在场，且注入进去的确实是业务路由器（不是入门日志兜底）；</li>
 *   <li>MLLP 守护真的绑上了端口（{@code @PostConstruct} 起得来）。</li>
 * </ul>
 *
 * <p>数据库用 H2（PostgreSQL 兼容模式），{@code ddl-auto=create-drop} 让 Hibernate 按实体建表；
 * Flyway 关闭（迁移脚本是 PostgreSQL 方言，真实落库验证由 task 35 的 Testcontainers 集成测试承担）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        // NON_KEYWORDS=KEY,VALUE：H2 把 key / value 当保留字，而 route_config 表在
        // PostgreSQL（生产库）里就是这么建的（非保留字）。这是 H2 侧的适配，不是产品行为。
        "spring.datasource.url=jdbc:h2:mem:hl7boot;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        // 端口交给内核分配，避免与本机 6661 / 11112 / 11113 冲突
        "hl7.trigger.port=0",
        "routing.local-move-dest-port=0"
})
@DisplayName("Task 19 — 真实 Spring 容器：上下文可启动 + 触发链路装配正确")
class Hl7TriggerContextBootTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Hl7TriggerRouter triggerRouter;

    @Autowired
    private MllpTriggerService triggerService;

    @Test
    @DisplayName("默认 profile 下整个上下文能起来（所有 Repository 的 JPQL 通过校验）")
    void applicationContextStartsWithRealJpaRepositories() {
        assertThat(context).isNotNull();
        assertThat(context.getBean(FetchTaskRepository.class)).isNotNull();
        assertThat(context.getBean(Hl7DeadLetterRepository.class)).isNotNull();
        assertThat(context.getBean(PeerRepository.class)).isNotNull();
        assertThat(context.getBean(RouteConfigRepository.class)).isNotNull();
    }

    @Test
    @DisplayName("注入到 MLLP 守护的是业务路由器（不是入门日志兜底）")
    void businessRouterIsTheOneWiredIntoTheDaemon() {
        assertThat(triggerRouter).isInstanceOf(Hl7MessageRouter.class);
        assertThat(context.getBean(Hl7MessageRouter.class)).isSameAs(triggerRouter);
        // 兜底路由器（只记入门日志）必须没被创建：它一旦顶上来，触发通道会退化成
        // 「记一行日志就回 ACK」—— HIS 以为已受理，库里却没有任务。
        assertThat(context.getBeanNamesForType(Hl7IngressLogRouter.class)).isEmpty();
    }

    @Test
    @DisplayName("Task 19 的业务 bean 都在场：入队服务 + 死信处理 + 订单处理器")
    void task19BeansArePresentInTheContainer() {
        assertThat(context.getBean(FetchTaskService.class)).isNotNull();
        assertThat(context.getBean(Hl7DeadLetterHandler.class)).isNotNull();
        assertThat(context.getBean(OrderMessageHandler.class)).isNotNull();
        assertThat(context.getBean(AdmitMessageHandler.class)).isNotNull();
    }

    @Test
    @DisplayName("MLLP 守护在容器启动时真的绑上了端口（@PostConstruct 起得来）")
    void mllpDaemonActuallyBoundItsPort() {
        assertThat(triggerService.isRunning()).isTrue();
        assertThat(triggerService.boundPort()).isGreaterThan(0);
    }
}
