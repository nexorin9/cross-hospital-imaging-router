package com.crosshospital.imaging.service.model;

import com.crosshospital.imaging.service.task.AiEnrichmentOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 增强的通用执行器（task 27）。
 *
 * <p>把「跑一次介入点」的固定动作收在一处，让每个介入点（task 28 报告归一、
 * task 29 互认初筛）只需要写自己的提示词与规则输入，不必各自重写一遍
 * 「模型 → 兜底 → 落库 → 失败降级」这套流程 —— 少写一遍就少错一遍。
 *
 * <p><b>失败一律降级，不阻断</b>：模型不可达、规则模板缺失、落库失败 ——
 * 三种失败都只让本次 AI 介入「没有产出」，返回
 * {@link AiEnrichmentOutcome#unavailable(String)}。
 * 跨院调阅主路径不受影响：影像已经在本院 PACS 里，AI 摘要只是给医生的辅助材料。
 *
 * <p><b>不推进任何业务状态</b>：本类只写 {@code ai_summaries}，
 * 不写 PACS、不推 HIS、不改任务状态。
 */
@Service
@Profile("!test")
public class AiEnrichmentSupport {

    private static final Logger LOG = LoggerFactory.getLogger(AiEnrichmentSupport.class);

    private final ModelGatewayWithFallback gateway;
    private final AiSummaryService summaries;

    public AiEnrichmentSupport(ModelGatewayWithFallback gateway, AiSummaryService summaries) {
        if (gateway == null || summaries == null) {
            throw new IllegalArgumentException("AiEnrichmentSupport 的依赖不能为 null");
        }
        this.gateway = gateway;
        this.summaries = summaries;
    }

    /**
     * 跑一次介入点并落库。
     *
     * @param kind       介入点类型
     * @param taskId     任务 ID
     * @param messages   提示词（模型路径）
     * @param schema     期望输出结构（两条路径都校验）
     * @param ruleInputs 规则模板输入
     * @return 产出结果；失败时为 {@link AiEnrichmentOutcome#unavailable(String)}
     */
    public AiEnrichmentOutcome run(String kind, UUID taskId, List<ChatMessage> messages,
                                   JsonSchema schema, Map<String, Object> ruleInputs) {
        AiResult result;
        try {
            result = gateway.invoke(kind, messages, schema, taskId, ruleInputs);
        } catch (RuntimeException ex) {
            LOG.warn("AI 介入点失败（模型与规则兜底都不可用，本次不产出摘要）：kind={} taskId={} msg={}",
                    kind, taskId, ex.getMessage());
            return AiEnrichmentOutcome.unavailable(ex.getMessage());
        }
        try {
            Long summaryId = summaries.persist(taskId, kind, result);
            return AiEnrichmentOutcome.of(summaryId, result.source(), result.fallbackReason());
        } catch (RuntimeException ex) {
            LOG.error("AI 摘要落库失败（已降级为本次不产出，不影响主路径）：kind={} taskId={} msg={}",
                    kind, taskId, ex.getMessage());
            return AiEnrichmentOutcome.unavailable("摘要落库失败：" + ex.getMessage());
        }
    }
}
