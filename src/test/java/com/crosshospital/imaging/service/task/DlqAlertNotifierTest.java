package com.crosshospital.imaging.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.crosshospital.imaging.config.DlqConfigProps;
import com.crosshospital.imaging.domain.DlqEntry;
import com.crosshospital.imaging.repository.DlqRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 22 — 死信告警 webhook 测试。
 *
 * <p>告警网关用<b>真 HTTP</b>验证：起一个本机 {@link HttpServer} 当告警网关，
 * 让告警器用真 {@link HttpClient} 打过去，断言「真的发出了、报文里有什么、收了几条」。
 * 这比 mock 客户端更能暴露「URL 拼错 / 报文序列化错 / 冷却判定写反」这类问题。
 */
@DisplayName("Task 22 — 死信告警 webhook 投递与冷却抑制")
class DlqAlertNotifierTest {

    private final DlqRepository repository = mock(DlqRepository.class);

    private HttpServer alertServer;
    /** 收到的告警报文（按顺序）。 */
    private final List<String> receivedBodies = new ArrayList<>();
    /** 网关返回的状态码。 */
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    /** 可控时钟（冷却窗口到期判定）。 */
    private Instant now = Instant.parse("2026-11-06T02:00:00Z");
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        receivedBodies.clear();
        responseStatus.set(200);
        now = Instant.parse("2026-11-06T02:00:00Z");
        clock = new MutableClock(now);
        // 库里没有「最近成功告警过」的同码行（跨实例冷却判定的默认态）
        when(repository.countAlertedSince(anyString(), any(Instant.class))).thenReturn(0L);
        when(repository.save(any(DlqEntry.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void stopServer() {
        if (alertServer != null) {
            alertServer.stop(0);
            alertServer = null;
        }
    }

    /** 起一个本机告警网关：收到 POST 就把报文原文存下来。 */
    private int startAlertServer() throws IOException {
        alertServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        alertServer.createContext("/alert", exchange -> {
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            int status = responseStatus.get();
            exchange.sendResponseHeaders(status, -1);
            exchange.getResponseBody().close();
        });
        alertServer.start();
        return alertServer.getAddress().getPort();
    }

    private static DlqConfigProps props(String webhookUrl, int cooldownMinutes) {
        DlqConfigProps props = new DlqConfigProps();
        props.setAlertWebhookUrl(webhookUrl);
        props.setAlertCooldownMinutes(cooldownMinutes);
        props.setAlertTimeoutMs(2000);
        return props;
    }

    private DlqAlertNotifier notifier(DlqConfigProps props) {
        return new DlqAlertNotifier(repository, props, new ObjectMapper(),
                HttpClient.newHttpClient(), clock);
    }

    /** 可推进的时钟 —— 冷却窗口的到期判定需要可控时间。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant value) {
            this.instant = value;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static DlqEntry entry(String failureCode, int retries) {
        DlqEntry entry = new DlqEntry();
        entry.setId(1L);
        entry.setTaskId(UUID.randomUUID());
        entry.setFailureCode(failureCode);
        entry.setFailureMessage("外院 PACS 拒绝连接");
        entry.setRetryAttempts(retries);
        entry.setFirstFailedAt(Instant.parse("2026-11-06T01:58:00Z"));
        entry.setLastRetryAt(Instant.parse("2026-11-06T02:00:00Z"));
        entry.setCreatedAt(Instant.parse("2026-11-06T02:00:00Z"));
        entry.setAlertWebhookSent(Boolean.FALSE);
        return entry;
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("未配置告警地址 → 只落库不推告警（不产生任何外发调用）")
    void webhookNotConfiguredSendsNothing() {
        DlqAlertNotifier notifier = notifier(props("", 10));

        DlqAlertResult result = notifier.dispatch(entry("connection_refused", 3));

        assertThat(result.status()).isEqualTo(DlqAlertResult.Status.NOT_CONFIGURED);
        assertThat(result.delivered()).isFalse();
        assertThat(receivedBodies).isEmpty();
    }

    @Test
    @DisplayName("告警网关可达 → 真的 POST 出去，报文含 task_id / failure_code / failure_message / retry_attempts")
    void alertIsPostedWithTaskAndFailureFields() throws Exception {
        int port = startAlertServer();
        DlqAlertNotifier notifier = notifier(props("http://127.0.0.1:" + port + "/alert", 10));

        DlqAlertResult result = notifier.dispatch(entry("connection_refused", 3));

        assertThat(result.status()).isEqualTo(DlqAlertResult.Status.SENT);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(receivedBodies).hasSize(1);
        String body = receivedBodies.get(0);
        assertThat(body).contains("task_id").contains("connection_refused")
                .contains("外院 PACS 拒绝连接").contains("\"retry_attempts\":3");
    }

    @Test
    @DisplayName("告警报文不含病人身份字段（告警发给值班群，不是病历）")
    void alertPayloadCarriesNoPatientIdentity() throws Exception {
        int port = startAlertServer();
        DlqAlertNotifier notifier = notifier(props("http://127.0.0.1:" + port + "/alert", 10));

        notifier.dispatch(entry("store_failed", 1));

        String body = receivedBodies.get(0);
        assertThat(body).doesNotContain("patient_mpi").doesNotContain("patient_name");
    }

    @Test
    @DisplayName("告警网关不可达 → 降级为日志告警，不抛异常（告警失败不该把死信流程带崩）")
    void unreachableWebhookDegradesGracefully() {
        // 指向一个没人监听的端口
        DlqAlertNotifier notifier = notifier(props("http://127.0.0.1:1/alert", 10));

        assertThatCode(() -> notifier.dispatch(entry("connection_refused", 3)))
                .doesNotThrowAnyException();
        assertThat(receivedBodies).isEmpty();
    }

    @Test
    @DisplayName("告警网关返回 500 → 记为 FAILED 并带回状态码，不谎称已送达")
    void non2xxResponseIsReportedAsFailure() throws Exception {
        int port = startAlertServer();
        responseStatus.set(500);
        DlqAlertNotifier notifier = notifier(props("http://127.0.0.1:" + port + "/alert", 10));

        DlqAlertResult result = notifier.dispatch(entry("connection_refused", 3));

        assertThat(result.status()).isEqualTo(DlqAlertResult.Status.FAILED);
        assertThat(result.httpStatus()).isEqualTo(500);
        assertThat(result.delivered()).isFalse();
    }

    @Test
    @DisplayName("同一失败码在冷却窗口内 → 只推第一条告警，其余的只落库（防告警风暴）")
    void secondAlertOfSameCodeIsSuppressedWithinCooldown() throws Exception {
        int port = startAlertServer();
        DlqAlertNotifier notifier = notifier(props("http://127.0.0.1:" + port + "/alert", 10));

        DlqAlertResult first = notifier.dispatch(entry("connection_refused", 3));
        DlqAlertResult second = notifier.dispatch(entry("connection_refused", 3));
        DlqAlertResult third = notifier.dispatch(entry("connection_refused", 3));

        assertThat(first.status()).isEqualTo(DlqAlertResult.Status.SENT);
        assertThat(second.status()).isEqualTo(DlqAlertResult.Status.SUPPRESSED_BY_COOLDOWN);
        assertThat(third.status()).isEqualTo(DlqAlertResult.Status.SUPPRESSED_BY_COOLDOWN);
        assertThat(receivedBodies).hasSize(1);
    }

    @Test
    @DisplayName("冷却窗口内不同失败码 → 各自都推（不同的问题要分开报）")
    void differentFailureCodesAreNotSuppressed() throws Exception {
        int port = startAlertServer();
        DlqAlertNotifier notifier = notifier(props("http://127.0.0.1:" + port + "/alert", 10));

        notifier.dispatch(entry("connection_refused", 3));
        notifier.dispatch(entry("tls_mismatch", 0));

        assertThat(receivedBodies).hasSize(2);
    }

    @Test
    @DisplayName("冷却窗口过后 → 再次推告警（同码的新问题不能被永久静默）")
    void alertIsSentAgainAfterCooldownExpires() throws Exception {
        int port = startAlertServer();
        DlqAlertNotifier notifier = notifier(props("http://127.0.0.1:" + port + "/alert", 10));

        notifier.dispatch(entry("connection_refused", 3));
        clock.set(now.plus(Duration.ofMinutes(11)));
        DlqAlertResult later = notifier.dispatch(entry("connection_refused", 3));

        assertThat(later.status()).isEqualTo(DlqAlertResult.Status.SENT);
        assertThat(receivedBodies).hasSize(2);
    }

    @Test
    @DisplayName("跨实例冷却：库里已有最近成功告警过的同码行 → 本实例也抑制（重启后仍生效）")
    void cooldownAlsoHonoursDatabaseState() throws Exception {
        int port = startAlertServer();
        when(repository.countAlertedSince(anyString(), any(Instant.class))).thenReturn(1L);
        DlqAlertNotifier notifier = notifier(props("http://127.0.0.1:" + port + "/alert", 10));

        DlqAlertResult result = notifier.dispatch(entry("connection_refused", 3));

        assertThat(result.status()).isEqualTo(DlqAlertResult.Status.SUPPRESSED_BY_COOLDOWN);
        assertThat(receivedBodies).isEmpty();
    }

    @Test
    @DisplayName("冷却判定查库失败 → 放行告警（宁可多发一条，也不静默吞掉告警）")
    void cooldownLookupFailureFailsOpen() throws Exception {
        int port = startAlertServer();
        when(repository.countAlertedSince(anyString(), any(Instant.class)))
                .thenThrow(new IllegalStateException("库炸了"));
        DlqAlertNotifier notifier = notifier(props("http://127.0.0.1:" + port + "/alert", 10));

        DlqAlertResult result = notifier.dispatch(entry("connection_refused", 3));

        assertThat(result.status()).isEqualTo(DlqAlertResult.Status.SENT);
        assertThat(receivedBodies).hasSize(1);
    }

    @Test
    @DisplayName("冷却窗口配 0 → 不做抑制（每条都推）")
    void zeroCooldownDisablesSuppression() throws Exception {
        int port = startAlertServer();
        DlqAlertNotifier notifier = notifier(props("http://127.0.0.1:" + port + "/alert", 0));

        notifier.dispatch(entry("connection_refused", 3));
        notifier.dispatch(entry("connection_refused", 3));

        assertThat(receivedBodies).hasSize(2);
    }
}
