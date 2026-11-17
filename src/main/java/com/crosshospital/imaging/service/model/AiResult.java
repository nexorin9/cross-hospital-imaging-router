package com.crosshospital.imaging.service.model;

/**
 * 一次 AI 介入点的产出 —— 无论来自模型还是规则模板，都用这一个类型承载。
 *
 * <p><b>为什么两条路径要合成一个类型</b>：下游（落 {@code ai_summaries}、写进 PACS 侧
 * 草稿、给医生复核）只该认一种结构。模型不可达时如果抛异常让调用方自己分支，
 * 每接一个介入点就会多一处「忘了判 source」的漏洞；合成一个类型后，
 * 「来源是什么」变成结果上的一个字段，必须显式带出来。
 *
 * <p><b>两条路径的差别只有三处</b>：
 * <ul>
 *   <li>{@link #source()} —— {@link #SOURCE_MODEL}（模型写的）或
 *       {@link #SOURCE_RULE_FALLBACK}（规则模板兜的），与 {@code ai_summaries.source}
 *       的 CHECK 约束一一对应；</li>
 *   <li>{@link #fallbackReason()} —— 规则兜底时说明「模型为什么没成」，
 *       模型路径恒为 {@code null}；</li>
 *   <li>{@link #requiresHumanReview()} —— <b>恒为 true</b>。</li>
 * </ul>
 *
 * <p><b>关于 {@link #requiresHumanReview()} 恒为 true</b>：AI 输出在本产品里<b>永远</b>只是草稿 ——
 * 互认结论由影像科医生在 PACS 工作站逐项复核后拍板，模型与规则模板都无权代替。
 * 把它写成一个恒真的字段不是冗余，而是把这条责任边界<b>写进代码</b>：
 * 将来谁想在这里塞 {@code false}，编译不过、review 也躲不掉。
 *
 * @param kind              介入点类型（{@code report_normalize} / {@code feasibility_screener}）
 * @param payload           结果载荷（JSON 文本；与模型路径同形状）
 * @param source            来源标记（{@link #SOURCE_MODEL} / {@link #SOURCE_RULE_FALLBACK}）
 * @param modelName         <b>实际产出所用的模型名</b>；规则兜底路径恒为 {@code null}
 *                          （没有模型参与产出，V4 迁移的列注释也是这么约定的）
 * @param latencyMs         本次耗时（毫秒；模型路径是网关耗时，兜底路径是「模型尝试 + 模板渲染」总耗时）
 * @param fallbackReason    规则兜底原因；模型路径恒为 {@code null}
 * @param requiresHumanReview 是否必须人工复核（本产品恒为 {@code true}）
 */
public record AiResult(String kind,
                        String payload,
                        String source,
                        String modelName,
                        long latencyMs,
                        String fallbackReason,
                        boolean requiresHumanReview) {

    /** 来源：模型产出。 */
    public static final String SOURCE_MODEL = "model";

    /** 来源：规则模板兜底产出。 */
    public static final String SOURCE_RULE_FALLBACK = "rule_fallback";

    /** 介入点：外院报告术语归一。 */
    public static final String KIND_REPORT_NORMALIZE = "report_normalize";

    /** 介入点：跨院影像互认可行性初筛。 */
    public static final String KIND_FEASIBILITY_SCREENER = "feasibility_screener";

    public AiResult {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("AI 结果缺少 kind");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("AI 结果缺少 payload");
        }
        if (!SOURCE_MODEL.equals(source) && !SOURCE_RULE_FALLBACK.equals(source)) {
            throw new IllegalArgumentException(
                    "AI 结果 source 只能是 " + SOURCE_MODEL + " / " + SOURCE_RULE_FALLBACK + "，实际：" + source);
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("AI 结果 latencyMs 不得为负数");
        }
    }

    /** 模型路径的产出。 */
    public static AiResult fromModel(String kind, String payload, String modelName, long latencyMs) {
        return new AiResult(kind, payload, SOURCE_MODEL, modelName, latencyMs, null, true);
    }

    /** 规则模板兜底路径的产出。 */
    public static AiResult fromRuleFallback(String kind, String payload, String modelName,
                                             long latencyMs, String fallbackReason) {
        return new AiResult(kind, payload, SOURCE_RULE_FALLBACK, modelName, latencyMs,
                fallbackReason == null || fallbackReason.isBlank() ? "模型不可达（未给出具体原因）" : fallbackReason,
                true);
    }

    /** 是否为规则兜底产出。 */
    public boolean isRuleFallback() {
        return SOURCE_RULE_FALLBACK.equals(source);
    }

    /** 是否为模型产出。 */
    public boolean isModel() {
        return SOURCE_MODEL.equals(source);
    }
}
