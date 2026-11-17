package com.crosshospital.imaging.service.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条规则模板（按介入点类型 {@code kind} 索引）。
 *
 * <p>模板本身只描述「输出载荷的每个字段怎么来」：
 * <ul>
 *   <li>{@link FieldDirective#resolver()} —— 用哪个解析器
 *       （{@code mapping} / {@code template} / {@code mapping_list} /
 *       {@code feasibility_decision} / {@code feasibility_reasons} / {@code constant}）；</li>
 *   <li>{@link FieldDirective#rule()} —— 词典在 {@code segment-mappings.yml} 里的哪一段
 *       （{@code model-fallback.<kind>.<rule>}）；</li>
 *   <li>{@link FieldDirective#input()} —— 从哪个输入键取值；</li>
 *   <li>{@link FieldDirective#constant()} —— 常量字段的值。</li>
 * </ul>
 *
 * <p>词典（模态→部位映射、兜底文案、互认判据）不放这里 —— 它们是<b>可被信息科直接改</b>的
 * 业务配置，放在 YAML 里；本类只管「怎么把词典变成载荷」。
 *
 * @param kind        介入点类型（{@code report_normalize} / {@code feasibility_screener}）
 * @param description 模板用途说明（运维在日志与文档里看得到）
 * @param fields      输出字段 → 渲染指令（保持声明顺序，渲染出的 JSON 字段序稳定）
 */
public record RuleTemplate(String kind, String description, Map<String, FieldDirective> fields) {

    public RuleTemplate {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("规则模板缺少 kind");
        }
        // 保持声明顺序：渲染出的 JSON 字段序稳定，便于人肉比对与快照测试
        fields = fields == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /**
     * 单个输出字段的渲染指令。
     *
     * @param resolver 解析器名
     * @param rule     {@code segment-mappings.yml} 里的词典键
     * @param input    输入键名（从调用方传入的 inputs 里取）
     * @param constant 常量值（仅 {@code constant} 解析器使用）
     */
    public record FieldDirective(String resolver, String rule, String input, Object constant) {

        /** 需要从输入取值的指令。 */
        public static FieldDirective of(String resolver, String rule, String input) {
            return new FieldDirective(resolver, rule, input, null);
        }

        /** 常量指令。 */
        public static FieldDirective constant(Object value) {
            return new FieldDirective("constant", null, null, value);
        }
    }
}
