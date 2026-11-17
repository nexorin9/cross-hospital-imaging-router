package com.crosshospital.imaging.domain;

import com.crosshospital.imaging.service.model.JsonSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外院报告术语归一结果（task 28，AI 介入点 1）。
 *
 * <p><b>四个字段就是规则兜底骨架里声明的四个字段</b>（{@code hl7/rule-templates.json}
 * 的 {@code report_normalize}）。模型路径与规则兜底路径产出<b>同一形状</b>，
 * 医生在 PACS 侧看到的草稿不需要分叉解析 —— 这是 task 27 定下的硬约束，
 * 这里用一份静态 schema 把它钉死：模型回话校验与兜底产物校验读同一份定义。
 *
 * <p><b>它只是草稿</b>：归一结果不回写 PACS 报告，互认结论由影像科医生复核。
 *
 * @param bodyPart    部位（本院习惯用法）
 * @param findings    影像所见
 * @param impression  诊断意见
 * @param studyItems  检查项目名列表
 */
public record NormalizedReport(String bodyPart,
                               String findings,
                               String impression,
                               List<String> studyItems) {

    /** schema 名（OpenAI-compatible {@code response_format.json_schema.name}）。 */
    public static final String SCHEMA_NAME = "normalized_report";

    /** 期望输出结构：两条路径共用。 */
    public static JsonSchema schema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("bodyPart", "findings", "impression", "studyItems"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("bodyPart", Map.of("type", "string"));
        properties.put("findings", Map.of("type", "string"));
        properties.put("impression", Map.of("type", "string"));
        properties.put("studyItems", Map.of("type", "array", "items", Map.of("type", "string")));
        schema.put("properties", properties);
        return JsonSchema.of(SCHEMA_NAME, schema);
    }

    /**
     * 把模型回话 / 规则兜底载荷解析成结果对象。
     *
     * <p>缺字段不抛异常、也不臆造内容：缺什么就留空字符串 / 空列表，
     * 与词典里的「外院报告未提供…」兜底文案是同一套口径。
     */
    public static NormalizedReport fromPayload(String payload, ObjectMapper mapper) {
        try {
            JsonNode node = mapper.readTree(payload);
            List<String> items = new ArrayList<>();
            JsonNode itemsNode = node.path("studyItems");
            if (itemsNode.isArray()) {
                itemsNode.forEach(item -> items.add(item.asText()));
            }
            return new NormalizedReport(
                    node.path("bodyPart").asText(""),
                    node.path("findings").asText(""),
                    node.path("impression").asText(""),
                    List.copyOf(items));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("归一结果载荷不是合法 JSON：" + e.getMessage(), e);
        }
    }
}
