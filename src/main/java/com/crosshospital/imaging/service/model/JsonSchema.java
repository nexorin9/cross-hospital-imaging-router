package com.crosshospital.imaging.service.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 期望模型返回的 JSON Schema（OpenAI-compatible {@code response_format.json_schema}）。
 *
 * <p>Schema 本体用 {@link Map} 承载（草案 2020-12 的常用子集：{@code type / required /
 * properties / items / enum}），由 {@link JsonSchemaValidator} 做校验。
 *
 * <p>为什么不引第三方 JSON-Schema 库：AI 层只需要「结构对不对」这一个判断，
 * 拉一个完整校验器进来反而把依赖面撑大；本类支持的子集足以覆盖报告归一 / 互认初筛两个输出形态。
 *
 * @param name   schema 名（部分服务端要求唯一名）
 * @param strict 是否严格模式（要求模型额外补齐所有字段）
 * @param schema schema 本体
 */
public record JsonSchema(String name, boolean strict, Map<String, Object> schema) {

    public JsonSchema {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("schema 名不得为空");
        }
        // 保留插入顺序（Map.copyOf 不保证顺序），也允许 schema 里有 null 值
        schema = schema == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(schema));
    }

    /** 严格模式 + 给定 schema 本体。 */
    public static JsonSchema of(String name, Map<String, Object> schema) {
        return new JsonSchema(name, true, schema);
    }

    /** 非严格模式（模型可只返回必填字段）。 */
    public static JsonSchema lenient(String name, Map<String, Object> schema) {
        return new JsonSchema(name, false, schema);
    }

    /** 包装成 OpenAI-compatible 的 {@code response_format}。 */
    public ResponseFormat toResponseFormat() {
        return ResponseFormat.jsonSchema(this);
    }
}
