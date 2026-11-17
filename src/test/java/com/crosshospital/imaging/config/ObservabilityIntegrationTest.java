package com.crosshospital.imaging.config;

import com.crosshospital.imaging.service.metrics.ImagingMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 37 — 结构化日志 + Micrometer/Prometheus 的端到端核验。
 *
 * <p>用真 Spring 上下文：验证指标 bean 真的装配上、{@code /actuator/prometheus}
 * 真的把业务指标暴露出来、跑一次动作后计数器真的 +1，以及 MDC 上下文
 * 能不能带着 traceId/spanId/who/taskId 进结构化日志并正确清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
@TestPropertySource(properties = "management.endpoints.web.exposure.include=health,info,prometheus")
@DisplayName("Task 37 — 结构化日志与 Prometheus 指标")
class ObservabilityIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ImagingMetrics metrics;

    @Test
    @DisplayName("/actuator/prometheus 暴露全部 8 个业务指标")
    void prometheusExposesAllBusinessMetrics() throws Exception {
        // 让 counter / timer 都至少有一条样本，Prometheus 才会输出对应行
        metrics.fetchAccepted();
        metrics.fetchFailed();
        metrics.aiCallStarted();
        metrics.dicomHandshake();
        metrics.fetchCompleted(metrics.startFetch());
        metrics.aiCallCompleted(metrics.startAiCall());

        String body = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String name : new String[]{
                ImagingMetrics.FETCH_TOTAL, ImagingMetrics.FETCH_FAILED_TOTAL,
                ImagingMetrics.FETCH_DURATION, ImagingMetrics.AI_CALL_TOTAL,
                ImagingMetrics.AI_CALL_LATENCY, ImagingMetrics.DCM_HANDSHAKE_TOTAL,
                ImagingMetrics.PEERS_COUNT, ImagingMetrics.DLQ_UNRESOLVED}) {
            assertTrue(body.contains(name), "指标缺失：" + name);
        }
    }

    @Test
    @DisplayName("跑一次 fetch 后 imaging_fetch_total 累加 1（MeterRegistry 侧独立核对）")
    void fetchCounterIncrementsByOne() {
        double before = metrics.fetchTotalCount();
        metrics.fetchAccepted();

        assertEquals(before + 1, metrics.fetchTotalCount());
        assertEquals(before + 1,
                meterRegistry.get(ImagingMetrics.FETCH_TOTAL).counter().count(),
                "门面与容器 MeterRegistry 必须是同一个 counter");
    }

    @Test
    @DisplayName("TaskContext 把 traceId/spanId/who/taskId 放进 MDC，close 后精确复原")
    void taskContextManagesMdcLifecycle() {
        MDC.clear();
        UUID taskId = UUID.randomUUID();
        try (TaskContext ignored = TaskContext.open(taskId, "employee_007")) {
            assertEquals(taskId.toString(), MDC.get("taskId"));
            assertEquals("employee_007", MDC.get("who"));
            assertNotNull(MDC.get("traceId"));
            assertNotNull(MDC.get("spanId"));
        }
        assertNull(MDC.get("taskId"), "close 后不得把 taskId 泄漏给线程池下一个任务");
        assertNull(MDC.get("who"));
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("spanId"));

        // 嵌套：外层已有 traceId 时内层复用，不另造
        try (TaskContext outer = TaskContext.open(taskId, "outer")) {
            String trace = MDC.get("traceId");
            try (TaskContext inner = TaskContext.open(taskId, "inner")) {
                assertEquals(trace, MDC.get("traceId"), "同一次链路必须复用同一个 traceId");
                assertEquals("inner", MDC.get("who"));
            }
            assertEquals("outer", MDC.get("who"), "内层 close 要恢复外层 who");
        }
    }

    @Test
    @DisplayName("logback-spring.xml 用 JSON encoder 且包含 MDC provider")
    void structuredLoggingConfigIsWired() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/logback-spring.xml")) {
            assertNotNull(in, "logback-spring.xml 不在 classpath 上");
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(xml.contains("LoggingEventCompositeJsonEncoder"),
                    "生产 profile 必须输出 JSON");
            assertTrue(xml.contains("<mdc/>"), "JSON 日志必须带 MDC 字段");
            assertTrue(xml.contains("springProfile name=\"test\""),
                    "测试 profile 保留纯文本日志，避免用例输出被 JSON 淹没");
        }
    }
}
