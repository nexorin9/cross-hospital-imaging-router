package com.crosshospital.imaging.service.wado;

import com.crosshospital.imaging.config.WadoConfigProps;
import com.crosshospital.imaging.domain.AiCallAudit;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.exception.WadoRsHttpException;
import com.crosshospital.imaging.repository.AiCallAuditRepository;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.support.TestDicomFixtures;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 12 端到端：WadoRsClient + 重试策略 + 容量监控 + 失败审计。
 *
 * <p>覆盖 task 步骤要求的三种失败场景：
 * <ol>
 *   <li>500 → 重试 → 失败</li>
 *   <li>404 → 不重试</li>
 *   <li>网络断开 → 重试 → 失败</li>
 * </ol>
 * 加上成功路径与重试耗尽后 exhausted 异常的断言，以及 Micrometer 指标 / 失败审计落库断言。
 */
class WadoRsRetryAndCapacityIntegrationTest {

    private static final String STUDY = TestDicomFixtures.EXAMPLE_STUDY_UID;
    private static final String BOUNDARY = "boundary-CHIRADIAG-01";
    private static final String MULTIPART_CT = "multipart/related; type=\"application/dicom\"; boundary=\""
            + BOUNDARY + "\"";

    private WireMockServer server;
    private WadoConfigProps props;
    private MeterRegistry registry;
    private WadoCapacityMonitor monitor;
    private WadoRsAuditWriter auditWriter;
    private AuditRepository auditRepository;
    private AiCallAuditRepository aiCallAuditRepository;
    private WadoRsRetryPolicy retryPolicy;
    private WadoRsClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        props = new WadoConfigProps();
        props.setBaseUrlTemplate("http://127.0.0.1:" + server.port() + "/wado/studies/{studyUid}");
        props.setMaxRetries(2); // 减少重试次数避免测试慢

        registry = new SimpleMeterRegistry();
        monitor = new WadoCapacityMonitor(registry);

        auditRepository = mock(AuditRepository.class);
        aiCallAuditRepository = mock(AiCallAuditRepository.class);
        when(auditRepository.save(any(AuditRow.class))).thenAnswer(inv -> inv.getArgument(0));
        when(aiCallAuditRepository.save(any(AiCallAudit.class))).thenAnswer(inv -> inv.getArgument(0));
        auditWriter = new WadoRsAuditWriter(auditRepository, aiCallAuditRepository);

        retryPolicy = new WadoRsRetryPolicy(props);

        MultipartDicomExtractor extractor = new MultipartDicomExtractor(props);
        client = new WadoRsClient(props, extractor, retryPolicy, monitor, auditWriter);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // ---------- 重试：500 → 失败 → 耗尽 ----------

    @Test
    @DisplayName("500 重复响应 → 重试 maxRetries+1 次后抛 WadoRsHttpException + 落审计 + 累计指标")
    void retriesOnServerErrorThenFails() {
        // 永远 500，WireMock scenario 让同一 stub 每次都返回 500
        server.stubFor(get(urlPathMatching("/wado/studies/.*"))
                .willReturn(aResponse().withStatus(500).withBody("down")));

        long start = System.currentTimeMillis();
        WadoRsHttpException ex = assertThrows(WadoRsHttpException.class,
                () -> client.fetchStudy("PEER_HZ", STUDY));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(500, ex.getStatusCode());
        assertFalse(ex.isRetryable(), "重试耗尽后 retryable 必须为 false");
        assertEquals(WadoRsHttpException.Reason.SERVER_ERROR, ex.getReason());

        // 期望出站次数：1 首次 + 2 重试 = 3 次（maxRetries=2）
        assertEquals(3, server.getAllServeEvents().size(),
                "应被重试 maxRetries+1 次（首次 1 + 重试 2）");

        // 重试计数器累加
        assertTrue(monitor.currentRetries() >= 2.0,
                "retries 计数器 >= 2，实际 " + monitor.currentRetries());

        // errors 累计
        assertTrue(monitor.currentErrorsTotal() >= 1.0,
                "errors 累计至少 1，实际 " + monitor.currentErrorsTotal());

        // 审计落库（一次失败审计，最后一次重试耗尽后调一次）
        verify(auditRepository, atLeastOnce()).save(any(AuditRow.class));
        verify(aiCallAuditRepository, atLeastOnce()).save(any(AiCallAudit.class));

        // 实际耗时至少 1 次退避（max backoff 30s，但 1000ms*1 + 2000ms*1 = 至少 2 秒会有抖动）
        assertTrue(elapsed >= 100L, "重试至少 sleep 一段，elapsed 应 >= 100ms，实测 " + elapsed);
    }

    @Test
    @DisplayName("500 → 200 → 200：前两次失败后第三次成功 → 不落失败审计")
    void retriesAndEventuallySucceeds() {
        server.stubFor(get(urlPathMatching("/wado/studies/.*"))
                .inScenario("first-fail-then-ok")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("server-error")
                .willReturn(aResponse().withStatus(500).withBody("down")));
        server.stubFor(get(urlPathMatching("/wado/studies/.*"))
                .inScenario("first-fail-then-ok")
                .whenScenarioStateIs("server-error")
                .willSetStateTo("ok")
                .willReturn(aResponse().withStatus(500).withBody("down")));
        server.stubFor(get(urlPathMatching("/wado/studies/.*"))
                .inScenario("first-fail-then-ok")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", MULTIPART_CT)
                        .withBody(TestDicomFixtures.multipartRelated(BOUNDARY, "application/dicom",
                                TestDicomFixtures.dicomInstance(STUDY, 1)))));

        var result = client.fetchStudyDetailed("PEER_HZ", STUDY);

        assertNotNull(result);
        assertEquals(1, result.instanceCount());
        assertEquals(3, server.getAllServeEvents().size());

        // 成功路径不落失败审计
        verify(auditRepository, never()).save(any(AuditRow.class));
        verify(aiCallAuditRepository, never()).save(any(AiCallAudit.class));

        // 成功指标累加
        assertTrue(monitor.currentBytesFetched() > 0);
        assertTrue(monitor.currentObjectsFetched() > 0);
        assertTrue(monitor.currentRetries() >= 2.0, "成功前 2 次失败进入重试退避");
    }

    // ---------- 4xx 不重试 ----------

    @Test
    @DisplayName("404 → 不重试；仅 1 次出站 + 1 行失败审计")
    void fourHundredNotFoundDoesNotRetry() {
        server.stubFor(get(urlPathMatching("/wado/studies/.*"))
                .willReturn(aResponse().withStatus(404).withBody("study not found")));

        WadoRsHttpException ex = assertThrows(WadoRsHttpException.class,
                () -> client.fetchStudy("PEER_HZ", STUDY));

        assertEquals(404, ex.getStatusCode());
        assertFalse(ex.isRetryable());
        assertEquals(WadoRsHttpException.Reason.STUDY_NOT_FOUND, ex.getReason());

        // 仅 1 次出站，无重试
        assertEquals(1, server.getAllServeEvents().size());
        assertEquals(0.0, monitor.currentRetries(), "404 不重试 → retries 计数器 0");

        // 落 1 行审计
        verify(auditRepository, times(1)).save(any(AuditRow.class));
        verify(aiCallAuditRepository, times(1)).save(any(AiCallAudit.class));
    }

    @Test
    @DisplayName("416 → 不重试；reason=RANGE_NOT_SATISFIABLE")
    void fourSixteenDoesNotRetry() {
        server.stubFor(get(urlPathMatching("/wado/studies/.*"))
                .willReturn(aResponse().withStatus(416).withBody("range unsatisfiable")));

        WadoRsHttpException ex = assertThrows(WadoRsHttpException.class,
                () -> client.fetchStudy("PEER_HZ", STUDY));

        assertEquals(416, ex.getStatusCode());
        assertFalse(ex.isRetryable());
        assertEquals(WadoRsHttpException.Reason.RANGE_NOT_SATISFIABLE, ex.getReason());
        assertEquals(1, server.getAllServeEvents().size());
    }

    // ---------- 网络断开 → 重试 → 失败 ----------

    @Test
    @DisplayName("连接拒绝（无监听）→ 重试耗尽 → TRANSPORT_FAILURE")
    void retriesOnConnectionRefusedAndThrows() {
        // 关闭 server 让 WireMock 端口变空闲，让 OkHttp 拿到 ConnectException
        server.stop();

        WadoRsHttpException ex = assertThrows(WadoRsHttpException.class,
                () -> client.fetchStudy("PEER_HZ", STUDY));

        assertEquals(WadoRsHttpException.STATUS_TRANSPORT_FAILURE, ex.getStatusCode());
        assertFalse(ex.isRetryable(), "重试耗尽后 retryable=false");
        assertEquals(WadoRsHttpException.Reason.TRANSPORT_FAILURE, ex.getReason());

        // errors_total 累加
        assertTrue(monitor.currentErrorsTotal() >= 1.0);
        // retries 计数器：每次退避前 +1，至少 maxRetries 次
        assertTrue(monitor.currentRetries() >= 2.0,
                "应至少重试 2 次（maxRetries=2），实际 " + monitor.currentRetries());

        // 落审计
        verify(auditRepository, atLeastOnce()).save(any(AuditRow.class));
        verify(aiCallAuditRepository, atLeastOnce()).save(any(AiCallAudit.class));
    }

    // ---------- 解析失败 ----------

    @Test
    @DisplayName("200 但响应缺 boundary → 不重试；落 ai_call_audit(status=parse_error)")
    void parseErrorDoesNotRetryButAudits() {
        // 返回 200 + 非 multipart 的 Content-Type
        server.stubFor(get(urlPathMatching("/wado/studies/.*"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html>nope</html>")));

        assertThrows(com.crosshospital.imaging.exception.WadoRsParseException.class,
                () -> client.fetchStudy("PEER_HZ", STUDY));

        assertEquals(1, server.getAllServeEvents().size(), "解析失败不重试");
        assertEquals(0.0, monitor.currentRetries(), "解析失败不进入退避");
        verify(auditRepository, times(1)).save(any(AuditRow.class));
        verify(aiCallAuditRepository, times(1)).save(any(AiCallAudit.class));
    }

    // ---------- 成功路径：指标累加正确 ----------

    @Test
    @DisplayName("成功拉取 3 个对象 → 指标 bytes_fetched / objects_fetched 累加；不落失败审计")
    void successfulFetchUpdatesMetrics() {
        server.stubFor(get(urlPathMatching("/wado/studies/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", MULTIPART_CT)
                        .withBody(TestDicomFixtures.multipartRelated(BOUNDARY, "application/dicom",
                                TestDicomFixtures.dicomInstance(STUDY, 1),
                                TestDicomFixtures.dicomInstance(STUDY, 2),
                                TestDicomFixtures.dicomInstance(STUDY, 3)))));

        var result = client.fetchStudyDetailed("PEER_HZ", STUDY);

        assertEquals(3, result.instanceCount());
        assertEquals(1, server.getAllServeEvents().size());

        assertTrue(monitor.currentBytesFetched() > 0);
        assertTrue(monitor.currentObjectsFetched() >= 3.0);
        assertEquals(0.0, monitor.currentRetries());
        assertEquals(0.0, monitor.currentErrorsTotal());

        verify(auditRepository, never()).save(any(AuditRow.class));
        verify(aiCallAuditRepository, never()).save(any(AiCallAudit.class));
    }

    // ---------- 容量监控指标名称 ----------

    @Test
    @DisplayName("Micrometer 注册了 wado.bytes_fetched / wado.objects_fetched / wado.errors / wado.retries / wado.fetch_duration_seconds / wado.cache_mb_used")
    void expectedMetersAreRegistered() {
        assertNotNull(registry.find("wado.bytes_fetched").counter());
        assertNotNull(registry.find("wado.objects_fetched").counter());
        assertNotNull(registry.find("wado.errors").tag("layer", "http").counter());
        assertNotNull(registry.find("wado.errors").tag("layer", "parse").counter());
        assertNotNull(registry.find("wado.errors").tag("layer", "unknown").counter());
        assertNotNull(registry.find("wado.retries").counter());
        assertNotNull(registry.find("wado.fetch_duration_seconds").timer());
        assertNotNull(registry.find("wado.cache_mb_used").gauge());
    }

    @Test
    @DisplayName("updateCacheBytes(0) → cache_mb_used gauge = 0")
    void cacheGaugeStartsAtZero() {
        monitor.updateCacheBytes(0L);
        assertEquals(0.0, registry.find("wado.cache_mb_used").gauge().value(), 0.0001);
    }

    @Test
    @DisplayName("updateCacheBytes(5MB) → cache_mb_used gauge = 5.0")
    void cacheGaugeReflectsBytes() {
        monitor.updateCacheBytes(5L * 1024 * 1024);
        double v = registry.find("wado.cache_mb_used").gauge().value();
        assertEquals(5.0, v, 0.01);
    }
}