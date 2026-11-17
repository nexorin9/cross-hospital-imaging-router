package com.crosshospital.imaging.service.model;

import com.crosshospital.imaging.config.ModelConfigProps;
import com.crosshospital.imaging.exception.ModelResponseParseException;
import com.crosshospital.imaging.exception.ModelTimeoutException;
import com.crosshospital.imaging.exception.ModelUnreachableException;
import com.crosshospital.imaging.exception.ValidationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 模型网关的<b>降级装饰器</b>（task 27）。
 *
 * <p><b>调用链</b>：
 * <pre>
 *   try 模型（ModelGateway）
 *     ├─ 成功 → AiResult(source=model)            ← 落 ai_call_audit(success)
 *     └─ 失败（连不上 / 超时 / 读不懂 / 输出不完整 / 入参不合法）
 *          ├─ 落 ai_call_audit(失败五态)
 *          ├─ try 规则模板（RuleTemplateEngine）
 *          │    ├─ 成功 → AiResult(source=rule_fallback)  ← 载荷与模型路径同形状
 *          │    └─ 失败 → 抛 ModelUnreachableException（原始失败保留在 cause 链里）
 *          └─ 兜底被关掉（model.fallback.enabled=false）→ 直接抛
 * </pre>
 *
 * <p><b>为什么「入参不合法」也要走兜底</b>：外院报告的某个字段为空是家常便饭，
 * 而提示词正文为空恰恰会被网关判为请求不合法。这条路<b>不该让本次介入颗粒无收</b> ——
 * 规则模板对「输入缺失」本来就有话说（写「外院报告未提供影像所见描述」这类兜底文案，
 * 既不臆造、也让医生一眼看出缺什么）。所以入参不合法与网络不可达一样，
 * 一律转规则模板兜底，并在审计里留一行说明「未发起模型调用」。
 *
 * <p><b>为什么兜底不是「静默返回空」</b>：空结果会被下游当成「模型认为没有异常」，
 * 医生在 PACS 侧看到的是一份<b>看起来正常但其实什么都没做</b>的报告 ——
 * 这比明确报错危险得多。兜底产出的载荷<b>形状与模型一致</b>，内容却是明写在词典里的
 * 「未明确 / 外院报告未提供…」这类<b>一眼看得出是兜底</b>的文案，
 * 并且 {@code ai_summaries.source} 标成 {@code rule_fallback}。
 *
 * <p><b>兜底结果也要过 schema 校验</b>：规则模板渲染出的载荷若与模型路径的 schema 不符，
 * 说明模板与 schema 已经漂移 —— 这时宁可让这次调用失败（让信息科看见），
 * 也不要把一份结构不对的载荷写进 {@code ai_summaries} 让医生去猜。
 *
 * <p><b>责任边界</b>：本类只产出草稿，<b>不</b>写 PACS、<b>不</b>推 HIS、
 * <b>不</b>推进调阅任务状态。模型不可达时，跨院调阅主路径照样走完 ——
 * AI 是增强层，不是前置条件。
 *
 * <p>源主路径证据：通用 AI 系统的降级模式 —— 失败必须<b>被分类、被记录、被兜住</b>，
 * 且兜底结果与主路径结果<b>同构</b>，下游才不用分叉。
 */
@Service
@Profile("!test")
public class ModelGatewayWithFallback {

    private static final Logger LOG = LoggerFactory.getLogger(ModelGatewayWithFallback.class);

    private final ModelGateway gateway;
    private final RuleTemplateEngine ruleTemplates;
    private final AiCallAuditWriter auditWriter;
    private final ModelConfigProps props;
    private final ObjectMapper mapper;

    public ModelGatewayWithFallback(ModelGateway gateway,
                                    RuleTemplateEngine ruleTemplates,
                                    AiCallAuditWriter auditWriter,
                                    ModelConfigProps props,
                                    ObjectMapper mapper) {
        if (gateway == null || ruleTemplates == null || auditWriter == null || props == null || mapper == null) {
            throw new IllegalArgumentException("ModelGatewayWithFallback 的依赖不能为 null");
        }
        this.gateway = gateway;
        this.ruleTemplates = ruleTemplates;
        this.auditWriter = auditWriter;
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * 跑一次介入点：模型优先，失败则规则兜底。
     *
     * @param kind       介入点类型（{@code report_normalize} / {@code feasibility_screener}）
     * @param messages   提示词（模型路径用）
     * @param schema     期望输出结构（两条路径都用：模型路径校验模型回话，兜底路径校验模板产物）
     * @param taskId     任务 ID（可空；落审计与模板引用）
     * @param ruleInputs 规则模板输入（可空）
     * @return 产出（{@code source} 标明来自模型还是规则兜底）
     * @throws ModelUnreachableException 模型失败<b>且</b>规则兜底也失败
     */
    public AiResult invoke(String kind, List<ChatMessage> messages, JsonSchema schema,
                           UUID taskId, Map<String, Object> ruleInputs) {
        long start = System.currentTimeMillis();
        try {
            ModelResponse response = gateway.chatCompletion(messages, schema);
            auditWriter.recordSuccess(taskId, kind, response.model(), response.latencyMs());
            return AiResult.fromModel(kind, response.content(), response.model(), response.latencyMs());
        } catch (ModelUnreachableException | ModelTimeoutException | ModelResponseParseException
                 | ValidationException ex) {
            return fallback(kind, schema, taskId, ruleInputs, ex, start);
        }
    }

    /**
     * 跑一次介入点（系统提示 + 用户输入的便捷重载）。
     *
     * @param kind       介入点类型
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户输入
     * @param schema       期望输出结构
     * @param taskId       任务 ID（可空）
     * @param ruleInputs   规则模板输入（可空）
     */
    public AiResult invoke(String kind, String systemPrompt, String userPrompt, JsonSchema schema,
                           UUID taskId, Map<String, Object> ruleInputs) {
        return invoke(kind, List.of(ChatMessage.system(systemPrompt), ChatMessage.user(userPrompt)),
                schema, taskId, ruleInputs);
    }

    // ==================================================================
    // 降级路径
    // ==================================================================

    private AiResult fallback(String kind, JsonSchema schema, UUID taskId,
                              Map<String, Object> ruleInputs, RuntimeException modelFailure, long start) {
        long elapsed = System.currentTimeMillis() - start;
        String status = AiCallAuditWriter.classify(modelFailure);
        auditWriter.recordFailure(taskId, kind, props.getModel(), elapsed, status, modelFailure.getMessage());
        LOG.warn("模型调用失败（{}），转规则模板兜底：kind={} taskId={} msg={}",
                status, kind, taskId, modelFailure.getMessage());

        if (!props.getFallback().isEnabled()) {
            throw asUnreachable(modelFailure, kind, "规则兜底已被关闭（model.fallback.enabled=false）");
        }
        if (!ruleTemplates.supports(kind)) {
            throw asUnreachable(modelFailure, kind, "没有 kind=" + kind + " 的规则模板");
        }

        String payload;
        try {
            payload = ruleTemplates.readRuleTemplate(kind, taskId, ruleInputs);
        } catch (RuntimeException ex) {
            LOG.error("规则模板兜底失败（模型与兜底双失败）：kind={} taskId={} msg={}", kind, taskId, ex.getMessage());
            throw asUnreachable(modelFailure, kind, "规则模板渲染失败：" + ex.getMessage());
        }
        try {
            validateFallbackPayload(payload, schema, kind);
        } catch (RuntimeException ex) {
            LOG.error("规则模板兜底结果不合规（模型与兜底双失败）：kind={} taskId={} msg={}", kind, taskId, ex.getMessage());
            throw asUnreachable(modelFailure, kind, "规则模板兜底结果不符合 schema：" + ex.getMessage());
        }

        String reason = describeFailure(status, modelFailure);
        LOG.info("已用规则模板兜底：kind={} taskId={} source={} 原因={}", kind, taskId,
                AiResult.SOURCE_RULE_FALLBACK, reason);
        // 模型名传 null：规则兜底的产出没有模型参与，V4 迁移也明写「rule_fallback 时为 NULL」。
        // 把配置里的模型名写上去，只会让「模型没配 / 压根没调用」的行看起来像是模型写的。
        return AiResult.fromRuleFallback(kind, payload, null, elapsed, reason);
    }

    /**
     * 兜底载荷也要过 schema —— 模板与 schema 漂移时宁可失败，也不写结构不对的载荷。
     */
    private void validateFallbackPayload(String payload, JsonSchema schema, String kind) {
        if (schema == null || schema.schema() == null || schema.schema().isEmpty()) {
            return;
        }
        JsonNode instance;
        try {
            instance = mapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规则模板兜底结果不是合法 JSON（kind=" + kind + "）：" + e.getMessage(), e);
        }
        List<String> violations = JsonSchemaValidator.validate(schema.schema(), instance);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("规则模板兜底结果不符合 schema（kind=" + kind + "）："
                    + JsonSchemaValidator.summarize(violations));
        }
    }

    /**
     * 把原始模型失败包装成 {@link ModelUnreachableException}。
     *
     * <p><b>为什么统一包装而不是原样抛出</b>：调用方（任务编排层）把「模型这条线彻底不可用」
     * 当成一个可识别的失败出口处理（不推进 PACS 写入、不推进任务状态）。
     * 但「为什么兜底没接上」（模板缺失 / 兜底开关关了 / 模板产物不合规）这条上下文
     * 只存在于兜底这一侧 —— 原样抛出会把它丢掉，排障的人只看到「模型返回 503」，
     * 不知道其实还有规则模板这条路、只是这条路也出了问题。
     *
     * <p><b>包装后仍然保真</b>：原始失败是 {@link ModelUnreachableException} 时，
     * 它的 {@code reason / httpStatus / retryable / responseSnippet} <b>原样带过来</b>；
     * 原始异常作为 cause 保留；真实失败类型（timeout / parse_error / unreachable）
     * 也已经逐条落进 {@code ai_call_audit}。
     */
    private RuntimeException asUnreachable(RuntimeException modelFailure, String kind, String reason) {
        ModelUnreachableException.Reason reasonCode;
        int httpStatus = -1;
        boolean retryable;
        String snippet = "";
        if (modelFailure instanceof ModelUnreachableException unreachable) {
            reasonCode = unreachable.getReason();
            httpStatus = unreachable.getHttpStatus();
            retryable = unreachable.isRetryable();
            snippet = unreachable.getResponseSnippet();
        } else if (modelFailure instanceof ModelTimeoutException) {
            reasonCode = ModelUnreachableException.Reason.TRANSPORT_FAILURE;
            retryable = true;
        } else if (modelFailure instanceof ValidationException) {
            // 请求本身不合法：重发一次还是一样的不合法 → 不可重试，且不是服务端错误
            reasonCode = ModelUnreachableException.Reason.CLIENT_ERROR;
            retryable = false;
        } else {
            reasonCode = ModelUnreachableException.Reason.SERVER_ERROR;
            retryable = false;
        }
        return new ModelUnreachableException(reasonCode, httpStatus, retryable, snippet,
                "模型调用失败且规则兜底也不可用（kind=" + kind + "，" + reason + "）：" + modelFailure.getMessage(),
                modelFailure);
    }

    /** 兜底原因的一句话描述（落进 {@code ai_summaries} 与日志）。 */
    private static String describeFailure(String status, RuntimeException modelFailure) {
        if (modelFailure instanceof ValidationException) {
            // 入参不合法：模型压根没被调用 —— 说成「模型服务不可达」会把排障方向带偏
            return "请求参数不合法（未发起模型调用）：" + modelFailure.getMessage();
        }
        return switch (status) {
            case AiCallAuditWriter.STATUS_TIMEOUT -> "模型服务超时：" + modelFailure.getMessage();
            case AiCallAuditWriter.STATUS_PARSE_ERROR -> "模型响应不可用：" + modelFailure.getMessage();
            case AiCallAuditWriter.STATUS_HTTP_ERROR -> "模型服务返回错误状态：" + modelFailure.getMessage();
            default -> "模型服务不可达：" + modelFailure.getMessage();
        };
    }
}
