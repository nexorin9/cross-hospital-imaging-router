package com.crosshospital.imaging.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.crosshospital.imaging.api.DlqController;
import com.crosshospital.imaging.config.DlqConfigProps;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.repository.DlqRepository;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.scheduled.DlqRetentionCleaner;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.routing.DlqEnqueuePort;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Task 22 — 死信链路的 Spring 装配检查。
 *
 * <p>单元测试都是手工 new 出被测对象，验不到「Bean 能不能真的装配起来」 ——
 * 而这恰恰是本任务最容易翻车的地方：{@code DlqService} 实现 {@link DlqEnqueuePort}，
 * 上游 {@code RoutingFailureHandler} / {@code FetchTaskService} 都靠 setter 注入这个端口，
 * 一旦有第二个实现或实现缺失，注入会静默落空（{@code required=false}），
 * 结果是「死信永远进不去」而没有任何报错 —— 那种坏法最难查。
 *
 * <p>本测试用真实的 Spring 容器（仓储用替身）把整条链路装配起来，
 * 断言：① 端口确实被注入到服务层；② 控制器 / 清理器 / 告警器都在容器里。
 */
@DisplayName("Task 22 — 死信链路 Spring 装配")
class DlqWiringContextTest {

    @Test
    @DisplayName("DlqService 能被装配成 DlqEnqueuePort（上游注入点不会静默落空）")
    void dlqServiceIsRegisteredAsEnqueuePort() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(DlqRepository.class, () -> mock(DlqRepository.class));
            ctx.registerBean(FetchTaskRepository.class, () -> mock(FetchTaskRepository.class));
            ctx.registerBean(AuditRepository.class, () -> mock(AuditRepository.class));
            ctx.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            ctx.register(DlqConfigProps.class);
            ctx.register(AuditService.class);
            ctx.register(DlqAlertNotifier.class);
            ctx.register(DlqService.class);
            ctx.register(DlqRetentionCleaner.class);
            ctx.register(DlqController.class);
            ctx.refresh();

            assertThat(ctx.getBean(DlqEnqueuePort.class)).isSameAs(ctx.getBean(DlqService.class));
            assertThat(ctx.getBean(DlqController.class)).isNotNull();
            assertThat(ctx.getBean(DlqRetentionCleaner.class)).isNotNull();
            assertThat(ctx.getBean(DlqAlertNotifier.class)).isNotNull();
            assertThat(ctx.getBeansOfType(DlqEnqueuePort.class)).hasSize(1);
        }
    }
}
