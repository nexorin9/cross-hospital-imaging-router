package com.crosshospital.imaging.service.model;

import com.crosshospital.imaging.config.ModelConfigProps;
import com.crosshospital.imaging.domain.AiCallAudit;
import com.crosshospital.imaging.exception.ModelUnreachableException;
import com.crosshospital.imaging.repository.AiCallAuditRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 27 验收：模型不可达时的规则兜底。
 *
 * <p><b>用真实出站调用</b>（WireMock 起真实 HTTP 端点 + 真实 OkHttp），
 * 不 mock 网关本身 —— 「网络断了」这件事必须真的发生在 socket 上才算数。
 *
 * <p><b>任务要求的三个不可达场景</b>：network unreachable / timeout / parse error，
 * 三者都必须落到<b>同一件事</b>上：产出规则模板结果、标 {@code source=rule_fallback}、
 * 往 {@code ai_call_audit} 落一行失败记录。
 */
class ModelGatewayWithFallbackTest {

    private static final String CHAT_PATH = "/v1/chat/completions";
    private static final String KIND = "report_normalize";

    /** 与模型路径一致的 schema（兜底产物必须能对上）。 */
    private static final JsonSchema REPORT_SCHEMA = JsonSchema.of("normalized_report", Map.of(
            "type", "object",
            "required", List.of("bodyPart", "findings", "impression", "studyItems"),
            "properties", Map.of(
                    "bodyPart", Map.of("type", "string"),
                    "findings", Map.of("type", "string"),
                    "impression", Map.of("type", "string"),
                    "studyItems", Map.of("type", "array", "items", Map.of("type", "string")))));

    private static final String OK_BODY = """
            {
              "id": "chatcmpl-ok-001",
              "model": "test-model",
              "choices": [
                {"index": 0, "message": {"role": "assistant",
                 "content": "{\\"bodyPart\\":\\"胸部\\",\\"findings\\":\\"双肺未见明显异常\\",\\"impression\\":\\"未见异常\\",\\"studyItems\\":[\\"CT 平扫\\"]}"},
                 "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150}
            }
            """;

    private WireMockServer server;
    private ModelConfigProps props;
    private AiCallAuditRepository auditRepository;
    private AiCallAuditWriter auditWriter;
    private ModelGatewayWithFallback fallback;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        props = new ModelConfigProps();
        props.setBaseUrl("http://127.0.0.1:" + server.port());
        props.setApiKey("test-api-key");
        props.setModel("test-model");
        props.setTimeoutMs(300);
        props.setMaxRetries(0);
        auditRepository = mock(AiCallAuditRepository.class);
        when(auditRepository.save(any(AiCallAudit.class))).thenAnswer(inv -> inv.getArgument(0));
        auditWriter = new AiCallAuditWriter(auditRepository);
        ObjectMapper mapper = new ObjectMapper();
        fallback = new ModelGatewayWithFallback(
                new ModelGateway(props, mapper),
                new RuleTemplateEngine(props, mapper),
                auditWriter, props, mapper);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private AiResult invoke(Map<String, Object> ruleInputs) {
        return fallback.invoke(KIND, "你是影像术语归一助手", "外院报告原文", REPORT_SCHEMA,
                UUID.randomUUID(), ruleInputs);
    }

    private List<AiCallAudit> capturedAudits() {
        ArgumentCaptor<AiCallAudit> captor = ArgumentCaptor.forClass(AiCallAudit.class);
        verify(auditRepository, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    // ─────────────── 正常路径 ───────────────

    @Test
    @DisplayName("模型可用时走模型：source=model，审计落 success 一行")
    void modelSuccessIsUsedDirectly() {
        server.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(OK_BODY)));

        AiResult result = invoke(Map.of(RuleTemplateEngine.INPUT_RAW_BODY_PART, "CHEST"));

        assertTrue(result.isModel());
        assertFalse(result.isRuleFallback());
        assertEquals(AiResult.SOURCE_MODEL, result.source());
        assertEquals("test-model", result.modelName());
        assertTrue(result.requiresHumanReview(), "AI 产出必须留人工复核位");
        assertTrue(result.payload().contains("胸部"));

        List<AiCallAudit> audits = capturedAudits();
        assertEquals(1, audits.size());
        assertEquals(AiCallAuditWriter.STATUS_SUCCESS, audits.get(0).getStatus());
        assertEquals(KIND, audits.get(0).getKind());
    }

    // ─────────────── 三个不可达场景 ───────────────

    @Test
    @DisplayName("① 网络不可达（连接被拒）→ 规则模板兜底 + source=rule_fallback + 审计落 unreachable")
    void networkUnreachableFallsBackToRuleTemplate() throws IOException {
        int closedPort = closedPort();
        props.setBaseUrl("http://127.0.0.1:" + closedPort);

        AiResult result = invoke(Map.of(
                RuleTemplateEngine.INPUT_RAW_BODY_PART, "CHEST",
                RuleTemplateEngine.INPUT_RAW_MODALITY, "CT",
                RuleTemplateEngine.INPUT_RAW_FINDINGS, "双肺纹理清晰"));

        assertTrue(result.isRuleFallback(), "网络不可达必须回退规则模板");
        assertEquals(AiResult.SOURCE_RULE_FALLBACK, result.source());
        assertNotNull(result.fallbackReason());
        assertTrue(result.payload().contains("胸部"));
        assertTrue(result.payload().contains("双肺纹理清晰"));

        List<AiCallAudit> audits = capturedAudits();
        assertEquals(1, audits.size());
        assertEquals(AiCallAuditWriter.STATUS_UNREACHABLE, audits.get(0).getStatus());
    }

    @Test
    @DisplayName("② 超时 → 规则模板兜底 + source=rule_fallback + 审计落 timeout")
    void timeoutFallsBackToRuleTemplate() {
        server.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withFixedDelay(2000)
                        .withHeader("Content-Type", "application/json")
                        .withBody(OK_BODY)));

        AiResult result = invoke(Map.of(RuleTemplateEngine.INPUT_RAW_BODY_PART, "CHEST"));

        assertTrue(result.isRuleFallback());
        assertEquals(AiResult.SOURCE_RULE_FALLBACK, result.source());

        List<AiCallAudit> audits = capturedAudits();
        assertEquals(1, audits.size());
        assertEquals(AiCallAuditWriter.STATUS_TIMEOUT, audits.get(0).getStatus());
    }

    @Test
    @DisplayName("③ 响应读不懂（不是 JSON）→ 规则模板兜底 + source=rule_fallback + 审计落 parse_error")
    void parseErrorFallsBackToRuleTemplate() {
        server.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("这不是 JSON，是模型服务返回的乱码")));

        AiResult result = invoke(Map.of(RuleTemplateEngine.INPUT_RAW_BODY_PART, "CHEST"));

        assertTrue(result.isRuleFallback());
        assertEquals(AiResult.SOURCE_RULE_FALLBACK, result.source());

        List<AiCallAudit> audits = capturedAudits();
        assertEquals(1, audits.size());
        assertEquals(AiCallAuditWriter.STATUS_PARSE_ERROR, audits.get(0).getStatus());
    }

    @Test
    @DisplayName("④ 输出被截断（finish_reason=length）→ 同样走兜底（半份结果不能当结果用）")
    void truncatedOutputFallsBackToRuleTemplate() {
        server.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"x","model":"test-model","choices":[{"index":0,
                                 "message":{"role":"assistant","content":"{\\"bodyPart\\":\\"胸部\\",\\"findings\\":\\"双肺"},
                                 "finish_reason":"length"}]}
                                """)));

        AiResult result = invoke(Map.of(RuleTemplateEngine.INPUT_RAW_BODY_PART, "CHEST"));

        assertTrue(result.isRuleFallback());
        List<AiCallAudit> audits = capturedAudits();
        assertEquals(AiCallAuditWriter.STATUS_PARSE_ERROR, audits.get(0).getStatus());
    }

    @Test
    @DisplayName("⑤ HTTP 5xx → 兜底 + 审计落 http_error")
    void serverErrorFallsBackToRuleTemplate() {
        server.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(503).withBody("model service down")));

        AiResult result = invoke(Map.of(RuleTemplateEngine.INPUT_RAW_BODY_PART, "CHEST"));

        assertTrue(result.isRuleFallback());
        List<AiCallAudit> audits = capturedAudits();
        assertEquals(AiCallAuditWriter.STATUS_HTTP_ERROR, audits.get(0).getStatus());
    }

    // ─────────────── 双失败 ───────────────

    @Test
    @DisplayName("兜底被关闭（model.fallback.enabled=false）→ 不产出、抛 ModelUnreachableException")
    void fallbackDisabledFailsLoudly() {
        props.getFallback().setEnabled(false);
        server.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(503).withBody("down")));

        assertThrows(ModelUnreachableException.class, () -> invoke(Map.of()));

        List<AiCallAudit> audits = capturedAudits();
        assertEquals(1, audits.size());
        assertEquals(AiCallAuditWriter.STATUS_HTTP_ERROR, audits.get(0).getStatus());
    }

    @Test
    @DisplayName("模型失败且没有该 kind 的模板 → 抛 ModelUnreachableException（不返回空载荷）")
    void missingRuleTemplateFailsLoudly() {
        server.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(503).withBody("down")));

        ModelUnreachableException ex = assertThrows(ModelUnreachableException.class,
                () -> fallback.invoke("no_such_kind", "系统提示", "用户输入", REPORT_SCHEMA,
                        UUID.randomUUID(), Map.of()));
        assertTrue(ex.getMessage().contains("no_such_kind"), ex.getMessage());
    }

    @Test
    @DisplayName("兜底载荷与 schema 不符（模板漂移）→ 抛错，不写结构不对的载荷")
    void fallbackPayloadViolatingSchemaFailsLoudly() {
        server.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(503).withBody("down")));

        // 这份 schema 要求一个模板根本不会产出的字段
        JsonSchema impossible = JsonSchema.of("impossible", Map.of(
                "type", "object",
                "required", List.of("bodyPart", "findings", "impression", "studyItems", "extraField"),
                "properties", Map.of(
                        "bodyPart", Map.of("type", "string"),
                        "findings", Map.of("type", "string"),
                        "impression", Map.of("type", "string"),
                        "studyItems", Map.of("type", "array", "items", Map.of("type", "string")),
                        "extraField", Map.of("type", "string"))));

        assertThrows(ModelUnreachableException.class,
                () -> fallback.invoke(KIND, "系统提示", "用户输入", impossible,
                        UUID.randomUUID(), Map.of()));
    }

    @Test
    @DisplayName("模型调用失败不会推进任何业务状态（本类不碰任务行）")
    void fallbackDoesNotTouchBusinessState() {
        server.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(503).withBody("down")));

        invoke(Map.of());

        // 只写审计，不写别的
        verify(auditRepository).save(any(AiCallAudit.class));
        verify(auditRepository, never()).delete(any());
    }

    // ─────────────── 请求形状 ───────────────

    @Test
    @DisplayName("兜底路径也会真的向模型发过一次请求（不是「压根不试」）")
    void modelIsTriedBeforeFallback() {
        server.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        invoke(Map.of());

        server.verify(postRequestedFor(urlPathEqualTo(CHAT_PATH))
                .withRequestBody(containing("你是影像术语归一助手"))
                .withRequestBody(containing("外院报告原文")));
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
