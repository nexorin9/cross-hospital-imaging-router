package com.crosshospital.imaging.service.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 26 配套：轻量 JSON Schema 校验器的行为边界。
 *
 * <p>这层校验是「模型输出能不能直接落库」的闸门 —— 报告归一与互认初筛的结构化输出
 * 都要先过这一关，所以它的宽松 / 严格程度必须被钉死，不能含糊。
 */
class JsonSchemaValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "required", List.of("bodyPart", "findings"),
            "properties", Map.of(
                    "bodyPart", Map.of("type", "string"),
                    "findings", Map.of("type", "string"),
                    "studyItems", Map.of("type", "array", "items", Map.of("type", "string"))));

    @Test
    @DisplayName("符合 schema → 无违规")
    void compliantInstancePasses() throws Exception {
        var instance = MAPPER.readTree("{\"bodyPart\":\"胸部\",\"findings\":\"无明显异常\"}");

        assertTrue(JsonSchemaValidator.validate(SCHEMA, instance).isEmpty());
    }

    @Test
    @DisplayName("缺必填字段 → 违规点名该字段")
    void missingRequiredFieldIsReported() throws Exception {
        var instance = MAPPER.readTree("{\"bodyPart\":\"胸部\"}");

        List<String> violations = JsonSchemaValidator.validate(SCHEMA, instance);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("findings"), violations.toString());
    }

    @Test
    @DisplayName("必填字段显式为 null 也算缺（模型给了 null 等于没给）")
    void nullRequiredFieldCountsAsMissing() throws Exception {
        var instance = MAPPER.readTree("{\"bodyPart\":\"胸部\",\"findings\":null}");

        List<String> violations = JsonSchemaValidator.validate(SCHEMA, instance);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("findings"));
    }

    @Test
    @DisplayName("字段类型不符 → 违规说明期望类型与实际类型")
    void wrongFieldTypeIsReported() throws Exception {
        var instance = MAPPER.readTree("{\"bodyPart\":123,\"findings\":\"无明显异常\"}");

        List<String> violations = JsonSchemaValidator.validate(SCHEMA, instance);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("string"), violations.toString());
        assertTrue(violations.get(0).contains("数字"), violations.toString());
    }

    @Test
    @DisplayName("数组元素类型不符 → 违规带上数组下标")
    void arrayItemTypeIsChecked() throws Exception {
        var instance = MAPPER.readTree(
                "{\"bodyPart\":\"胸部\",\"findings\":\"无\",\"studyItems\":[\"CT\",7]}");

        List<String> violations = JsonSchemaValidator.validate(SCHEMA, instance);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("[1]"), violations.toString());
    }

    @Test
    @DisplayName("enum 取值不在允许集合 → 违规")
    void enumViolationIsReported() throws Exception {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("status", Map.of("type", "string",
                        "enum", List.of("stored", "notified"))));

        var instance = MAPPER.readTree("{\"status\":\"failed\"}");

        List<String> violations = JsonSchemaValidator.validate(schema, instance);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("enum") || violations.get(0).contains("允许集合"),
                violations.toString());
    }

    @Test
    @DisplayName("顶层类型不符 → 只报一条，不继续往下刷字段违规")
    void topLevelTypeMismatchStopsDescent() throws Exception {
        var instance = MAPPER.readTree("[1,2,3]");

        List<String> violations = JsonSchemaValidator.validate(SCHEMA, instance);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("object"), violations.toString());
    }

    @Test
    @DisplayName("空 schema → 不拦（没有约束就不制造假阳性）")
    void emptySchemaPassesEverything() throws Exception {
        var instance = MAPPER.readTree("{\"随便\":1}");

        assertTrue(JsonSchemaValidator.validate(Map.of(), instance).isEmpty());
        assertTrue(JsonSchemaValidator.validate(null, instance).isEmpty());
    }

    @Test
    @DisplayName("summarize 把多条违规拼成一行摘要")
    void summarizeJoinsViolations() {
        String summary = JsonSchemaValidator.summarize(List.of("甲", "乙"));

        assertEquals("甲；乙", summary);
        assertEquals("", JsonSchemaValidator.summarize(List.of()));
    }
}
