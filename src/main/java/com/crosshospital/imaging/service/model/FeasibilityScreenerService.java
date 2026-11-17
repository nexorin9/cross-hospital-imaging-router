package com.crosshospital.imaging.service.model;

import com.crosshospital.imaging.domain.ExternalStudyMetadata;
import com.crosshospital.imaging.domain.FeasibilityDraft;
import com.crosshospital.imaging.service.task.AiEnrichmentOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 互认可行性初筛（task 29，AI 介入点 2）。
 *
 * <p><b>输入 → 输出</b>：外院 study 元数据 + 本院检查项目 →
 * {@link FeasibilityDraft}（可互认序列 / 不建议互认序列 / 判据说明 / decision）。
 * 模型路径与规则兜底路径共用 {@link FeasibilityDraft#schema()}。
 *
 * <p><b>规则兜底按模态匹配</b>：模型不可达时，规则模板读
 * {@code external_modality / local_modality / external_study_item / local_study_item}
 * 四个输入，模态与项目名都一致才判 {@code recognizable}；缺输入一律
 * {@code not_recommended}（缺证据不往「可互认」上靠）。
 *
 * <p><b>结论永远只是草稿</b>：{@link FeasibilityDraft#requiresHumanReview()} 恒 true，
 * 不采信模型返回的 false；不回写 PACS 报告、不推进任务状态。
 */
@Service
@Profile("!test")
public class FeasibilityScreenerService {

    private static final Logger LOG = LoggerFactory.getLogger(FeasibilityScreenerService.class);

    private static final List<String> KNOWN_MODALITIES = List.of("CT", "MR", "XA", "US", "CR", "DX");

    private final ModelGatewayWithFallback gateway;
    private final AiEnrichmentSupport support;
    private final PromptTemplates prompts;
    private final ObjectMapper mapper;

    public FeasibilityScreenerService(ModelGatewayWithFallback gateway,
                                      AiEnrichmentSupport support,
                                      PromptTemplates prompts,
                                      ObjectMapper mapper) {
        if (gateway == null || support == null || prompts == null || mapper == null) {
            throw new IllegalArgumentException("FeasibilityScreenerService 的依赖不能为 null");
        }
        this.gateway = gateway;
        this.support = support;
        this.prompts = prompts;
        this.mapper = mapper;
    }

    /** 初筛（不落库）。 */
    public FeasibilityDraft screen(ExternalStudyMetadata metadata, List<String> localStudyItems) {
        AiResult raw = screenRaw(metadata, localStudyItems);
        return FeasibilityDraft.fromPayload(raw.payload(), mapper, metadata.series());
    }

    /** 初筛并返回带来源标记的原始产出。 */
    public AiResult screenRaw(ExternalStudyMetadata metadata, List<String> localStudyItems) {
        requireMetadata(metadata);
        AiResult result = gateway.invoke(AiResult.KIND_FEASIBILITY_SCREENER,
                prompts.feasibilityMessages(metadata.toView(), localStudyItems),
                FeasibilityDraft.schema(), null, ruleInputs(metadata, localStudyItems));
        LOG.debug("互认初筛完成：studyUid={} source={} decision={}", metadata.studyUid(),
                result.source(), result.payload());
        return result;
    }

    /** 调阅任务 notified 前的增强入口：初筛 + 落 {@code ai_summaries}。 */
    public AiEnrichmentOutcome screenForTask(UUID taskId, ExternalStudyMetadata metadata,
                                             List<String> localStudyItems) {
        requireMetadata(metadata);
        return support.run(AiResult.KIND_FEASIBILITY_SCREENER, taskId,
                prompts.feasibilityMessages(metadata.toView(), localStudyItems),
                FeasibilityDraft.schema(), ruleInputs(metadata, localStudyItems));
    }

    /**
     * 规则兜底输入。
     *
     * <p><b>本院模态怎么来</b>：本院检查项目名里认得出 CT / MR 等就取它；
     * 认不出就<b>留空</b>而不是拿项目名硬凑 —— 让规则判成「模态不一致」，
     * 比猜出一个模态然后判「可互认」安全得多。
     */
    public Map<String, Object> ruleInputs(ExternalStudyMetadata metadata, List<String> localStudyItems) {
        String localItem = localStudyItems == null || localStudyItems.isEmpty()
                ? "" : localStudyItems.get(0);
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put(RuleTemplateEngine.INPUT_EXTERNAL_MODALITY, nvl(metadata.modality()));
        inputs.put(RuleTemplateEngine.INPUT_LOCAL_MODALITY, guessModality(localItem));
        inputs.put(RuleTemplateEngine.INPUT_EXTERNAL_STUDY_ITEM, nvl(metadata.studyItem()));
        inputs.put(RuleTemplateEngine.INPUT_LOCAL_STUDY_ITEM, localItem);
        return inputs;
    }

    /** 从本院检查项目名里认模态；认不出返回空串（判据缺失 → 保守）。 */
    private static String guessModality(String localItem) {
        if (localItem == null) {
            return "";
        }
        String upper = localItem.toUpperCase(Locale.ROOT);
        for (String modality : KNOWN_MODALITIES) {
            if (upper.contains(modality)) {
                return modality;
            }
        }
        return "";
    }

    private static void requireMetadata(ExternalStudyMetadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("互认初筛缺少外院 study 元数据");
        }
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }
}
