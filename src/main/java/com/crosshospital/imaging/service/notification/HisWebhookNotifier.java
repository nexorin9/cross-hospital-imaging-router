package com.crosshospital.imaging.service.notification;

import com.crosshospital.imaging.config.NotificationConfigProps;
import com.crosshospital.imaging.domain.DlqNotification;
import com.crosshospital.imaging.repository.DlqNotificationRepository;
import com.crosshospital.imaging.service.task.NotificationPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HIS 工单完成回调（task 32）。
 *
 * <p><b>它记的是「成功之后的失败」</b>：影像已写入本院 PACS、任务已到 notified，
 * 回调 HIS 三次都没送达时，任务<b>不回退</b>，而是落一行
 * {@code dlq_notifications} 待办 + 可选告警。把回调失败算成调阅失败，
 * 会让信息科去查外院网络，而真正该做的是补推一次通知。
 *
 * <p><b>失败分类</b>：连接超时 → {@code webhook_timeout}；连不上 →
 * {@code webhook_unreachable}；非 2xx → {@code webhook_failed}；
 * 未配置端点 → {@code webhook_unreachable} 且不重试（重发同一个空地址没有意义）。
 *
 * <p><b>不落完整 URL</b>：HIS 网关地址里常带 token，死信表只记 host 摘要。
 *
 * <p><b>绝不抛异常给主路径</b>：投递、落死信、告警三段的异常都在这里收住，
 * 最坏情况是「通知没到、但待办与日志都在」。
 */
@Service
@Profile("!test")
public class HisWebhookNotifier implements NotificationPort {

    private static final Logger LOG = LoggerFactory.getLogger(HisWebhookNotifier.class);

    private static final String CODE_FAILED = "webhook_failed";
    private static final String CODE_TIMEOUT = "webhook_timeout";
    private static final String CODE_UNREACHABLE = "webhook_unreachable";

    private final NotificationConfigProps props;
    private final DlqNotificationRepository deadLetters;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public HisWebhookNotifier(NotificationConfigProps props,
                              DlqNotificationRepository deadLetters,
                              ObjectMapper mapper) {
        this.props = props;
        this.deadLetters = deadLetters;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, props.getHisWebhookTimeoutMs())))
                .build();
    }

    /** 主路径插头：notified 后调用，结果只记日志（不回退状态）。 */
    @Override
    public void onNotified(UUID taskId, String studyUid, String status) {
        NotificationResult result = send(new NotificationRequest(
                taskId, studyUid, props.getTargetPacsAet(), status));
        if (!result.delivered()) {
            LOG.warn("HIS 通知未送达（已落死信，任务状态保持 notified）：taskId={} attempts={} code={}",
                    taskId, result.attempts(), result.failureCode());
        }
    }

    /**
     * 投递一次 HIS 工单回调（最多 3 次，指数线性退避）。
     *
     * @return 投递结果；失败时已落死信 + 告警
     */
    public NotificationResult send(NotificationRequest notification) {
        String url = props.getHisWebhookUrl();
        if (url == null || url.isBlank()) {
            NotificationResult result = NotificationResult.failed(0, CODE_UNREACHABLE,
                    "未配置 HIS_WEBHOOK_URL，无法回推 HIS 工单");
            deadLetter(notification, result, url);
            return result;
        }
        int maxAttempts = props.isRetryOnFailure()
                ? Math.max(1, props.getMaxRetries()) : 1;
        String payload = toJson(notification);
        String lastCode = CODE_UNREACHABLE;
        String lastMessage = "";

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMillis(Math.max(1, props.getHisWebhookTimeoutMs())))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return NotificationResult.ok(attempt);
                }
                lastCode = CODE_FAILED;
                lastMessage = "HIS 回调返回 HTTP " + response.statusCode();
            } catch (HttpTimeoutException e) {
                lastCode = CODE_TIMEOUT;
                lastMessage = "HIS 回调超时：" + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastCode = CODE_UNREACHABLE;
                lastMessage = "HIS 回调被中断";
            } catch (Exception e) {
                lastCode = CODE_UNREACHABLE;
                lastMessage = "HIS 回调连不上：" + e.getMessage();
            }
            if (attempt < maxAttempts && props.getBackoffMs() > 0) {
                sleep(props.getBackoffMs() * attempt);
            }
        }
        NotificationResult result = NotificationResult.failed(maxAttempts, lastCode, lastMessage);
        deadLetter(notification, result, url);
        return result;
    }

    /** 落死信 + 告警；两段的异常都吞掉，不把主路径带崩。 */
    private void deadLetter(NotificationRequest notification, NotificationResult result, String url) {
        boolean alerted = alert(notification, result);
        try {
            DlqNotification row = new DlqNotification();
            row.setTaskId(notification.taskId());
            row.setStudyUid(notification.studyUid());
            row.setTargetPacsAet(notification.targetPacsAet());
            row.setWebhookHost(hostOf(url));
            row.setFailureCode(result.failureCode());
            row.setFailureMessage(result.message());
            row.setRetryAttempts(result.attempts());
            row.setAlertWebhookSent(alerted);
            DlqNotification saved = deadLetters.save(row);
            LOG.warn("HIS 通知死信已落库：id={} taskId={} code={} attempts={} alertSent={}",
                    saved == null ? null : saved.getId(), notification.taskId(),
                    result.failureCode(), result.attempts(), alerted);
        } catch (RuntimeException ex) {
            LOG.error("HIS 通知死信落库失败（原始失败原因保留在日志）：taskId={} code={} msg={}",
                    notification.taskId(), result.failureCode(), ex.getMessage());
        }
    }

    /** 告警外发：配置了才发；失败只记日志。 */
    private boolean alert(NotificationRequest notification, NotificationResult result) {
        String url = props.getAlertWebhookUrl();
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("event", "his_notification_dead_letter");
            body.put("taskId", notification.taskId() == null ? null : notification.taskId().toString());
            body.put("failureCode", result.failureCode());
            body.put("message", result.message());
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1, props.getHisWebhookTimeoutMs())))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            LOG.warn("HIS 通知告警外发失败（不影响死信落库）：{}", e.getMessage());
            return false;
        }
    }

    private String toJson(NotificationRequest notification) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", notification.taskId() == null ? null : notification.taskId().toString());
        body.put("studyUid", notification.studyUid());
        body.put("targetPacsAet", notification.targetPacsAet());
        body.put("status", notification.status());
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("HIS 通知消息体序列化失败", e);
        }
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return "unconfigured";
        }
        try {
            String host = URI.create(url).getHost();
            return host == null ? "invalid-url" : host;
        } catch (IllegalArgumentException e) {
            return "invalid-url";
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
