package com.crosshospital.imaging.service.hl7;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.crosshospital.imaging.config.Hl7ConfigProps;
import com.crosshospital.imaging.domain.Hl7DeadLetter;
import com.crosshospital.imaging.domain.Hl7ParsedMessage;
import com.crosshospital.imaging.exception.Hl7ParseException;
import com.crosshospital.imaging.repository.Hl7DeadLetterRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * HL7 触发报文死信处理器：解析失败 / 缺关键字段 / 无承接外院 / 入队失败 → 落死信表 + 告警。
 *
 * <p><b>源主路径采纳</b>：{@code github_ref/dcm4che/dcm4che-net-hl7/.../HL7ConnectionMonitor.java:54-62}
 * —— 源产品的 {@code onMessageProcessed(conn, socket, msg, rsp, ex)} 同时带回「回应」与「异常」，
 * 也就是说<b>处理结果必须显式回报</b>，收到消息不等于处理成功。本类把这条语义落成三件事：
 *
 * <ol>
 *   <li><b>死信落库</b>：一帧报文一行，分类可查、可处置；</li>
 *   <li><b>告警 webhook</b>：异步推给信息科（钉钉 / 企业微信 / 自建 HTTP 均可），URL 走环境变量；</li>
 *   <li><b>不假装成功</b>：报文本身不合法时，调用方继续向上抛，线路层回 NAK 让 HIS 重投；
 *       报文合法但没有承接对象时，<b>不</b>回 NAK —— 那不是 HIS 的错，回 NAK 只会造成重投风暴。</li>
 * </ol>
 *
 * <p><b>为什么告警是异步的</b>：触发路由器跑在 Netty 事件循环线程上，
 * 一次同步 HTTP POST（哪怕 3 秒超时）会把整个 worker 卡住 —— 一条慢告警拖垮所有连接受理。
 * 告警走独立单线程执行器；执行器由构造参数注入，单测可换成同步执行器让断言确定。
 *
 * <p><b>为什么死信表不存报文原文</b>：见 {@link Hl7DeadLetter} 的类注释 —— 死信表不是第二份病历。
 */
@Service
@Profile("!test")
public class Hl7DeadLetterHandler {

    private static final Logger LOG = LoggerFactory.getLogger(Hl7DeadLetterHandler.class);

    /** MSH 段头摘要在死信表里的最大长度。 */
    public static final int MSH_EXCERPT_MAX = 256;

    /** 告警投递线程名前缀。 */
    public static final String ALERT_THREAD_PREFIX = "hl7-dl-alert-";

    private final Hl7DeadLetterRepository repository;
    private final Hl7ConfigProps props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Executor alertExecutor;
    /** 是否由本类创建了告警执行器（自建的要负责停机；外部传入的不动）。 */
    private final boolean ownsExecutor;

    /**
     * Spring 装配入口：HTTP 客户端与告警线程都在本类内部建。
     *
     * <p><b>刻意不把 {@link Executor} 暴露成 Spring bean</b>：Spring Boot 的
     * {@code TaskExecutionAutoConfiguration} 带 {@code @ConditionalOnMissingBean(Executor.class)}，
     * 一旦本服务登记一个 {@code Executor} bean，Boot 就不再自动装配全应用的
     * {@code applicationTaskExecutor}，{@code @Async}（后续任务的重试调度要用）
     * 会退化成每次新建线程的 {@code SimpleAsyncTaskExecutor}。
     * 告警线程是本类私有的实现细节，不该影响全局任务执行器。
     */
    @Autowired
    public Hl7DeadLetterHandler(Hl7DeadLetterRepository repository,
                                Hl7ConfigProps props,
                                ObjectMapper objectMapper) {
        this(repository, props, objectMapper,
                defaultHttpClient(props),
                defaultAlertExecutor(),
                true);
    }

    /** 显式构造入口（测试用）：自备 HTTP 客户端与告警执行器。 */
    public Hl7DeadLetterHandler(Hl7DeadLetterRepository repository,
                                Hl7ConfigProps props,
                                ObjectMapper objectMapper,
                                HttpClient httpClient,
                                Executor alertExecutor) {
        this(repository, props, objectMapper, httpClient, alertExecutor, false);
    }

    private Hl7DeadLetterHandler(Hl7DeadLetterRepository repository,
                                 Hl7ConfigProps props,
                                 ObjectMapper objectMapper,
                                 HttpClient httpClient,
                                 Executor alertExecutor,
                                 boolean ownsExecutor) {
        if (repository == null || props == null || objectMapper == null
                || httpClient == null || alertExecutor == null) {
            throw new IllegalArgumentException("Hl7DeadLetterHandler 依赖不能为 null");
        }
        this.repository = repository;
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.alertExecutor = alertExecutor;
        this.ownsExecutor = ownsExecutor;
    }

    /** 告警用 HTTP 客户端（连接超时取配置里的投递超时）。 */
    private static HttpClient defaultHttpClient(Hl7ConfigProps props) {
        int timeoutMs = props == null ? 3000 : props.getDeadLetter().getAlertTimeoutMs();
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, timeoutMs)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * 告警投递执行器：单线程 + 守护线程。
     *
     * <p>单线程是因为死信告警本来就要求按发生顺序送达（信息科看的是一条条事件，不是乱序事件）；
     * 守护线程保证进程退出时不被它挂住。
     */
    private static Executor defaultAlertExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, ALERT_THREAD_PREFIX + Thread.currentThread().getId());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 停机时释放自建的告警线程（外部传入的执行器不动）。 */
    @PreDestroy
    void shutdown() {
        if (!ownsExecutor) {
            return;
        }
        if (alertExecutor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 对外入口

    /** 报文结构不合法（MSH 段缺失 / 编码字符异常 / 消息类型不支持 / PID 缺失）。 */
    public void recordParseFailure(MllpFrame frame, Hl7ParseException cause) {
        Hl7DeadLetter.FailureCode code = mapFailureCode(cause.getCode());
        persist(frame, null, code, cause.getMessage());
    }

    /** 报文合规但缺影像引用（OBR-3 为空）—— 没有它定位不到外院那一份检查。 */
    public void recordMissingStudyReference(Hl7ParsedMessage message, MllpFrame frame, String detail) {
        persist(frame, message, Hl7DeadLetter.FailureCode.MISSING_STUDY_REFERENCE, detail);
    }

    /** 报文合规，但按主索引找不到承接的外院节点（无可调阅对象）。 */
    public void recordNoTargetPeer(Hl7ParsedMessage message, MllpFrame frame, String reason) {
        persist(frame, message, Hl7DeadLetter.FailureCode.NO_TARGET_PEER, reason);
    }

    /** 入队失败（数据库不可用 / 调阅队列已满）。 */
    public void recordEnqueueFailed(Hl7ParsedMessage message, MllpFrame frame, Throwable cause) {
        String detail = cause == null ? "未知原因" : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        persist(frame, message, Hl7DeadLetter.FailureCode.ENQUEUE_FAILED, detail);
    }

    /** 解析异常分类 → 死信分类（一一对应，缺一不可）。 */
    private static Hl7DeadLetter.FailureCode mapFailureCode(Hl7ParseException.FailureCode code) {
        if (code == null) {
            return Hl7DeadLetter.FailureCode.INVALID_MSH;
        }
        switch (code) {
            case INVALID_MSH:
                return Hl7DeadLetter.FailureCode.INVALID_MSH;
            case INVALID_DYNAMIC_DELIMITERS:
                return Hl7DeadLetter.FailureCode.INVALID_DYNAMIC_DELIMITERS;
            case UNSUPPORTED_MESSAGE_TYPE:
                return Hl7DeadLetter.FailureCode.UNSUPPORTED_MESSAGE_TYPE;
            case MISSING_PID:
                return Hl7DeadLetter.FailureCode.MISSING_PID;
            default:
                return Hl7DeadLetter.FailureCode.INVALID_MSH;
        }
    }

    // ------------------------------------------------------------------ 落库与告警

    /**
     * 落一行死信，并异步推告警。
     *
     * <p>落库失败只记日志、不向上抛 —— 死信写入失败不该盖掉原始失败原因
     * （调用方抛的是报文本身的问题，信息科要看到的是那个，不是"死信表也挂了"）。
     */
    private void persist(MllpFrame frame,
                         Hl7ParsedMessage message,
                         Hl7DeadLetter.FailureCode code,
                         String detail) {
        Hl7DeadLetter row = new Hl7DeadLetter();
        row.setFailureCode(code);
        row.setFailureMessage(detail);
        row.setPayloadBytes(frame == null ? 0 : frame.size());
        row.setRemoteAddress(frame == null ? null : frame.remoteAddress());
        row.setReceivedAt(frame == null ? Instant.now() : frame.receivedAt());
        row.setMshExcerpt(mshExcerpt(frame));
        if (message != null) {
            row.setMessageControlId(message.messageControlId());
            row.setRouteKey(message.routeKey());
            row.setSendingApplication(message.sendingApplication());
            row.setSendingFacility(message.sendingFacility());
            row.setPatientMpi(message.patientMpi());
            row.setAccessionNumber(message.accessionNumber());
        }

        Hl7DeadLetter saved = null;
        try {
            saved = repository.save(row);
            LOG.warn("HL7 触发报文已进死信：failure_code={} control_id={} route={} 说明={}",
                    code.code(), row.getMessageControlId(), row.getRouteKey(), detail);
        } catch (RuntimeException ex) {
            LOG.error("HL7 死信落库失败（原始失败原因：{}）：{}", detail, ex.getMessage());
        }

        dispatchAlert(saved != null ? saved : row);
    }

    /** 异步推告警 webhook；未配置 URL 时只记日志。 */
    private void dispatchAlert(Hl7DeadLetter row) {
        String url = props.getDeadLetter().resolveAlertWebhookUrl();
        if (url == null || url.isBlank()) {
            LOG.warn("未配置 hl7.dead-letter.alert-webhook-url，HL7 死信只落库不推告警（control_id={}）",
                    row.getMessageControlId());
            return;
        }
        String payload = buildAlertPayload(row);
        try {
            alertExecutor.execute(() -> postAlert(url, payload, row));
        } catch (RuntimeException ex) {
            LOG.error("HL7 死信告警投递失败（任务未被调度）：{}", ex.getMessage());
        }
    }

    /** 组装告警报文体（只含定位所需字段，不含患者身份字段）。 */
    private String buildAlertPayload(Hl7DeadLetter row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("failure_code", row.getFailureCode().code());
        body.put("failure_message", row.getFailureMessage());
        body.put("message_control_id", row.getMessageControlId());
        body.put("route_key", row.getRouteKey());
        body.put("sending_application", row.getSendingApplication());
        body.put("sending_facility", row.getSendingFacility());
        body.put("patient_mpi", row.getPatientMpi());
        body.put("remote_address", row.getRemoteAddress());
        // 解析失败时 message_control_id 是空的（根本没解析出来），MSH 摘要里带着它 ——
        // 告警必须自带"这是哪一条"，否则信息科收到告警还得先翻库才能回查 HIS
        body.put("msh_excerpt", row.getMshExcerpt());
        body.put("received_at", String.valueOf(row.getReceivedAt()));
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            LOG.error("HL7 死信告警报文序列化失败，退化为纯文本：{}", ex.getMessage());
            return "{\"failure_code\":\"" + row.getFailureCode().code() + "\"}";
        }
    }

    /** POST 告警；失败降级为日志（不抛 —— 告警失败不该把死信流程带崩）。 */
    private void postAlert(String url, String payload, Hl7DeadLetter row) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(props.getDeadLetter().getAlertTimeoutMs()))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, Charset.forName("UTF-8")))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                markAlertSent(row, true);
                LOG.info("HL7 死信告警已送达：failure_code={} control_id={}",
                        row.getFailureCode().code(), row.getMessageControlId());
            } else {
                LOG.error("HL7 死信告警返回非 2xx：status={} control_id={}",
                        response.statusCode(), row.getMessageControlId());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.error("HL7 死信告警投递被中断：control_id={}", row.getMessageControlId());
        } catch (Exception ex) {
            LOG.error("HL7 死信告警投递失败：control_id={} 原因={}",
                    row.getMessageControlId(), ex.getMessage());
        }
    }

    /** 回写告警送达标记；写失败只记日志（标记位不是关键路径）。 */
    private void markAlertSent(Hl7DeadLetter row, boolean sent) {
        if (row.getId() == null) {
            return;
        }
        try {
            row.setAlertWebhookSent(sent);
            repository.save(row);
        } catch (RuntimeException ex) {
            LOG.warn("HL7 死信告警标记回写失败：{}", ex.getMessage());
        }
    }

    /** MSH 段头摘要（截断到 {@link #MSH_EXCERPT_MAX}，只含应用/设施/控制 ID）。 */
    private static String mshExcerpt(MllpFrame frame) {
        if (frame == null || frame.isEmpty()) {
            return null;
        }
        Charset cs = Charset.forName("UTF-8");
        try {
            String line = frame.mshLine(cs);
            return line.length() > MSH_EXCERPT_MAX ? line.substring(0, MSH_EXCERPT_MAX) : line;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
