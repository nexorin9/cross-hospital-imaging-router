package com.crosshospital.imaging.service.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.crosshospital.imaging.config.Hl7ConfigProps;
import com.crosshospital.imaging.domain.Hl7DeadLetter;
import com.crosshospital.imaging.exception.Hl7ParseException;
import com.crosshospital.imaging.repository.Hl7DeadLetterRepository;
import com.crosshospital.imaging.support.OracleOrderMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 4 · Task 19：HL7 死信处理器（落库 + 告警 webhook + 降级）测试。
 *
 * <p>告警 webhook 用<b>真 HTTP</b>验证：起一个本机 {@link HttpServer} 当告警网关，
 * 让死信处理器用真 {@link HttpClient} 打过去，断言"真的发出了、报文里有什么"。
 * 这比 mock 客户端更能暴露"URL 拼错 / 报文序列化错 / 线程没跑到"这类问题。
 */
@DisplayName("Task 19 — HL7 死信落库与告警投递")
class Hl7DeadLetterHandlerTest {

    private final Hl7DeadLetterRepository repository = mock(Hl7DeadLetterRepository.class);

    private final List<Hl7DeadLetter> saved = new ArrayList<>();
    private HttpServer alertServer;
    private final AtomicReference<String> alertBody = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        saved.clear();
        alertBody.set(null);
        // 替身要像数据库：INSERT 分配自增 id；UPDATE 命中已存在行（不重复计入清单）
        when(repository.save(any(Hl7DeadLetter.class))).thenAnswer(inv -> {
            Hl7DeadLetter row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId((long) saved.size() + 1L);
                saved.add(row);
            }
            return row;
        });
    }

    @AfterEach
    void stopServer() {
        if (alertServer != null) {
            alertServer.stop(0);
            alertServer = null;
        }
    }

    /** 起一个本机告警网关，收到 POST 后把报文原文存起来。 */
    private int startAlertServer() throws IOException {
        alertServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        alertServer.createContext("/alert", exchange -> {
            alertBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        alertServer.start();
        return alertServer.getAddress().getPort();
    }

    private static Hl7ConfigProps props(String webhookUrl) {
        Hl7ConfigProps props = new Hl7ConfigProps();
        props.getTrigger().setCharset("UTF-8");
        props.getDeadLetter().setAlertWebhookUrl(webhookUrl);
        props.getDeadLetter().setAlertTimeoutMs(2000);
        return props;
    }

    private static MllpFrame frame(byte[] payload) {
        return new MllpFrame(payload, "127.0.0.1:51234", Instant.now(), 7L);
    }

    private Hl7DeadLetterHandler handler(String webhookUrl) {
        return new Hl7DeadLetterHandler(repository, props(webhookUrl), new ObjectMapper(),
                HttpClient.newHttpClient(), Runnable::run);
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("解析失败 → 落一行死信 + 真的 POST 告警网关（报文含分类与控制 ID，不含患者身份字段）")
    void parseFailureIsPersistedAndAlertedOverRealHttp() throws Exception {
        int port = startAlertServer();
        String webhook = "http://127.0.0.1:" + port + "/alert";
        Hl7DeadLetterHandler handler = handler(webhook);

        handler.recordParseFailure(frame(OracleOrderMessage.ormO01(StandardCharsets.UTF_8)),
                new Hl7ParseException(Hl7ParseException.FailureCode.MISSING_PID, "PID-3 缺失"));

        assertThat(saved).hasSize(1);
        Hl7DeadLetter row = saved.get(0);
        assertThat(row.getFailureCode()).isEqualTo(Hl7DeadLetter.FailureCode.MISSING_PID);
        assertThat(row.getPayloadBytes()).isPositive();
        assertThat(row.getAlertWebhookSent()).isTrue();

        assertThat(alertBody.get()).isNotNull();
        assertThat(alertBody.get()).contains("missing_pid").contains("MSG_EXAMPLE_0001");
        assertThat(alertBody.get()).doesNotContain("<EXAMPLE_PATIENT_NAME>");
        assertThat(alertBody.get()).doesNotContain("PAT_MPI_EXAMPLE");
    }

    @Test
    @DisplayName("告警网关不可达 → 死信照落库，不抛异常（告警失败不该把死信流程带崩）")
    void unreachableAlertWebhookDegradesGracefully() {
        // 指向一个没人监听的端口
        Hl7DeadLetterHandler handler = handler("http://127.0.0.1:1/alert");

        assertThatCode(() -> handler.recordParseFailure(
                frame("MSH|broken".getBytes(StandardCharsets.UTF_8)),
                new Hl7ParseException(Hl7ParseException.FailureCode.INVALID_MSH, "首个段不是 MSH")))
                .doesNotThrowAnyException();

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getAlertWebhookSent()).isFalse();
    }

    @Test
    @DisplayName("未配置告警 webhook → 只落库，不外发")
    void withoutWebhookUrlOnlyPersists() {
        Hl7DeadLetterHandler handler = handler("");

        handler.recordParseFailure(frame("MSH|broken".getBytes(StandardCharsets.UTF_8)),
                new Hl7ParseException(Hl7ParseException.FailureCode.INVALID_MSH, "首个段不是 MSH"));

        assertThat(saved).hasSize(1);
        assertThat(alertBody.get()).isNull();
    }

    @Test
    @DisplayName("死信表写失败 → 不抛异常（原始失败原因由调用方保留）")
    void deadLetterStoreFailureDoesNotThrow() {
        when(repository.save(any(Hl7DeadLetter.class)))
                .thenThrow(new IllegalStateException("死信表不可写"));
        Hl7DeadLetterHandler handler = handler("");

        assertThatCode(() -> handler.recordParseFailure(
                frame("MSH|broken".getBytes(StandardCharsets.UTF_8)),
                new Hl7ParseException(Hl7ParseException.FailureCode.INVALID_MSH, "首个段不是 MSH")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("死信行只留定位信息：控制 ID / 路由键 / 字节数 / MSH 摘要，不落报文原文")
    void deadLetterRowCarriesOnlyLocatingInformation() {
        Hl7DeadLetterHandler handler = handler("");

        handler.recordParseFailure(
                frame(("MSH|^~\\&|HIS_APP|HIS_FACILITY|IMAGER|LOCAL_HOSP|20261020093000||ORM^O01|"
                        + "MSG_EXAMPLE_0001|P|2.5.1\rPID|1||PAT_MPI_EXAMPLE^^^HIS_MPI^MR||"
                        + "<EXAMPLE_PATIENT_NAME>||19700101|M\r")
                        .getBytes(StandardCharsets.UTF_8)),
                new Hl7ParseException(Hl7ParseException.FailureCode.MISSING_PID, "PID-3 缺失"));

        assertThat(saved).hasSize(1);
        Hl7DeadLetter row = saved.get(0);
        assertThat(row.getMshExcerpt()).contains("MSG_EXAMPLE_0001");
        assertThat(row.getMshExcerpt()).doesNotContain("PAT_MPI_EXAMPLE");
        assertThat(row.getMshExcerpt()).doesNotContain("<EXAMPLE_PATIENT_NAME>");
        assertThat(row.getRemoteAddress()).isEqualTo("127.0.0.1:51234");
        assertThat(row.getPayloadBytes()).isPositive();
        assertThat(row.getReceivedAt()).isNotNull();
    }

    @Test
    @DisplayName("装配拒绝 null 依赖")
    void rejectsNullDependencies() {
        assertThatThrownBy(() -> new Hl7DeadLetterHandler(null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
