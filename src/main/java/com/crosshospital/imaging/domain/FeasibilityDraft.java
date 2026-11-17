package com.crosshospital.imaging.domain;

import com.crosshospital.imaging.service.model.JsonSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外院影像互认可行性初筛草稿（task 29，AI 介入点 2）。
 *
 * <p><b>形状与规则兜底一致</b>：{@link #schema()} 的必填字段
 * （{@code decision / reasons / requiresHumanReview}）与
 * {@code hl7/rule-templates.json} 的 {@code feasibility_screener} 骨架逐项对齐 ——
 * 模型不可达时，规则模板渲染出的载荷能过同一份 schema 校验（task 27 的硬约束）。
 * 两个序列清单是模型路径的<b>可选</b>补充：模型没给时按 decision 从外院序列派生，
 * 避免医生拿到一份「有判据、没对象」的草稿。
 *
 * <p><b>requiresHumanReview 恒为 true</b>：模型即便返回 false 也不采信 ——
 * 互认结论由影像科医生在 PACS 工作站复核后拍板。
 *
 * @param recognizableSeries   初筛建议可互认的序列
 * @param notRecommendedSeries 初筛建议不互认的序列
 * @param reasons              判据说明（模态 / 检查项目是否一致）
 * @param decision             {@code recognizable} / {@code not_recommended}
 * @param requiresHumanReview  恒为 true
 */
public record FeasibilityDraft(List<String> recognizableSeries,
                               List<String> notRecommendedSeries,
                               List<String> reasons,
                               String decision,
                               boolean requiresHumanReview) {

    public static final String SCHEMA_NAME = "feasibility_screener_draft";
    public static final String DECISION_RECOGNIZABLE = "recognizable";
    public static final String DECISION_NOT_RECOMMENDED = "not_recommended";

    public FeasibilityDraft {
        recognizableSeries = recognizableSeries == null ? List.of() : List.copyOf(recognizableSeries);
        notRecommendedSeries = notRecommendedSeries == null ? List.of() : List.copyOf(notRecommendedSeries);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        if (decision == null || decision.isBlank()) {
            decision = DECISION_NOT_RECOMMENDED;
        }
        // 责任边界写进构造器：谁都不能把「必须人工复核」关掉
        requiresHumanReview = true;
    }

    /** 模型路径与规则兜底路径共用的输出结构。 */
    public static JsonSchema schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("decision", Map.of("type", "string"));
        properties.put("reasons", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("requiresHumanReview", Map.of("type", "boolean"));
        properties.put("recognizableSeries", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("notRecommendedSeries", Map.of("type", "array", "items", Map.of("type", "string")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        // 只要求规则兜底一定给得出的三个字段；两个序列清单由服务按 decision 补齐。
        schema.put("required", List.of("decision", "reasons", "requiresHumanReview"));
        schema.put("properties", properties);
        return JsonSchema.of(SCHEMA_NAME, schema);
    }

    /**
     * 解析模型 / 规则兜底载荷。
     *
     * @param externalSeries 外院序列清单（模型没给序列时按 decision 归到其中一边）
     */
    public static FeasibilityDraft fromPayload(String payload, ObjectMapper mapper,
                                               List<String> externalSeries) {
        try {
            JsonNode node = mapper.readTree(payload);
            String decision = node.path("decision").asText(DECISION_NOT_RECOMMENDED);
            List<String> reasons = strings(node.path("reasons"));
            List<String> recognizable = strings(node.path("recognizableSeries"));
            List<String> notRecommended = strings(node.path("notRecommendedSeries"));
            if (recognizable.isEmpty() && notRecommended.isEmpty()) {
                List<String> series = externalSeries == null ? List.of() : externalSeries;
                if (DECISION_RECOGNIZABLE.equals(decision)) {
                    recognizable = series;
                } else {
                    notRecommended = series;
                }
            }
            return new FeasibilityDraft(recognizable, notRecommended, reasons, decision, true);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("互认初筛载荷不是合法 JSON：" + e.getMessage(), e);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(item -> result.add(item.asText()));
        }
        return result;
    }
}
