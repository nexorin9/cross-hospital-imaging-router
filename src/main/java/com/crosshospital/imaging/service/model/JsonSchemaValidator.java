package com.crosshospital.imaging.service.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 轻量 JSON Schema 校验器（只覆盖本项目实际用到的子集）。
 *
 * <p>支持的关键字：
 * <ul>
 *   <li>{@code type} —— {@code object / array / string / number / integer / boolean / null}</li>
 *   <li>{@code required} —— 对象必填字段</li>
 *   <li>{@code properties} —— 对象字段类型</li>
 *   <li>{@code items} —— 数组元素类型</li>
 *   <li>{@code enum} —— 取值枚举</li>
 * </ul>
 *
 * <p><b>为什么不用完整校验器</b>：AI 增强层只需要判断「模型返回的结构对不对」，
 * 拉一个完整 JSON-Schema 实现进来会把依赖面与冷启动成本一起撑大。
 * 报告归一（4 个字段）与互认初筛（3 个字段）两个输出形态，用上面这几个关键字已经够用。
 *
 * <p>校验不通过时**不**重试模型：同样的提示 + 同样的模型返回不符合 schema，
 * 第二次多半还是不符合；这种失败应直接走规则模板兜底（task 27）。
 */
public final class JsonSchemaValidator {

    private JsonSchemaValidator() {
    }

    /**
     * 校验实例是否满足 schema。
     *
     * @param schema   schema 本体（{@link JsonSchema#getSchema()}）
     * @param instance 待校验的 JSON 实例
     * @return 违规描述列表；空列表表示通过
     */
    public static List<String> validate(Map<String, Object> schema, JsonNode instance) {
        List<String> violations = new ArrayList<>();
        if (schema == null || schema.isEmpty()) {
            return violations;
        }
        validateNode(schema, instance, "$", violations);
        return violations;
    }

    private static void validateNode(Map<String, Object> schema, JsonNode instance, String path,
                                     List<String> violations) {
        if (schema == null) {
            return;
        }
        Object type = schema.get("type");
        if (type instanceof String typeName && !matchesType(typeName, instance)) {
            violations.add(path + " 类型应为 " + typeName + "，实际为 " + describe(instance));
            // 类型都不对，后面的关键字没有意义，直接返回避免刷屏
            return;
        }

        Object enumValues = schema.get("enum");
        if (enumValues instanceof List<?> allowed) {
            boolean hit = allowed.stream().anyMatch(v -> instance.isValueNode() && nodeEquals(instance, v.toString()));
            if (!hit) {
                violations.add(path + " 取值不在允许集合 " + allowed + " 内");
            }
        }

        if (instance.isObject()) {
            validateObject(schema, instance, path, violations);
        } else if (instance.isArray()) {
            validateArray(schema, instance, path, violations);
        }
    }

    private static void validateObject(Map<String, Object> schema, JsonNode instance, String path,
                                       List<String> violations) {
        Object required = schema.get("required");
        if (required instanceof List<?> requiredFields) {
            for (Object field : requiredFields) {
                String name = String.valueOf(field);
                if (!instance.has(name) || instance.get(name).isNull()) {
                    violations.add(path + " 缺少必填字段 \"" + name + "\"");
                }
            }
        }
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> entry : props.entrySet()) {
                String name = String.valueOf(entry.getKey());
                JsonNode value = instance.get(name);
                if (value == null || value.isNull()) {
                    continue;
                }
                if (entry.getValue() instanceof Map<?, ?> childSchema) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) childSchema;
                    validateNode(typed, value, path + "." + name, violations);
                }
            }
        }
    }

    private static void validateArray(Map<String, Object> schema, JsonNode instance, String path,
                                      List<String> violations) {
        Object items = schema.get("items");
        if (!(items instanceof Map<?, ?> itemSchema)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) itemSchema;
        int index = 0;
        for (JsonNode element : instance) {
            validateNode(typed, element, path + "[" + index + "]", violations);
            index++;
        }
    }

    private static boolean matchesType(String typeName, JsonNode instance) {
        if (instance == null || instance.isMissingNode()) {
            return false;
        }
        return switch (typeName) {
            case "object" -> instance.isObject();
            case "array" -> instance.isArray();
            case "string" -> instance.isTextual();
            case "number" -> instance.isNumber();
            case "integer" -> instance.isIntegralNumber();
            case "boolean" -> instance.isBoolean();
            case "null" -> instance.isNull();
            default -> true; // 未知类型关键字不拦（宁可少拦也不要误杀）
        };
    }

    private static boolean nodeEquals(JsonNode node, String candidate) {
        return node.asText().equals(candidate);
    }

    private static String describe(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "缺失";
        }
        if (node.isNull()) {
            return "null";
        }
        if (node.isTextual()) {
            return "字符串";
        }
        if (node.isNumber()) {
            return "数字";
        }
        if (node.isBoolean()) {
            return "布尔";
        }
        if (node.isArray()) {
            return "数组";
        }
        if (node.isObject()) {
            return "对象";
        }
        return node.getNodeType().toString();
    }

    /** 便于调试：把违规列表拼成一行摘要。 */
    public static String summarize(List<String> violations) {
        if (violations == null || violations.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = violations.iterator();
        while (it.hasNext()) {
            sb.append(it.next());
            if (it.hasNext()) {
                sb.append("；");
            }
        }
        return sb.toString();
    }
}
