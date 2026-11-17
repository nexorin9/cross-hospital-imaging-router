package com.crosshospital.imaging.service.model;

import com.crosshospital.imaging.domain.NormalizedReport;
import com.crosshospital.imaging.exception.InvalidReportTextException;
import com.crosshospital.imaging.service.task.AiEnrichmentOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 报告文本归一（task 28，AI 介入点 1）。
 *
 * <p><b>输入 → 输出</b>：{@code studyUid + 外院报告原文} →
 * {@link NormalizedReport}{@code (bodyPart / findings / impression / studyItems)}。
 * 模型路径与规则兜底路径共用 {@link NormalizedReport#schema()}，
 * 两条路径产出的载荷同形状（task 27 的硬约束）。
 *
 * <p><b>两条入口的分工</b>：
 * <ul>
 *   <li>{@link #normalize} —— 给「只想拿归一结果」的调用方，返回结构化对象；</li>
 *   <li>{@link #normalizeForTask} —— 给调阅主路径 notified 前的增强钩子，
 *       走 {@link AiEnrichmentSupport} 落 {@code ai_summaries} 再返回摘要 id。</li>
 * </ul>
 *
 * <p><b>空报告不兜底</b>：外院报告原文为空属于「输入不合法」，不是「模型不可达」。
 * 抛 {@link InvalidReportTextException}，模型与落库<b>都不调用</b> ——
 * 规则模板若在这里凑出一份「未提供…」的草稿，等于凭空造一份报告给医生看。
 *
 * <p><b>AI 失败不算路由失败</b>：模型不可达时由 {@link ModelGatewayWithFallback}
 * 产出 {@code source=rule_fallback} 的同形状载荷；双失败由增强支持降级为「本次无产出」，
 * 不抛给主路径、不推进 PACS 写入、不改任务状态。
 */
@Service
@Profile("!test")
public class ReportNormalizationService {

    private static final Logger LOG = LoggerFactory.getLogger(ReportNormalizationService.class);

    private final ModelGatewayWithFallback gateway;
    private final AiEnrichmentSupport support;
    private final PromptTemplates prompts;
    private final ObjectMapper mapper;

    public ReportNormalizationService(ModelGatewayWithFallback gateway,
                                      AiEnrichmentSupport support,
                                      PromptTemplates prompts,
                                      ObjectMapper mapper) {
        if (gateway == null || support == null || prompts == null || mapper == null) {
            throw new IllegalArgumentException("ReportNormalizationService 的依赖不能为 null");
        }
        this.gateway = gateway;
        this.support = support;
        this.prompts = prompts;
        this.mapper = mapper;
    }

    /** 归一外院报告文本（不落库）。 */
    public NormalizedReport normalize(String studyUid, String externalReportText) {
        return NormalizedReport.fromPayload(normalizeRaw(studyUid, externalReportText).payload(), mapper);
    }

    /**
     * 归一并返回带来源标记的原始产出（{@code model} / {@code rule_fallback}）。
     * 医生与信息科要能分清草稿是模型写的还是规则兜的。
     */
    public AiResult normalizeRaw(String studyUid, String externalReportText) {
        requireReportText(externalReportText);
        List<ChatMessage> messages = prompts.reportMessages(studyUid, externalReportText);
        AiResult result = gateway.invoke(AiResult.KIND_REPORT_NORMALIZE, messages,
                NormalizedReport.schema(), null, ruleInputs(externalReportText));
        LOG.debug("报告归一完成：studyUid={} source={}", studyUid, result.source());
        return result;
    }

    /**
     * 调阅任务 notified 前的增强入口：归一 + 落 {@code ai_summaries}。
     *
     * @return 摘要产出；模型与规则双失败时 {@link AiEnrichmentOutcome#unavailable(String)}
     */
    public AiEnrichmentOutcome normalizeForTask(UUID taskId, String studyUid, String externalReportText) {
        requireReportText(externalReportText);
        return support.run(AiResult.KIND_REPORT_NORMALIZE, taskId,
                prompts.reportMessages(studyUid, externalReportText),
                NormalizedReport.schema(), ruleInputs(externalReportText));
    }

    /** 规则兜底输入：从原文切出所见 / 意见 / 部位 / 模态。 */
    public Map<String, Object> ruleInputs(String externalReportText) {
        return ExternalReportTextParser.ruleInputs(externalReportText);
    }

    private static void requireReportText(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidReportTextException("外院报告原文为空，不做归一（不落 ai_summaries）");
        }
    }
}
