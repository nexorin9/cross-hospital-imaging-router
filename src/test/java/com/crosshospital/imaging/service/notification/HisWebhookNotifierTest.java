package com.crosshospital.imaging.service.notification;

import com.crosshospital.imaging.config.NotificationConfigProps;
import com.crosshospital.imaging.domain.DlqNotification;
import com.crosshospital.imaging.repository.DlqNotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 32 — HIS 通知 webhook：真实 HTTP 出站 + 三次重试 + 死信 + 告警。 */
@DisplayName("Task 32 — HIS 工单回调与死信")
class HisWebhookNotifierTest {

    private static final String HIS_PATH = "/his/workorder/callback";
    private static final String ALERT_PATH = "/ops/alert";

    private WireMockServer server;
    private NotificationConfigProps props;
    private DlqNotificationRepository deadLetters;
    private HisWebhookNotifier notifier;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        props = new NotificationConfigProps();
        props.setHisWebhookUrl("http://127.0.0.1:" + server.port() + HIS_PATH);
        props.setHisWebhookTimeoutMs(1000);
        props.setMaxRetries(3);
        props.setBackoffMs(0);
        props.setTargetPacsAet("LOCAL_PACS");
        deadLetters = mock(DlqNotificationRepository.class);
        when(deadLetters.save(any(DlqNotification.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        notifier = new HisWebhookNotifier(props, deadLetters, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private NotificationRequest request() {
        return new NotificationRequest(UUID.randomUUID(), "1.2.840.EXAMPLE", "LOCAL_PACS", "notified");
    }

    @Test
    @DisplayName("HTTP 200 → 一次送达，不落死信")
    void successDeliversOnce() {
        server.stubFor(post(urlPathEqualTo(HIS_PATH)).willReturn(aResponse().withStatus(200)));

        NotificationResult result = notifier.send(request());

        assertTrue(result.delivered());
        assertEquals(1, result.attempts());
        verify(deadLetters, never()).save(any(DlqNotification.class));
        server.verify(1, postRequestedFor(urlPathEqualTo(HIS_PATH)));
    }

    @Test
    @DisplayName("HTTP 503 → 重试 3 次后落死信 webhook_failed")
    void serverErrorRetriesThreeTimesThenDeadLetter() {
        server.stubFor(post(urlPathEqualTo(HIS_PATH)).willReturn(aResponse().withStatus(503)));
        NotificationRequest req = request();

        NotificationResult result = notifier.send(req);

        assertFalse(result.delivered());
        assertEquals(3, result.attempts());
        assertEquals("webhook_failed", result.failureCode());
        server.verify(3, postRequestedFor(urlPathEqualTo(HIS_PATH)));
        ArgumentCaptor<DlqNotification> captor = ArgumentCaptor.forClass(DlqNotification.class);
        verify(deadLetters).save(captor.capture());
        DlqNotification row = captor.getValue();
        assertEquals(req.taskId(), row.getTaskId());
        assertEquals(req.studyUid(), row.getStudyUid());
        assertEquals("webhook_failed", row.getFailureCode());
        assertEquals(3, row.getRetryAttempts());
        assertEquals("127.0.0.1", row.getWebhookHost(), "死信只记 host，不落完整 URL");
    }

    @Test
    @DisplayName("响应超时 → webhook_timeout")
    void timeoutIsClassified() {
        server.stubFor(post(urlPathEqualTo(HIS_PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(800)));
        props.setHisWebhookTimeoutMs(100);
        notifier = new HisWebhookNotifier(props, deadLetters, new ObjectMapper());

        NotificationResult result = notifier.send(request());

        assertEquals("webhook_timeout", result.failureCode());
        verify(deadLetters).save(any(DlqNotification.class));
    }

    @Test
    @DisplayName("端点连不上 → webhook_unreachable")
    void unreachableEndpointIsClassified() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        props.setHisWebhookUrl("http://127.0.0.1:" + closedPort + HIS_PATH);
        props.setMaxRetries(1);
        notifier = new HisWebhookNotifier(props, deadLetters, new ObjectMapper());

        NotificationResult result = notifier.send(request());

        assertEquals("webhook_unreachable", result.failureCode());
        verify(deadLetters).save(any(DlqNotification.class));
    }

    @Test
    @DisplayName("未配置 HIS_WEBHOOK_URL → 不重试、落死信（不假装通知成功）")
    void unconfiguredEndpointFallsToDeadLetter() {
        props.setHisWebhookUrl("");
        notifier = new HisWebhookNotifier(props, deadLetters, new ObjectMapper());

        NotificationResult result = notifier.send(request());

        assertEquals(0, result.attempts());
        assertEquals("webhook_unreachable", result.failureCode());
        ArgumentCaptor<DlqNotification> captor = ArgumentCaptor.forClass(DlqNotification.class);
        verify(deadLetters).save(captor.capture());
        assertEquals("unconfigured", captor.getValue().getWebhookHost());
    }

    @Test
    @DisplayName("告警网关可用 → 死信行标记 alertWebhookSent=true")
    void alertIsSentAndRecorded() {
        server.stubFor(post(urlPathEqualTo(HIS_PATH)).willReturn(aResponse().withStatus(500)));
        server.stubFor(post(urlPathEqualTo(ALERT_PATH)).willReturn(aResponse().withStatus(200)));
        props.setAlertWebhookUrl("http://127.0.0.1:" + server.port() + ALERT_PATH);
        props.setMaxRetries(1);
        notifier = new HisWebhookNotifier(props, deadLetters, new ObjectMapper());

        notifier.send(request());

        ArgumentCaptor<DlqNotification> captor = ArgumentCaptor.forClass(DlqNotification.class);
        verify(deadLetters).save(captor.capture());
        assertTrue(captor.getValue().getAlertWebhookSent());
        server.verify(1, postRequestedFor(urlPathEqualTo(ALERT_PATH)));
    }

    @Test
    @DisplayName("主路径插头 onNotified 不抛异常（通知失败也不回退任务）")
    void portNeverThrows() {
        server.stubFor(post(urlPathEqualTo(HIS_PATH)).willReturn(aResponse().withStatus(500)));
        props.setMaxRetries(1);

        notifier.onNotified(UUID.randomUUID(), "1.2.840.EXAMPLE", "notified");

        verify(deadLetters, times(1)).save(any(DlqNotification.class));
    }
}
