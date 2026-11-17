package com.crosshospital.imaging.support;

import com.crosshospital.imaging.config.ModelConfigProps;
import com.crosshospital.imaging.domain.AiSummary;
import com.crosshospital.imaging.exception.ModelUnreachableException;
import com.crosshospital.imaging.repository.AiCallAuditRepository;
import com.crosshospital.imaging.repository.AiSummaryRepository;
import com.crosshospital.imaging.service.model.AiCallAuditWriter;
import com.crosshospital.imaging.service.model.AiEnrichmentSupport;
import com.crosshospital.imaging.service.model.AiSummaryService;
import com.crosshospital.imaging.service.model.ChatMessage;
import com.crosshospital.imaging.service.model.FeasibilityScreenerService;
import com.crosshospital.imaging.service.model.JsonSchema;
import com.crosshospital.imaging.service.model.ModelGateway;
import com.crosshospital.imaging.service.model.ModelGatewayWithFallback;
import com.crosshospital.imaging.service.model.ModelResponse;
import com.crosshospital.imaging.service.model.PromptTemplates;
import com.crosshospital.imaging.service.model.ReportNormalizationService;
import com.crosshospital.imaging.service.model.RuleTemplateEngine;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * task 28 / 29 测试夹具：装配一条<b>真实</b>的「模型 → 规则兜底 → 落库」链路。
 *
 * <p>最外层用可覆写的 {@link StubGateway} 代替真实 HTTP 出站（JDK 27 上 Mockito
 * 无法可靠 mock 具体类，故用子类覆写而不是 mock）。其余
 * {@link ModelGatewayWithFallback}、{@link RuleTemplateEngine}、
 * {@link AiEnrichmentSupport}、两个介入点服务全部是生产类；
 * 只有三个 Spring Data <b>接口</b>仓储是替身。
 * 因此「模型不可达 → 同形状兜底载荷 → ai_summaries.source=rule_fallback」是跑出来的，
 * 不是把服务返回值 mock 出来的。
 */
public final class AiEnrichmentTestSupport {

    private AiEnrichmentTestSupport() {
    }

    /** 记录调用次数的桩网关：不发出任何真实 HTTP 请求。 */
    public static final class StubGateway extends ModelGateway {

        private final ModelResponse response;
        private final RuntimeException failure;
        private int callCount;
        private List<ChatMessage> lastMessages = List.of();

        StubGateway(ModelConfigProps props, ObjectMapper mapper,
                    ModelResponse response, RuntimeException failure) {
            super(props, mapper);
            this.response = response;
            this.failure = failure;
        }

        @Override
        public ModelResponse chatCompletion(List<ChatMessage> messages, JsonSchema schema) {
            callCount++;
            lastMessages = new ArrayList<>(messages);
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        public int callCount() { return callCount; }
        public List<ChatMessage> lastMessages() { return List.copyOf(lastMessages); }
    }

    public record Fixture(ObjectMapper mapper,
                          StubGateway gateway,
                          ModelGatewayWithFallback fallback,
                          AiEnrichmentSupport support,
                          AiSummaryRepository summaries,
                          ReportNormalizationService reports,
                          FeasibilityScreenerService screener) {
    }

    /** 模型返回给定 JSON 内容。 */
    public static Fixture withModelResponse(String content) {
        return build(new ModelResponse("resp-1", "mock-model", content, "stop", null, 12L, 1), null);
    }

    /** 模型完全不可达（真实失败对象，触发规则模板兜底）。 */
    public static Fixture withFailingModel() {
        ModelUnreachableException failure = new ModelUnreachableException(
                ModelUnreachableException.Reason.TRANSPORT_FAILURE, -1, true, "",
                "连接模型服务被拒绝", null);
        return build(null, failure);
    }

    private static Fixture build(ModelResponse response, RuntimeException failure) {
        ObjectMapper mapper = new ObjectMapper();
        ModelConfigProps props = new ModelConfigProps();
        props.setBaseUrl("http://127.0.0.1:1"); // 仅让 isConfigured() 为真；桩网关不出站
        props.setModel("mock-model");
        ModelConfigProps.Fallback fallbackProps = new ModelConfigProps.Fallback();
        fallbackProps.setEnabled(true);
        fallbackProps.setTemplatesPath("classpath:hl7/segment-mappings.yml");
        fallbackProps.setRuleTemplatesPath("classpath:hl7/rule-templates.json");
        props.setFallback(fallbackProps);

        StubGateway gateway = new StubGateway(props, mapper, response, failure);
        RuleTemplateEngine engine = new RuleTemplateEngine(props, mapper);
        AiCallAuditWriter auditWriter =
                new AiCallAuditWriter(mock(AiCallAuditRepository.class));
        AiSummaryRepository summaries = mock(AiSummaryRepository.class);
        when(summaries.save(any(AiSummary.class))).thenAnswer(invocation -> {
            AiSummary row = invocation.getArgument(0);
            row.setId(101L);
            return row;
        });

        ModelGatewayWithFallback fallback = new ModelGatewayWithFallback(
                gateway, engine, auditWriter, props, mapper);
        AiEnrichmentSupport support = new AiEnrichmentSupport(fallback,
                new AiSummaryService(summaries));
        PromptTemplates prompts = new PromptTemplates();
        ReportNormalizationService reports =
                new ReportNormalizationService(fallback, support, prompts, mapper);
        FeasibilityScreenerService screener =
                new FeasibilityScreenerService(fallback, support, prompts, mapper);
        return new Fixture(mapper, gateway, fallback, support, summaries, reports, screener);
    }

    /** 一条中文外院报告样例（脱敏，字段为占位写法）。 */
    public static final String SAMPLE_REPORT = """
            检查部位：胸部
            检查项目：胸部 CT 平扫
            影像所见：双肺纹理清晰，未见异常密度影。
            诊断意见：双肺未见明显异常。
            """;

    /** 一条互认初筛模型回话样例。 */
    public static final String FEASIBILITY_JSON = """
            {"decision":"recognizable","reasons":["外院模态与本院检查项目匹配"],
             "requiresHumanReview":true}
            """;
}
