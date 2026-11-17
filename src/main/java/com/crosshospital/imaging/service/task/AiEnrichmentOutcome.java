package com.crosshospital.imaging.service.task;

/**
 * AI 增强的结果（task 27）。
 *
 * <p>三种可能：
 * <ul>
 *   <li><b>模型产出</b> —— {@code summaryId} 有值，{@code source=model}；</li>
 *   <li><b>规则兜底产出</b> —— {@code summaryId} 有值，{@code source=rule_fallback}；</li>
 *   <li><b>没有产出</b> —— {@code summaryId} 为 {@code null}（模型不可达且规则兜底也没有）：</li>
 * </ul>
 *
 * <p><b>第三种情况的语义很重</b>：它表示「这次 AI 介入彻底没结果」。
 * 调用方（{@code FetchTaskService}）据此保持 {@code ai_summary_id} 为空，
 * <b>但照常推进 notified</b> —— 影像已经在本院 PACS 里了，
 * AI 摘要只是给医生的辅助材料，没有它也该让 HIS 知道这次调阅完成了。
 *
 * @param summaryId 落库后的 {@code ai_summaries.id}；无产出时为 {@code null}
 * @param source    来源（{@code model} / {@code rule_fallback}）；无产出时为 {@code null}
 * @param note      失败原因或兜底原因（可空）
 */
public record AiEnrichmentOutcome(Long summaryId, String source, String note) {

    /** 没有产出。 */
    public static AiEnrichmentOutcome unavailable(String note) {
        return new AiEnrichmentOutcome(null, null, note);
    }

    /** 有产出。 */
    public static AiEnrichmentOutcome of(Long summaryId, String source, String note) {
        return new AiEnrichmentOutcome(summaryId, source, note);
    }

    /** 是否产出了可引用的摘要。 */
    public boolean hasSummary() {
        return summaryId != null;
    }
}
