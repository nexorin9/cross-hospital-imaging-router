package com.crosshospital.imaging.service.task;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.crosshospital.imaging.config.DlqConfigProps;
import com.crosshospital.imaging.domain.DlqEntry;
import com.crosshospital.imaging.repository.DlqRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 死信告警外发器：把一条死信 POST 给告警网关（钉钉 / 企业微信 / 院内自建均可）。
 *
 * <p><b>为什么要告警</b>：死信是「重试也没用、只能人管」的那一类失败。如果只躺在表里，
 * 信息科要靠每天翻列表才知道有个外院断了 —— 而断外院这件事，越早知道越少影像卡住。
 *
 * <p><b>为什么带冷却窗口</b>：一次外院断网会让几十个 study 同时失败、落几十条同码死信。
 * 不做抑制的话，值班手机会在两分钟内收到几十条一模一样的告警 —— 真出事的时候
 * 那条告警就淹没在噪音里了。做法是：<b>同一失败码</b>在冷却窗口（默认 10 分钟）内
 * 只发第一条，后续的只落库不推告警。抑制状态两处都查：
 * <ul>
 *   <li>进程内 {@code lastDispatchedAtByCode} —— 同实例内零竞态（第一条刚落库就能挡住后一条）；</li>
 *   <li>库里「最近已成功告警过的同码行」 —— 跨实例 / 重启后仍然生效。</li>
 * </ul>
 *
 * <p><b>为什么同步 POST 而不是异步</b>：告警送达标记（{@code alert_webhook_sent}）要写回
 * 同一行死信，若异步写回就会与入队事务产生可见性竞态（更新可能先于插入提交而落空）。
 * 同步投递让「落库 → 推送 → 标记」在一处完成，标记一定跟着入库事务一起提交。
 * 代价是最多 3 秒（{@code dlq.alert-timeout-ms}）的阻塞 —— 这条路本来就是失败路径，
 * 前面已经烧掉了一个 DICOM 超时，3 秒不改变量级。
 *
 * <p><b>失败不抛异常</b>：告警网关挂了只降级为日志告警，绝不能反过来把
 * 「调阅已经失败」这件事的排障链路带崩。
 *
 * <p>源主路径证据：通用分布式系统 DLQ 模式（重试耗尽 → 死信 → 告警 → 人工处置 → TTL 清理）。
 */
@Service
@Profile("!test")
public class DlqAlertNotifier implements DlqAlertPort {

    private static final Logger LOG = LoggerFactory.getLogger(DlqAlertNotifier.class);

    /** 告警报文里 failure_message 的最大长度 —— 告警是给人看的，不是完整日志。 */
    public static final int MESSAGE_MAX_CHARS = 500;

    private final DlqRepository repository;
    private final DlqConfigProps props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Clock clock;

    /** 同实例内的冷却状态：失败码 → 最近一次成功告警的时间。 */
    private final Map<String, Instant> lastDispatchedAtByCode = new ConcurrentHashMap<>();

    @Autowired
    public DlqAlertNotifier(DlqRepository repository,
                            DlqConfigProps props,
                            ObjectMapper objectMapper) {
        this(repository, props, objectMapper, defaultHttpClient(props), Clock.systemUTC());
    }

    /** 显式构造入口（测试用：自备 HTTP 客户端）。 */
    public DlqAlertNotifier(DlqRepository repository,
                            DlqConfigProps props,
                            ObjectMapper objectMapper,
                            HttpClient httpClient) {
        this(repository, props, objectMapper, httpClient, Clock.systemUTC());
    }

    /** 显式构造入口（测试用：自备 HTTP 客户端与时钟 —— 冷却窗口的到期判定需要可控时间）。 */
    public DlqAlertNotifier(DlqRepository repository,
                            DlqConfigProps props,
                            ObjectMapper objectMapper,
                            HttpClient httpClient,
                            Clock clock) {
        if (repository == null || props == null || objectMapper == null
                || httpClient == null || clock == null) {
            throw new IllegalArgumentException("DlqAlertNotifier 依赖不能为 null");
        }
        this.repository = repository;
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    private static HttpClient defaultHttpClient(DlqConfigProps props) {
        int timeoutMs = props == null ? 3000 : props.getAlertTimeoutMs();
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, timeoutMs)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    // ------------------------------------------------------------------ 对外入口

    /**
     * 推送一条死信告警。<b>本方法不抛异常。</b>
     */
    @Override
    public DlqAlertResult dispatch(DlqEntry entry) {
        if (entry == null) {
            return DlqAlertResult.failed("死信行为空，未推送告警", null);
        }
        String url = props.resolveAlertWebhookUrl();
        if (url == null) {
            LOG.warn("未配置 dlq.alert-webhook-url，死信只落库不推告警：task={} code={}",
                    entry.getTaskId(), entry.getFailureCode());
            return DlqAlertResult.notConfigured("未配置告警地址，死信只落库");
        }

        String failureCode = entry.getFailureCode() == null ? "unknown" : entry.getFailureCode();
        Instant now = clock.instant();
        if (isCoolingDown(failureCode, now)) {
            LOG.info("命中告警冷却窗口（{} 分钟），本次不推告警：task={} code={}",
                    props.getAlertCooldownMinutes(), entry.getTaskId(), failureCode);
            return DlqAlertResult.suppressed(
                    "命中告警冷却窗口（" + props.getAlertCooldownMinutes() + " 分钟）");
        }

        String payload = buildPayload(entry);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1, props.getAlertTimeoutMs())))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                lastDispatchedAtByCode.put(failureCode, now);
                LOG.info("死信告警已送达：code={} task={} status={}",
                        failureCode, entry.getTaskId(), status);
                return DlqAlertResult.sent(status);
            }
            LOG.error("死信告警返回非 2xx：code={} task={} status={}",
                    failureCode, entry.getTaskId(), status);
            return DlqAlertResult.failed("告警网关返回 " + status, status);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.error("死信告警投递被中断：code={} task={}", failureCode, entry.getTaskId());
            return DlqAlertResult.failed("告警投递被中断", null);
        } catch (IOException ex) {
            // 连接拒绝 / 超时 / TLS 握手失败 —— 一律降级为日志告警，不向上抛
            LOG.error("死信告警投递失败（降级为日志告警）：code={} task={} 原因={}",
                    failureCode, entry.getTaskId(), ex.getMessage());
            return DlqAlertResult.failed("告警投递失败：" + ex.getMessage(), null);
        } catch (RuntimeException ex) {
            // URI 非法 / 连接拒绝 / 超时 —— 一律降级为日志告警，不向上抛
            LOG.error("死信告警投递失败（降级为日志告警）：code={} task={} 原因={}",
                    failureCode, entry.getTaskId(), ex.getMessage());
            return DlqAlertResult.failed("告警投递失败：" + ex.getMessage(), null);
        }
    }

    // ------------------------------------------------------------------ 冷却窗口

    /**
     * 是否处于告警冷却窗口内。
     *
     * <p>两处都查：进程内状态（零竞态）+ 库里最近一次成功告警（跨实例 / 重启后仍生效）。
     */
    boolean isCoolingDown(String failureCode, Instant now) {
        int cooldownMinutes = props.getAlertCooldownMinutes();
        if (cooldownMinutes <= 0) {
            return false;
        }
        Instant last = lastDispatchedAtByCode.get(failureCode);
        if (last != null && last.plus(Duration.ofMinutes(cooldownMinutes)).isAfter(now)) {
            return true;
        }
        try {
            Instant threshold = now.minus(Duration.ofMinutes(cooldownMinutes));
            return repository.countAlertedSince(failureCode, threshold) > 0;
        } catch (RuntimeException ex) {
            // 冷却判定查库失败 → 放行（宁可多发一条，也别把告警静默吞掉）
            LOG.warn("告警冷却判定查库失败，本次放行告警: {}", ex.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ 报文组装

    /**
     * 组装告警报文：任务号 / 失败码 / 失败消息 / 重试次数，外加定位用的时间戳。
     *
     * <p>不含病人身份字段 —— 告警是发给值班群的，不是病历。
     */
    String buildPayload(DlqEntry entry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dlq_id", entry.getId());
        body.put("task_id", entry.getTaskId() == null ? null : entry.getTaskId().toString());
        body.put("task_context_present", DlqService.hasTaskContext(entry.getTaskId()));
        body.put("failure_code", entry.getFailureCode());
        body.put("failure_message", truncate(entry.getFailureMessage()));
        body.put("retry_attempts", entry.getRetryAttempts());
        body.put("first_failed_at", String.valueOf(entry.getFirstFailedAt()));
        body.put("last_retry_at", String.valueOf(entry.getLastRetryAt()));
        body.put("source", "cross-hospital-imaging-router");
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            LOG.error("死信告警报文序列化失败，退化为最小报文：{}", ex.getMessage());
            return "{\"failure_code\":\"" + entry.getFailureCode() + "\"}";
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MESSAGE_MAX_CHARS
                ? message
                : message.substring(0, MESSAGE_MAX_CHARS) + "…(截断)";
    }

    /** 报文编码（显式 UTF-8，院内钉钉/企业微信均按 UTF-8 收）。 */
    static Charset charset() {
        return StandardCharsets.UTF_8;
    }
}
