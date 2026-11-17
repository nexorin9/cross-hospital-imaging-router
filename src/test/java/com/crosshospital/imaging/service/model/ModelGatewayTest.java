package com.crosshospital.imaging.service.model;

import com.crosshospital.imaging.config.ModelConfigProps;
import com.crosshospital.imaging.exception.ModelResponseParseException;
import com.crosshospital.imaging.exception.ModelTimeoutException;
import com.crosshospital.imaging.exception.ModelUnreachableException;
import com.crosshospital.imaging.exception.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 26 验收：OpenAI-compatible 模型网关。
 *
 * <p>用 WireMock 起真实 HTTP 端点、走真实 OkHttp 出站调用（不 mock 客户端本身），
 * 覆盖「报文形状 / 结构化输出 / 超时 / 4xx / 5xx / 传输失败 / 解析失败 / 未配置」八类行为。
 *
 * <p><b>负向优先</b>：解析失败、鉴权失败这两类必须证明「没有偷偷重试」——
 * 重试会让「模型服务在闹脾气」被误读成「网络抖动」，进而把 AI 层的失败变成资源浪费。
 */
class ModelGatewayTest {

    private static final String CHAT_PATH = "/v1/chat/completions";

    private static final String OK_BODY = """
            {
              "id": "chatcmpl-test-001",
              "model": "test-model",
              "choices": [
                {"index": 0, "message": {"role": "assistant",
                 "content": "{\\"bodyPart\\":\\"胸部\\",\\"findings\\":\\"双肺未见明显异常\\"}"},
                 "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150}
            }
            """;

    /** 报告归一用的最小 schema：两个必填字段。 */
    private static final JsonSchema REPORT_SCHEMA = JsonSchema.of("normalized_report", Map.of(
            "type", "object",
            "required", List.of("bodyPart", "findings"),
            "properties", Map.of(
                    "bodyPart", Map.of("type", "string"),
                    "findings", Map.of("type", "string"))));

    private WireMockServer server;
    private ModelConfigProps props;
    private ModelGateway gateway;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        props = new ModelConfigProps();
        props.setBaseUrl("http://127.0.0.1:" + server.port());
        props.setApiKey("test-api-key");
        props.setModel("test-model");
        props.setTimeoutMs(3000);
        props.setMaxRetries(1);
        gateway = new ModelGateway(props, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // ─────────────── 正常路径 ───────────────

    @Test
    @DisplayName("200 + 合法响应 → ModelResponse 解析出 id/model/content/usage")
    void validResponseIsParsedIntoModelResponse() {
        stubOk(OK_BODY);

        ModelResponse response = gateway.chatCompletion(
                List.of(ChatMessage.system("你是影像术语归一助手"), ChatMessage.user("外院报告原文")),
                REPORT_SCHEMA);

        assertNotNull(response);
        assertEquals("chatcmpl-test-001", response.id());
        assertEquals("test-model", response.model());
        assertEquals("{\"bodyPart\":\"胸部\",\"findings\":\"双肺未见明显异常\"}", response.content());
        assertEquals("stop", response.finishReason());
        assertEquals(120, response.usage().promptTokens());
        assertEquals(30, response.usage().completionTokens());
        assertEquals(150, response.usage().totalTokens());
        assertEquals(1, response.attemptCount());
        assertTrue(response.latencyMs() >= 0);
    }

    @Test
    @DisplayName("请求报文是 OpenAI-compatible 形状：路径 /v1/chat/completions + Bearer + model/messages/response_format/temperature")
    void requestUsesOpenAiCompatibleWireFormat() {
        stubOk(OK_BODY);

        gateway.chatCompletion(List.of(ChatMessage.user("外院报告原文")), REPORT_SCHEMA);

        server.verify(postRequestedFor(urlPathEqualTo(CHAT_PATH))
                .withHeader("Authorization", equalTo("Bearer test-api-key"))
                .withHeader("Content-Type", WireMock.containing("application/json")));

        String body = server.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH))).get(0).getBodyAsString();
        assertTrue(body.contains("\"model\":\"test-model\""), "报文应含 model：" + body);
        assertTrue(body.contains("\"messages\""), "报文应含 messages：" + body);
        assertTrue(body.contains("\"response_format\""), "报文应含 response_format：" + body);
        assertTrue(body.contains("\"json_schema\""), "response_format 应为 json_schema：" + body);
        assertTrue(body.contains("\"temperature\":0.1"), "温度默认 0.1：" + body);
    }

    @Test
    @DisplayName("apiKey 留空 → 不带 Authorization 头（本地模型端点常见）")
    void blankApiKeyOmitsAuthorizationHeader() {
        props.setApiKey("");
        stubOk(OK_BODY);

        gateway.chatCompletion(List.of(ChatMessage.user("外院报告原文")), REPORT_SCHEMA);

        server.verify(postRequestedFor(urlPathEqualTo(CHAT_PATH)).withHeader("Authorization", absent()));
    }

    @Test
    @DisplayName("临时 5xx 后重试成功 → 返回正常响应且 attemptCount=2")
    void retrySucceedsAfterTransientServerError() {
        server.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .inScenario("retry").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500).withBody("boom"))
                .willSetStateTo("second"));
        server.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .inScenario("retry").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(OK_BODY)));

        ModelResponse response = gateway.chatCompletion(
                List.of(ChatMessage.user("外院报告原文")), REPORT_SCHEMA);

        assertEquals(2, response.attemptCount(), "第一次 500、第二次成功");
        assertEquals(2, server.getAllServeEvents().size(), "应发出 2 次 HTTP 请求");
    }

    // ─────────────── 不可达路径 ───────────────

    @Test
    @DisplayName("HTTP 500 → ModelUnreachableException(SERVER_ERROR, 可重试)，且重试到上限")
    void http500IsRetriedThenRaised() {
        server.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        ModelUnreachableException ex = assertThrows(ModelUnreachableException.class,
                () -> gateway.chatCompletion(List.of(ChatMessage.user("x")), REPORT_SCHEMA));

        assertEquals(ModelUnreachableException.Reason.SERVER_ERROR, ex.getReason());
        assertEquals(500, ex.getHttpStatus());
        assertTrue(ex.isRetryable(), "5xx 可重试");
        assertEquals(2, server.getAllServeEvents().size(), "maxRetries=1 → 共 2 次出站");
    }

    @Test
    @DisplayName("HTTP 401 → ModelUnreachableException(UNAUTHORIZED, 不可重试)，只出站 1 次")
    void http401IsNotRetried() {
        server.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"invalid api key\"}")));

        ModelUnreachableException ex = assertThrows(ModelUnreachableException.class,
                () -> gateway.chatCompletion(List.of(ChatMessage.user("x")), REPORT_SCHEMA));

        assertEquals(ModelUnreachableException.Reason.UNAUTHORIZED, ex.getReason());
        assertEquals(401, ex.getHttpStatus());
        assertFalse(ex.isRetryable(), "401 重试没有意义");
        assertEquals(1, server.getAllServeEvents().size(), "鉴权失败不应重试");
    }

    @Test
    @DisplayName("连接被拒 → ModelUnreachableException(TRANSPORT_FAILURE)")
    void connectionRefusedIsTransportFailure() throws IOException {
        int closedPort = freePort();
        props.setBaseUrl("http://127.0.0.1:" + closedPort);
        props.setMaxRetries(0);

        ModelUnreachableException ex = assertThrows(ModelUnreachableException.class,
                () -> gateway.chatCompletion(List.of(ChatMessage.user("x")), REPORT_SCHEMA));

        assertEquals(ModelUnreachableException.Reason.TRANSPORT_FAILURE, ex.getReason());
        assertEquals(-1, ex.getHttpStatus());
        assertTrue(ex.isRetryable());
    }

    @Test
    @DisplayName("调用超时 → ModelTimeoutException（带超时预算与端点）")
    void timeoutIsRaisedAsModelTimeoutException() {
        props.setTimeoutMs(300);
        props.setMaxRetries(0);
        server.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(OK_BODY).withFixedDelay(1500)));

        ModelTimeoutException ex = assertThrows(ModelTimeoutException.class,
                () -> gateway.chatCompletion(List.of(ChatMessage.user("x")), REPORT_SCHEMA));

        assertEquals(300, ex.getTimeoutMs());
        assertTrue(ex.isRetryable(), "超时可重试");
        assertTrue(ex.getEndpoint().endsWith(CHAT_PATH));
    }

    @Test
    @DisplayName("model.base-url 未配置 → NOT_CONFIGURED，且零出站调用")
    void unconfiguredGatewayFailsWithoutOutboundCall() {
        props.setBaseUrl("");

        ModelUnreachableException ex = assertThrows(ModelUnreachableException.class,
                () -> gateway.chatCompletion(List.of(ChatMessage.user("x")), REPORT_SCHEMA));

        assertEquals(ModelUnreachableException.Reason.NOT_CONFIGURED, ex.getReason());
        assertFalse(ex.isRetryable());
        assertTrue(server.getAllServeEvents().isEmpty(), "未配置时不得发出任何请求");
    }

    // ─────────────── 解析失败路径 ───────────────

    @Test
    @DisplayName("响应体不是 JSON → ModelResponseParseException(NOT_JSON)")
    void nonJsonBodyIsRejected() {
        stubOk("<html>502 Bad Gateway</html>");

        ModelResponseParseException ex = assertThrows(ModelResponseParseException.class,
                () -> gateway.chatCompletion(List.of(ChatMessage.user("x")), REPORT_SCHEMA));

        assertEquals(ModelResponseParseException.ParseError.NOT_JSON, ex.getParseError());
        assertFalse(ex.isRetryable(), "解析失败不重试");
        assertEquals(1, server.getAllServeEvents().size(), "解析失败不应重试");
    }

    @Test
    @DisplayName("响应缺 choices → ModelResponseParseException(MISSING_CHOICE)")
    void missingChoicesIsRejected() {
        stubOk("{\"id\":\"chatcmpl-x\",\"usage\":{\"total_tokens\":3}}");

        ModelResponseParseException ex = assertThrows(ModelResponseParseException.class,
                () -> gateway.chatCompletion(List.of(ChatMessage.user("x")), REPORT_SCHEMA));

        assertEquals(ModelResponseParseException.ParseError.MISSING_CHOICE, ex.getParseError());
    }

    @Test
    @DisplayName("content 为空 → ModelResponseParseException(MISSING_CONTENT)")
    void blankContentIsRejected() {
        stubOk("{\"id\":\"chatcmpl-x\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"  \"}}]}");

        ModelResponseParseException ex = assertThrows(ModelResponseParseException.class,
                () -> gateway.chatCompletion(List.of(ChatMessage.user("x")), REPORT_SCHEMA));

        assertEquals(ModelResponseParseException.ParseError.MISSING_CONTENT, ex.getParseError());
    }

    @Test
    @DisplayName("要求 JSON 但回话不是 JSON → ModelResponseParseException(INVALID_JSON_CONTENT)")
    void nonJsonContentIsRejected() {
        stubOk("""
                {"id":"chatcmpl-x","choices":[{"index":0,"message":{"role":"assistant","content":"胸部未见明显异常"}}]}
                """);

        ModelResponseParseException ex = assertThrows(ModelResponseParseException.class,
                () -> gateway.chatCompletion(List.of(ChatMessage.user("x")), REPORT_SCHEMA));

        assertEquals(ModelResponseParseException.ParseError.INVALID_JSON_CONTENT, ex.getParseError());
    }

    @Test
    @DisplayName("回话是合法 JSON 但缺必填字段 → ModelResponseParseException(SCHEMA_VIOLATION)，并带具体违规条目")
    void schemaViolationIsRejectedWithDetails() {
        stubOk("""
                {"id":"chatcmpl-x","choices":[{"index":0,"message":{"role":"assistant","content":"{\\"bodyPart\\":\\"胸部\\"}"}}]}
                """);

        ModelResponseParseException ex = assertThrows(ModelResponseParseException.class,
                () -> gateway.chatCompletion(List.of(ChatMessage.user("x")), REPORT_SCHEMA));

        assertEquals(ModelResponseParseException.ParseError.SCHEMA_VIOLATION, ex.getParseError());
        assertFalse(ex.getViolations().isEmpty(), "应给出具体违规条目");
        assertTrue(ex.getViolations().stream().anyMatch(v -> v.contains("findings")),
                "违规条目应点名缺失字段：" + ex.getViolations());
    }

    @Test
    @DisplayName("回话被 ```json 围栏包住 → 剥围栏后正常解析")
    void codeFencedJsonIsAccepted() {
        stubOk("""
                {"id":"chatcmpl-x","choices":[{"index":0,"message":{"role":"assistant",
                 "content":"```json\\n{\\"bodyPart\\":\\"胸部\\",\\"findings\\":\\"无明显异常\\"}\\n```"}}]}
                """);

        ModelResponse response = gateway.chatCompletion(List.of(ChatMessage.user("x")), REPORT_SCHEMA);

        assertTrue(response.content().contains("bodyPart"));
    }

    // ─────────────── 回话不完整（finish_reason） ───────────────

    @Test
    @DisplayName("finish_reason=length（输出被截断）→ 判为解析失败，不得当成完整结果返回")
    void truncatedOutputIsRejected() {
        // 回话本身是合法 JSON 且符合 schema：只有 finish_reason 说它被截断了。
        // 这种报文今天会被当成成功结果直接返回 —— 那就是「半份归一报告当完整报告送审」。
        stubOk("""
                {"id":"chatcmpl-x","choices":[{"index":0,"message":{"role":"assistant",
                 "content":"{\\"bodyPart\\":\\"胸部\\",\\"findings\\":\\"双肺未见明显异常\\"}"},
                 "finish_reason":"length"}]}
                """);

        ModelResponseParseException ex = assertThrows(ModelResponseParseException.class,
                () -> gateway.chatCompletion(List.of(ChatMessage.user("x")), REPORT_SCHEMA));

        assertEquals(ModelResponseParseException.ParseError.INCOMPLETE_OUTPUT, ex.getParseError());
        assertTrue(ex.getMessage().contains("截断"), "应说明回话被截断：" + ex.getMessage());
        assertFalse(ex.isRetryable(), "截断不是传输故障，不应重试");
        assertEquals(1, server.getAllServeEvents().size(), "截断不是传输故障，不应重试");
    }

    @Test
    @DisplayName("finish_reason=content_filter（被内容安全拦截）→ 判为解析失败")
    void contentFilteredOutputIsRejected() {
        stubOk("""
                {"id":"chatcmpl-x","choices":[{"index":0,"message":{"role":"assistant",
                 "content":"{\\"bodyPart\\":\\"胸部\\",\\"findings\\":\\"（内容被安全策略拦截，未生成）\\"}"},
                 "finish_reason":"content_filter"}]}
                """);

        ModelResponseParseException ex = assertThrows(ModelResponseParseException.class,
                () -> gateway.chatCompletion(List.of(ChatMessage.user("x")), REPORT_SCHEMA));

        assertEquals(ModelResponseParseException.ParseError.INCOMPLETE_OUTPUT, ex.getParseError());
        assertTrue(ex.getMessage().contains("内容安全"), "应说明被内容安全拦截：" + ex.getMessage());
        assertFalse(ex.isRetryable(), "被拦截不是传输故障，不应重试");
        assertEquals(1, server.getAllServeEvents().size(), "被拦截不是传输故障，不应重试");
    }

    @Test
    @DisplayName("text 格式 + finish_reason=length → 截断的半句话不得当成完整归一结果")
    void truncatedPlainTextIsRejected() {
        props.setResponseFormat("text");
        stubOk("""
                {"id":"chatcmpl-x","choices":[{"index":0,"message":{"role":"assistant",
                 "content":"胸部未见明显异常，双肺纹理清晰，未见实"},
                 "finish_reason":"length"}]}
                """);

        ModelResponseParseException ex = assertThrows(ModelResponseParseException.class,
                () -> gateway.chatCompletion(List.of(ChatMessage.user("x"))));

        assertEquals(ModelResponseParseException.ParseError.INCOMPLETE_OUTPUT, ex.getParseError());
        assertTrue(ex.getMessage().contains("截断"), "应说明回话被截断：" + ex.getMessage());
        assertEquals(1, server.getAllServeEvents().size(), "截断不是传输故障，不应重试");
    }

    @Test
    @DisplayName("响应不带 finish_reason（部分自建端点省略）→ 照常接受，不得误杀")
    void missingFinishReasonIsAccepted() {
        stubOk("""
                {"id":"chatcmpl-x","choices":[{"index":0,"message":{"role":"assistant",
                 "content":"{\\"bodyPart\\":\\"胸部\\",\\"findings\\":\\"无明显异常\\"}"}}]}
                """);

        ModelResponse response = gateway.chatCompletion(List.of(ChatMessage.user("x")), REPORT_SCHEMA);

        assertNotNull(response);
        assertTrue(response.content().contains("bodyPart"));
    }

    @Test
    @DisplayName("非标准 finish_reason（如 eos）→ 照常接受，不得误杀自建端点")
    void nonStandardFinishReasonIsAccepted() {
        stubOk("""
                {"id":"chatcmpl-x","choices":[{"index":0,"message":{"role":"assistant",
                 "content":"{\\"bodyPart\\":\\"胸部\\",\\"findings\\":\\"无明显异常\\"}"},
                 "finish_reason":"eos"}]}
                """);

        ModelResponse response = gateway.chatCompletion(List.of(ChatMessage.user("x")), REPORT_SCHEMA);

        assertNotNull(response);
        assertEquals("eos", response.finishReason());
    }

    // ─────────────── 请求前置校验 ───────────────

    @Test
    @DisplayName("messages 为空 → ValidationException，且零出站调用")
    void emptyMessagesFailBeforeAnyOutboundCall() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> gateway.chatCompletion(List.of(), REPORT_SCHEMA));

        assertTrue(ex.getMessage().contains("messages"));
        assertTrue(server.getAllServeEvents().isEmpty(), "校验失败不得发出请求");
    }

    @Test
    @DisplayName("消息正文空白 → ValidationException，且零出站调用")
    void blankMessageContentFailsBeforeAnyOutboundCall() {
        assertThrows(ValidationException.class,
                () -> gateway.chatCompletion(List.of(ChatMessage.user("   ")), REPORT_SCHEMA));

        assertTrue(server.getAllServeEvents().isEmpty());
    }

    // ─────────────── 端点拼装 ───────────────

    @Test
    @DisplayName("base-url 已带 /v1 → 不重复拼 /v1")
    void baseUrlEndingWithV1IsNotDoubled() {
        assertEquals("http://h:1/v1/chat/completions", gateway.buildEndpoint("http://h:1/v1"));
        assertEquals("http://h:1/v1/chat/completions", gateway.buildEndpoint("http://h:1"));
        assertEquals("http://h:1/v1/chat/completions", gateway.buildEndpoint("http://h:1/"));
        assertEquals("http://h:1/v1/chat/completions",
                gateway.buildEndpoint("http://h:1/v1/chat/completions"));
    }

    @Test
    @DisplayName("未指定 schema 时按 model.response-format 降级为 json_object，并要求合法 JSON")
    void defaultFormatDowngradesToJsonObject() {
        props.setResponseFormat("json_schema");
        stubOk("""
                {"id":"chatcmpl-x","choices":[{"index":0,"message":{"role":"assistant","content":"{\\"a\\":1}"}}]}
                """);

        ModelResponse response = gateway.chatCompletion(List.of(ChatMessage.user("x")));

        assertNotNull(response);
        String body = server.findAll(postRequestedFor(urlPathEqualTo(CHAT_PATH))).get(0).getBodyAsString();
        assertTrue(body.contains("\"type\":\"json_object\""), "无 schema 时应降级为 json_object：" + body);
    }

    @Test
    @DisplayName("默认格式为 json_object 时，回话不是 JSON → INVALID_JSON_CONTENT")
    void jsonObjectFormatStillRequiresJson() {
        props.setResponseFormat("json_object");
        stubOk("""
                {"id":"chatcmpl-x","choices":[{"index":0,"message":{"role":"assistant","content":"不是 JSON"}}]}
                """);

        ModelResponseParseException ex = assertThrows(ModelResponseParseException.class,
                () -> gateway.chatCompletion(List.of(ChatMessage.user("x"))));

        assertEquals(ModelResponseParseException.ParseError.INVALID_JSON_CONTENT, ex.getParseError());
    }

    @Test
    @DisplayName("text 格式：回话是纯文本也照单全收，不做 JSON 校验")
    void textFormatAcceptsPlainText() {
        props.setResponseFormat("text");
        stubOk("""
                {"id":"chatcmpl-x","choices":[{"index":0,"message":{"role":"assistant","content":"胸部未见明显异常"}}]}
                """);

        ModelResponse response = gateway.chatCompletion(List.of(ChatMessage.user("x")));

        assertEquals("胸部未见明显异常", response.content());
    }

    // ─────────────── 辅助 ───────────────

    private void stubOk(String body) {
        server.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    /** 取一个刚释放的端口，用它连必然被拒。 */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
