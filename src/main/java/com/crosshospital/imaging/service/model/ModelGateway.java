package com.crosshospital.imaging.service.model;

import com.crosshospital.imaging.config.ModelConfigProps;
import com.crosshospital.imaging.exception.ModelResponseParseException;
import com.crosshospital.imaging.exception.ModelTimeoutException;
import com.crosshospital.imaging.exception.ModelUnreachableException;
import com.crosshospital.imaging.exception.ValidationException;
import com.crosshospital.imaging.service.metrics.ImagingMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * <b>唯一</b>的模型网关（OpenAI-compatible 协议客户端）。
 *
 * <p>本项目只对接 OpenAI-compatible 一种协议（院内自建端点 / 本地模型 / 云服务同协议），
 * 因此整个 AI 增强层只经过这一个类：业务层（报告归一、互认初筛）不直接碰任何厂商 SDK，
 * 换模型服务只改 {@code model.*} 配置即可。
 *
 * <p><b>源主路径</b>：OpenAI Chat Completions API 公开规范 ——
 * {@code POST {base-url}/v1/chat/completions}，请求 {@code model/messages/response_format/temperature/max_tokens}，
 * 响应 {@code id/model/choices[0].message.content/usage}。
 *
 * <p><b>失败语义</b>（三类互不混，处置策略不同）：
 * <ul>
 *   <li>{@link ModelUnreachableException} —— 端点没配 / 连不上 / 4xx / 5xx。
 *       401、4xx <b>不重试</b>（重试没用），5xx、429、传输失败<b>重试</b>。</li>
 *   <li>{@link ModelTimeoutException} —— 超时；<b>可重试</b>（模型只是慢）。</li>
 *   <li>{@link ModelResponseParseException} —— 拿到响应但读不懂 / 结构不符 / <b>没有完整生成</b>
 *       （{@code finish_reason=length} 截断、{@code content_filter} 拦截）；<b>不重试</b>
 *       （同样的提示 + 同样的模型大概率还是不符），直接走规则模板兜底（task 27）。</li>
 * </ul>
 *
 * <p><b>责任边界</b>：本类只负责「问模型要一句话」这一件事。它<b>不</b>推进任何业务状态 ——
 * 不写 PACS、不推 HIS、不改任务状态。AI 输出是否被采纳由医生在 PACS 工作站复核，
 * 模型不可达时的兜底与标注由 task 27 的装饰器负责。
 */
@Service
@Profile("!test")
public class ModelGateway {

    private static final Logger LOG = LoggerFactory.getLogger(ModelGateway.class);

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final String USER_AGENT = "cross-hospital-imaging-router/0.1 (model-gateway)";

    /** 重试退避基数（毫秒）：第 n 次失败后等 n × BACKOFF_BASE_MS。 */
    private static final int BACKOFF_BASE_MS = 200;

    /** 响应体读取上限（字符）；超过截断，避免异常端点回超大 body 打满堆。 */
    private static final int MAX_RESPONSE_CHARS = 1_048_576;

    /** 错误响应体片段保留上限（字符）。 */
    private static final int MAX_ERROR_SNIPPET = 512;

    /** {@code finish_reason}：输出被 max_tokens 截断（回话不完整）。 */
    private static final String FINISH_REASON_LENGTH = "length";

    /** {@code finish_reason}：输出被内容安全策略拦截（不是模型的业务结论）。 */
    private static final String FINISH_REASON_CONTENT_FILTER = "content_filter";

    private final ModelConfigProps props;
    private final ObjectMapper mapper;

    /** OkHttpClient 线程安全，构建一次即可；volatile 保证懒加载安全。 */
    private volatile OkHttpClient httpClient;

    /** 运行指标（task 37，可空）；模型调用次数与耗时只在本类记一次。 */
    private volatile ImagingMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMetrics(ImagingMetrics metrics) {
        this.metrics = metrics;
    }

    public ModelGateway(ModelConfigProps props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /** 是否配置了模型服务端点；未配置时任何调用都会直接失败（交由规则模板兜底）。 */
    public boolean isConfigured() {
        return props.isConfigured();
    }

    /** 当前配置的模型名。 */
    public String modelName() {
        return props.getModel();
    }

    /** 结构化调用（按 schema 约束模型输出）。 */
    public ModelResponse chatCompletion(List<ChatMessage> messages, JsonSchema schema) {
        ResponseFormat format = schema == null ? null : ResponseFormat.jsonSchema(schema);
        return chatCompletion(ModelRequest.builder()
                .model(props.getModel())
                .messages(messages)
                .responseFormat(format)
                .build());
    }

    /** 指定响应格式的调用。 */
    public ModelResponse chatCompletion(List<ChatMessage> messages, ResponseFormat responseFormat) {
        return chatCompletion(ModelRequest.builder()
                .model(props.getModel())
                .messages(messages)
                .responseFormat(responseFormat)
                .build());
    }

    /**
     * 不指定响应格式的调用：按 {@code model.response-format} 默认值走
     * （默认 {@code json_schema}，但没有 schema 时降级为 {@code json_object}）。
     */
    public ModelResponse chatCompletion(List<ChatMessage> messages) {
        return chatCompletion(messages, (ResponseFormat) null);
    }

    /** 「系统提示 + 一段输入 + 一份 schema」的单轮结构化调用（报告归一 / 互认初筛用这个）。 */
    public ModelResponse chatCompletion(String systemPrompt, String userPrompt, JsonSchema schema) {
        return chatCompletion(List.of(
                ChatMessage.system(systemPrompt),
                ChatMessage.user(userPrompt)), schema);
    }

    /**
     * 主入口：发起一次模型调用并归一化响应。
     *
     * @throws ValidationException              请求本身不合法（<b>不会发出任何 HTTP 请求</b>）
     * @throws ModelUnreachableException        端点未配置 / 连不上 / 状态码不可接受
     * @throws ModelTimeoutException           调用超时
     * @throws ModelResponseParseException     响应读不懂或结构不符
     */
    public ModelResponse chatCompletion(ModelRequest request) {
        validateRequest(request);

        if (!props.isConfigured()) {
            throw ModelUnreachableException.notConfigured(
                    "model.base-url 未配置（环境变量 MODEL_BASE_URL），模型服务不可达");
        }

        ResponseFormat format = resolveFormat(request.getResponseFormat());
        ModelRequest effective = request.withResponseFormat(format);
        String endpoint = buildEndpoint(props.getBaseUrl());
        if (metrics == null) {
            return execute(effective, format, endpoint);
        }
        metrics.aiCallStarted();
        Timer.Sample sample = metrics.startAiCall();
        try {
            return execute(effective, format, endpoint);
        } finally {
            metrics.aiCallCompleted(sample);
        }
    }

    // ─────────────── 请求校验 ───────────────

    /** 请求不合法时在<b>建联之前</b>失败：一次不合规的请求不该消耗模型侧配额。 */
    private void validateRequest(ModelRequest request) {
        if (request == null) {
            throw new ValidationException("模型请求不得为 null");
        }
        if (request.getModel() == null || request.getModel().isBlank()) {
            throw new ValidationException("模型请求缺少 model 字段");
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new ValidationException("模型请求 messages 不得为空");
        }
        for (ChatMessage message : request.getMessages()) {
            if (message == null) {
                throw new ValidationException("模型请求 messages 含 null 元素");
            }
            if (message.content() == null || message.content().isBlank()) {
                throw new ValidationException("模型请求消息正文不得为空（role=" + message.role() + "）");
            }
        }
    }

    /**
     * 解析最终生效的响应格式。
     *
     * <p>调用方没显式指定时按 {@code model.response-format} 走；配置成 {@code json_schema}
     * 但调用方没给 schema 时**降级为 json_object**（仍要求合法 JSON），而不是发一个
     * 服务端会拒的半截 {@code response_format}。
     */
    private ResponseFormat resolveFormat(ResponseFormat explicit) {
        if (explicit != null) {
            if (ResponseFormat.TYPE_JSON_SCHEMA.equals(explicit.getType())
                    && explicit.getJsonSchema() == null) {
                LOG.warn("response_format=json_schema 但未提供 schema，降级为 json_object");
                return ResponseFormat.jsonObject();
            }
            return explicit;
        }
        String configured = props.getResponseFormat();
        if (ResponseFormat.TYPE_JSON_SCHEMA.equals(configured)) {
            LOG.warn("model.response-format=json_schema 但调用未提供 schema，降级为 json_object");
            return ResponseFormat.jsonObject();
        }
        if (ResponseFormat.TYPE_JSON_OBJECT.equals(configured)) {
            return ResponseFormat.jsonObject();
        }
        return ResponseFormat.text();
    }

    // ─────────────── 端点与报文 ───────────────

    /**
     * 拼端点：容忍三种常见写法 —— 根地址、带 {@code /v1}、以及完整路径。
     *
     * <pre>
     * http://host:8000              → http://host:8000/v1/chat/completions
     * http://host:8000/v1           → http://host:8000/v1/chat/completions
     * http://host:8000/v1/chat/completions → 原样
     * </pre>
     */
    String buildEndpoint(String baseUrl) {
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        if (base.endsWith("/v1")) {
            return base + "/chat/completions";
        }
        return base + "/v1/chat/completions";
    }

    private Request buildRequest(String endpoint, String body) {
        Request.Builder builder = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(body, JSON))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
        String key = props.getApiKey();
        if (key != null && !key.isBlank()) {
            builder.header("Authorization", "Bearer " + key);
        }
        return builder.build();
    }

    // ─────────────── 调用与重试 ───────────────

    private ModelResponse execute(ModelRequest request, ResponseFormat format, String endpoint) {
        int maxAttempts = Math.max(1, props.getMaxRetries() + 1);
        long start = System.currentTimeMillis();
        RuntimeException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return doCall(request, format, endpoint, attempt, start);
            } catch (RuntimeException e) {
                last = e;
                if (!isRetryable(e) || attempt >= maxAttempts) {
                    throw e;
                }
                int backoff = BACKOFF_BASE_MS * attempt;
                LOG.warn("模型调用失败（{}），{}ms 后重试（第 {}/{} 次）", e.getMessage(), backoff,
                        attempt, maxAttempts);
                sleep(backoff);
            }
        }
        throw last;
    }

    /** 只有传输类失败值得重试；解析失败与 4xx 重试没有意义。 */
    private boolean isRetryable(RuntimeException e) {
        if (e instanceof ModelUnreachableException ue) {
            return ue.isRetryable();
        }
        return e instanceof ModelTimeoutException te && te.isRetryable();
    }

    private ModelResponse doCall(ModelRequest request, ResponseFormat format, String endpoint,
                                 int attempt, long start) {
        String body;
        try {
            body = mapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new ValidationException("模型请求序列化失败：" + e.getMessage());
        }

        Request httpRequest = buildRequest(endpoint, body);
        try (Response response = client().newCall(httpRequest).execute()) {
            int status = response.code();
            String text = readBody(response);
            if (status != 200) {
                throw ModelUnreachableException.httpStatus(status, snippet(text),
                        "模型服务返回 HTTP " + status + "（" + endpoint + "）");
            }
            ModelResponse parsed = parseResponse(text, request, format);
            return new ModelResponse(parsed.id(), parsed.model(), parsed.content(), parsed.finishReason(),
                    parsed.usage(), System.currentTimeMillis() - start, attempt);
        } catch (IOException e) {
            if (isTimeout(e)) {
                throw new ModelTimeoutException(props.getTimeoutMs(), endpoint,
                        "模型服务调用超时（" + props.getTimeoutMs() + "ms）：" + endpoint, e);
            }
            throw ModelUnreachableException.transportFailure(
                    "模型服务连接失败（" + e.getClass().getSimpleName() + "）：" + e.getMessage(), e);
        }
    }

    /** OkHttpClient 懒加载；超时三项都取 {@code model.timeout-ms}。 */
    OkHttpClient client() {
        OkHttpClient local = httpClient;
        if (local == null) {
            synchronized (this) {
                local = httpClient;
                if (local == null) {
                    local = new OkHttpClient.Builder()
                            .connectTimeout(props.getTimeoutMs(), TimeUnit.MILLISECONDS)
                            .readTimeout(props.getTimeoutMs(), TimeUnit.MILLISECONDS)
                            .writeTimeout(props.getTimeoutMs(), TimeUnit.MILLISECONDS)
                            .callTimeout(props.getTimeoutMs(), TimeUnit.MILLISECONDS)
                            // 重试由本类统一编排：OkHttp 内建重试会让「实际发了几次」对不上审计
                            .retryOnConnectionFailure(false)
                            .build();
                    httpClient = local;
                }
            }
        }
        return local;
    }

    // ─────────────── 响应解析 ───────────────

    /** 解析 OpenAI-compatible 响应；结构化要求存在时额外做 schema 校验。 */
    ModelResponse parseResponse(String body, ModelRequest request, ResponseFormat format) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ModelResponseParseException(ModelResponseParseException.ParseError.NOT_JSON, body,
                    List.of(), "模型响应不是合法 JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new ModelResponseParseException(ModelResponseParseException.ParseError.NOT_JSON, body,
                    List.of(), "模型响应不是 JSON 对象", null);
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ModelResponseParseException(ModelResponseParseException.ParseError.MISSING_CHOICE,
                    body, List.of(), "模型响应缺少 choices 数组", null);
        }

        JsonNode first = choices.get(0);
        String finishReason = first.path("finish_reason").isMissingNode()
                ? null : first.path("finish_reason").asText(null);
        rejectIncompleteOutput(finishReason, body);

        JsonNode contentNode = first.path("message").path("content");
        if (!contentNode.isTextual()) {
            throw new ModelResponseParseException(ModelResponseParseException.ParseError.MISSING_CONTENT,
                    body, List.of(), "模型响应缺少 choices[0].message.content", null);
        }
        String content = contentNode.asText();
        if (content.isBlank()) {
            throw new ModelResponseParseException(ModelResponseParseException.ParseError.MISSING_CONTENT,
                    body, List.of(), "模型响应内容为空白", null);
        }

        if (format.expectsJson()) {
            validateStructuredContent(content, format, body);
        }

        JsonNode usageNode = root.path("usage");
        ModelUsage usage = new ModelUsage(
                usageNode.path("prompt_tokens").asInt(0),
                usageNode.path("completion_tokens").asInt(0),
                usageNode.path("total_tokens").asInt(0));

        String id = root.path("id").isMissingNode() ? null : root.path("id").asText(null);
        String model = root.path("model").isMissingNode() ? request.getModel() : root.path("model").asText(null);

        return new ModelResponse(id, model, content, finishReason, usage, 0L, 1);
    }

    /**
     * 拒绝「模型没有完整生成」的回话。
     *
     * <p>OpenAI-compatible 协议用 {@code choices[0].finish_reason} 说明这一轮<b>是否完整</b>：
     * <ul>
     *   <li>{@code length} —— 输出被 {@code max_tokens} 截断，回话是<b>半份</b>结果</li>
     *   <li>{@code content_filter} —— 输出被内容安全策略拦截，回话<b>不是</b>模型的业务结论</li>
     * </ul>
     *
     * <p><b>为什么必须在这里拦</b>：这两种报文里的 {@code content} 可能碰巧是合法 JSON、
     * 甚至碰巧符合 schema —— 只看正文是看不出它被截断过的。若放行，被截断的归一结果
     * 会作为「完整结果」写进 {@code ai_summaries}，医生在 PACS 侧看到的是半份报告却以为
     * 是全的；而且网关会报成功，task 27 的规则模板兜底<b>不会</b>被触发。
     * 判成解析失败（不重试）后，兜底链路才能接管。
     *
     * <p><b>只拦这两个值</b>：{@code stop} 是正常结束；部分自建端点（vLLM / Ollama 等）
     * 会省略 {@code finish_reason} 或返回 {@code eos} / {@code stop_sequence} 之类的非标准值 ——
     * 那些一律照常接受，误杀自建端点比漏判更糟。
     */
    private void rejectIncompleteOutput(String finishReason, String body) {
        if (finishReason == null) {
            return;
        }
        String normalized = finishReason.trim().toLowerCase(Locale.ROOT);
        if (FINISH_REASON_LENGTH.equals(normalized)) {
            throw new ModelResponseParseException(ModelResponseParseException.ParseError.INCOMPLETE_OUTPUT,
                    body, List.of(),
                    "模型回话被长度上限截断（finish_reason=length），输出不完整，不可用作归一 / 初筛结果", null);
        }
        if (FINISH_REASON_CONTENT_FILTER.equals(normalized)) {
            throw new ModelResponseParseException(ModelResponseParseException.ParseError.INCOMPLETE_OUTPUT,
                    body, List.of(),
                    "模型回话被内容安全策略拦截（finish_reason=content_filter），没有可用结果", null);
        }
    }

    /** 结构化校验：先要「是合法 JSON」，再要「符合 schema」。 */
    private void validateStructuredContent(String content, ResponseFormat format, String body) {
        String cleaned = stripCodeFence(content);
        JsonNode instance;
        try {
            instance = mapper.readTree(cleaned);
        } catch (JsonProcessingException e) {
            throw new ModelResponseParseException(ModelResponseParseException.ParseError.INVALID_JSON_CONTENT,
                    content, List.of(), "模型回话不是合法 JSON", e);
        }
        if (instance == null || instance.isMissingNode()) {
            throw new ModelResponseParseException(ModelResponseParseException.ParseError.INVALID_JSON_CONTENT,
                    content, List.of(), "模型回话为空内容", null);
        }

        JsonSchema schema = format.getJsonSchema();
        if (schema == null) {
            return;
        }
        List<String> violations = JsonSchemaValidator.validate(schema.schema(), instance);
        if (!violations.isEmpty()) {
            throw new ModelResponseParseException(ModelResponseParseException.ParseError.SCHEMA_VIOLATION,
                    content, violations,
                    "模型回话不符合 schema：" + JsonSchemaValidator.summarize(violations), null);
        }
    }

    /** 部分模型会把 JSON 包在 ```json 围栏里；先剥围栏再解析。 */
    static String stripCodeFence(String content) {
        if (content == null) {
            return "";
        }
        String text = content.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int newline = text.indexOf('\n');
        if (newline < 0) {
            return text;
        }
        text = text.substring(newline + 1).trim();
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3).trim();
        }
        return text;
    }

    // ─────────────── 小工具 ───────────────

    private static boolean isTimeout(IOException e) {
        return e instanceof SocketTimeoutException || e instanceof InterruptedIOException;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ModelUnreachableException.transportFailure("模型调用退避被中断", ie);
        }
    }

    /** 读取响应体（带上限）；读失败返回空串，不让读 body 的异常盖掉真正的 HTTP 状态码。 */
    private String readBody(Response response) {
        ResponseBody body = response.body();
        if (body == null) {
            return "";
        }
        try (var in = body.byteStream()) {
            byte[] bytes = in.readNBytes(MAX_RESPONSE_CHARS);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String snippet(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        return body.length() > MAX_ERROR_SNIPPET
                ? body.substring(0, MAX_ERROR_SNIPPET) + "...[truncated]"
                : body;
    }
}
