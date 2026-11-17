package com.crosshospital.imaging.service.model;

import com.crosshospital.imaging.config.ModelConfigProps;
import com.crosshospital.imaging.exception.RuleTemplateNotFoundException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 27 验收：规则模板引擎。
 *
 * <p><b>要守住的三条线</b>：
 * <ol>
 *   <li><b>不臆造</b> —— 外院没给的内容，兜底文案必须<b>明写</b>「未提供」，
 *       绝不拿别处的值顶替，也绝不把 {@code ${...}} 占位符原样写进给医生看的载荷里；</li>
 *   <li><b>同形状</b> —— 规则兜底产出的载荷必须与模型路径<b>同一个 schema</b>能对上，
 *       否则下游要分叉处理两套结构；</li>
 *   <li><b>配错要早死</b> —— 模板引用了不存在的词典键，必须在<b>启动时</b>失败，
 *       不能等到某天模型挂了才发现兜底也出不来东西。</li>
 * </ol>
 */
class RuleTemplateEngineTest {

    private static final String KIND_REPORT = "report_normalize";
    private static final String KIND_FEASIBILITY = "feasibility_screener";

    private ObjectMapper mapper;
    private RuleTemplateEngine engine;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        engine = new RuleTemplateEngine(new ModelConfigProps(), mapper);
    }

    // ─────────────── 装载 ───────────────

    @Test
    @DisplayName("两个介入点都有模板骨架，词典段在启动时就校验过")
    void loadsBothInterventionKinds() {
        assertEquals(2, engine.supportedKinds().size());
        assertTrue(engine.supports(KIND_REPORT));
        assertTrue(engine.supports(KIND_FEASIBILITY));
        assertFalse(engine.supports("no_such_kind"));
        assertTrue(engine.find(KIND_REPORT).isPresent());
        assertTrue(engine.find("no_such_kind").isEmpty());
    }

    @Test
    @DisplayName("模板引用了词典里不存在的键 → 启动直接失败（不让配置错误拖到生产事故）")
    void templateReferencingMissingRuleFailsFast(@TempDir Path dir) throws IOException {
        Path templates = write(dir, "templates.json", """
                {"templates": {"demo": {"description": "坏模板",
                 "fields": {"x": {"resolver": "mapping", "rule": "no_such_rule", "input": "raw"}}}}}
                """);
        Path rules = write(dir, "rules.yml", """
                model-fallback:
                  demo:
                    real_rule:
                      kind: rule_template
                      mapping:
                        "A": "甲"
                      fallback: "乙"
                """);
        ModelConfigProps props = new ModelConfigProps();
        props.getFallback().setTemplatesPath(rules.toString());
        props.getFallback().setRuleTemplatesPath(templates.toString());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new RuleTemplateEngine(props, mapper));
        assertTrue(ex.getMessage().contains("no_such_rule"), "异常应点名缺失的词典键，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("模板文件缺失 → 启动失败（兜底链路缺一块要立刻知道）")
    void missingTemplateFileFailsFast() {
        ModelConfigProps props = new ModelConfigProps();
        props.getFallback().setRuleTemplatesPath("classpath:hl7/there-is-no-such-file.json");
        assertThrows(IllegalStateException.class, () -> new RuleTemplateEngine(props, mapper));
    }

    @Test
    @DisplayName("未知 kind → RuleTemplateNotFoundException（不是空载荷）")
    void unknownKindIsRejected() {
        assertThrows(RuleTemplateNotFoundException.class,
                () -> engine.readRuleTemplate("no_such_kind", UUID.randomUUID()));
    }

    // ─────────────── 报告归一 ───────────────

    @Test
    @DisplayName("报告归一：模态与原文齐备时按词典渲染")
    void reportNormalizeRendersFromDictionary() throws IOException {
        String payload = engine.readRuleTemplate(KIND_REPORT, UUID.randomUUID(), Map.of(
                RuleTemplateEngine.INPUT_RAW_BODY_PART, "CHEST",
                RuleTemplateEngine.INPUT_RAW_MODALITY, "CT",
                RuleTemplateEngine.INPUT_RAW_FINDINGS, "双肺纹理清晰，未见明显实变",
                RuleTemplateEngine.INPUT_RAW_IMPRESSION, "双肺未见明显异常"));

        JsonNode node = mapper.readTree(payload);
        assertEquals("胸部", node.path("bodyPart").asText());
        assertEquals("双肺纹理清晰，未见明显实变", node.path("findings").asText());
        assertEquals("双肺未见明显异常", node.path("impression").asText());
        assertEquals(1, node.path("studyItems").size());
        assertEquals("CT 平扫", node.path("studyItems").get(0).asText());
    }

    @Test
    @DisplayName("报告归一：外院没给的内容写明『未提供』，不臆造")
    void reportNormalizeUsesFallbackTextWhenInputMissing() throws IOException {
        String payload = engine.readRuleTemplate(KIND_REPORT, UUID.randomUUID(), Map.of());
        JsonNode node = mapper.readTree(payload);

        assertEquals("未明确", node.path("bodyPart").asText());
        assertEquals("外院报告未提供影像所见描述", node.path("findings").asText());
        assertEquals("外院报告未提供诊断意见", node.path("impression").asText());
        assertEquals(1, node.path("studyItems").size());
        assertEquals("外院检查", node.path("studyItems").get(0).asText());
    }

    @Test
    @DisplayName("报告归一：占位符绝不原样出现在载荷里（半截替换等于没替换）")
    void placeholderIsNeverLeftInPayload() throws IOException {
        String payload = engine.readRuleTemplate(KIND_REPORT, UUID.randomUUID(), Map.of(
                RuleTemplateEngine.INPUT_RAW_FINDINGS, "双肺未见明显异常"));
        assertFalse(payload.contains("${"), "载荷里残留了占位符：" + payload);
        assertEquals("外院报告未提供诊断意见", mapper.readTree(payload).path("impression").asText());
        assertEquals("双肺未见明显异常", mapper.readTree(payload).path("findings").asText());
    }

    @Test
    @DisplayName("报告归一：部位写法大小写/空格不敏感（外院写法不统一）")
    void dictionaryLookupIsCaseAndSpaceInsensitive() throws IOException {
        String payload = engine.readRuleTemplate(KIND_REPORT, UUID.randomUUID(), Map.of(
                RuleTemplateEngine.INPUT_RAW_BODY_PART, "  chest "));
        assertEquals("胸部", mapper.readTree(payload).path("bodyPart").asText());
    }

    // ─────────────── 互认初筛 ───────────────

    @Test
    @DisplayName("互认初筛：模态与检查项目都一致 → recognizable 并给出两条判据")
    void feasibilityRecognizableWhenEverythingMatches() throws IOException {
        String payload = engine.readRuleTemplate(KIND_FEASIBILITY, UUID.randomUUID(), Map.of(
                RuleTemplateEngine.INPUT_EXTERNAL_MODALITY, "CT",
                RuleTemplateEngine.INPUT_LOCAL_MODALITY, "CT",
                RuleTemplateEngine.INPUT_EXTERNAL_STUDY_ITEM, "胸部CT平扫",
                RuleTemplateEngine.INPUT_LOCAL_STUDY_ITEM, "胸部CT平扫"));
        JsonNode node = mapper.readTree(payload);

        assertEquals("recognizable", node.path("decision").asText());
        assertTrue(node.path("requiresHumanReview").asBoolean(), "互认结论必须留人工复核位");
        List<String> reasons = mapper.convertValue(node.path("reasons"), new TypeReference<List<String>>() {
        });
        assertEquals(2, reasons.size());
        assertTrue(reasons.contains("外院模态与本院检查项目匹配"), reasons.toString());
        assertTrue(reasons.contains("检查项目名称一致"), reasons.toString());
    }

    @Test
    @DisplayName("互认初筛：模态或检查项目不一致 → not_recommended 并说明差在哪")
    void feasibilityNotRecommendedWhenModalityDiffers() throws IOException {
        String payload = engine.readRuleTemplate(KIND_FEASIBILITY, UUID.randomUUID(), Map.of(
                RuleTemplateEngine.INPUT_EXTERNAL_MODALITY, "MR",
                RuleTemplateEngine.INPUT_LOCAL_MODALITY, "CT",
                RuleTemplateEngine.INPUT_EXTERNAL_STUDY_ITEM, "头颅MR平扫",
                RuleTemplateEngine.INPUT_LOCAL_STUDY_ITEM, "胸部CT平扫"));
        JsonNode node = mapper.readTree(payload);

        assertEquals("not_recommended", node.path("decision").asText());
        List<String> reasons = mapper.convertValue(node.path("reasons"), new TypeReference<List<String>>() {
        });
        assertTrue(reasons.contains("外院模态与本院检查项目不一致，需影像科逐项复核"), reasons.toString());
        assertTrue(reasons.contains("检查项目名称不一致"), reasons.toString());
    }

    @Test
    @DisplayName("互认初筛：关键输入缺失 → 判为 not_recommended（缺证据不往可互认上靠）")
    void feasibilityMissingInputIsNotRecommended() throws IOException {
        String payload = engine.readRuleTemplate(KIND_FEASIBILITY, UUID.randomUUID(), Map.of());
        assertEquals("not_recommended", mapper.readTree(payload).path("decision").asText());
    }

    // ─────────────── 同形状契约 ───────────────

    @Test
    @DisplayName("规则兜底载荷与模型路径同一个 schema 对得上（下游不必分叉处理两套结构）")
    void fallbackPayloadMatchesTheModelSchema() throws IOException {
        // 与模型路径用的 schema 完全一致（task 28 的报告归一 schema）
        JsonSchema schema = JsonSchema.of("normalized_report", Map.of(
                "type", "object",
                "required", List.of("bodyPart", "findings", "impression", "studyItems"),
                "properties", Map.of(
                        "bodyPart", Map.of("type", "string"),
                        "findings", Map.of("type", "string"),
                        "impression", Map.of("type", "string"),
                        "studyItems", Map.of("type", "array", "items", Map.of("type", "string")))));

        String payload = engine.readRuleTemplate(KIND_REPORT, UUID.randomUUID(), Map.of(
                RuleTemplateEngine.INPUT_RAW_BODY_PART, "CHEST",
                RuleTemplateEngine.INPUT_RAW_MODALITY, "CT",
                RuleTemplateEngine.INPUT_RAW_FINDINGS, "双肺纹理清晰",
                RuleTemplateEngine.INPUT_RAW_IMPRESSION, "双肺未见明显异常"));

        List<String> violations = JsonSchemaValidator.validate(schema.schema(), mapper.readTree(payload));
        assertTrue(violations.isEmpty(), "规则兜底载荷与模型 schema 不符：" + violations);
    }

    @Test
    @DisplayName("互认初筛载荷同样过 schema 校验")
    void feasibilityPayloadMatchesSchema() throws IOException {
        JsonSchema schema = JsonSchema.of("feasibility", Map.of(
                "type", "object",
                "required", List.of("decision", "reasons", "requiresHumanReview"),
                "properties", Map.of(
                        "decision", Map.of("type", "string"),
                        "reasons", Map.of("type", "array", "items", Map.of("type", "string")),
                        "requiresHumanReview", Map.of("type", "boolean"))));

        String payload = engine.readRuleTemplate(KIND_FEASIBILITY, UUID.randomUUID(), Map.of(
                RuleTemplateEngine.INPUT_EXTERNAL_MODALITY, "CT",
                RuleTemplateEngine.INPUT_LOCAL_MODALITY, "CT",
                RuleTemplateEngine.INPUT_EXTERNAL_STUDY_ITEM, "胸部CT平扫",
                RuleTemplateEngine.INPUT_LOCAL_STUDY_ITEM, "胸部CT平扫"));

        List<String> violations = JsonSchemaValidator.validate(schema.schema(), mapper.readTree(payload));
        assertTrue(violations.isEmpty(), "规则兜底载荷与模型 schema 不符：" + violations);
    }

    // ─────────────── 磁盘路径装载 ───────────────

    @Test
    @DisplayName("模板与词典也可以放在磁盘上（信息科自行维护词典），且 task_id 能被引用")
    void templatesCanBeLoadedFromDiskAndTaskIdInjected(@TempDir Path dir) throws IOException {
        Path templates = write(dir, "templates.json", """
                {"templates": {"demo": {"description": "演示模板",
                 "fields": {"note": {"resolver": "template", "rule": "note", "input": "raw_note"}}}}}
                """);
        Path rules = write(dir, "rules.yml", """
                model-fallback:
                  demo:
                    note:
                      kind: rule_template
                      template: "任务 ${task_id} 的规则备注：${raw_note}"
                      fallback: "任务 ${task_id} 无备注"
                """);
        ModelConfigProps props = new ModelConfigProps();
        props.getFallback().setTemplatesPath(rules.toString());
        props.getFallback().setRuleTemplatesPath(templates.toString());

        RuleTemplateEngine diskEngine = new RuleTemplateEngine(props, mapper);
        UUID taskId = UUID.randomUUID();
        String payload = diskEngine.readRuleTemplate("demo", taskId, Map.of("raw_note", "外院报告已归一"));
        assertEquals("任务 " + taskId + " 的规则备注：外院报告已归一", mapper.readTree(payload).path("note").asText());

        String noInput = diskEngine.readRuleTemplate("demo", taskId, Map.of());
        assertEquals("任务 " + taskId + " 无备注", mapper.readTree(noInput).path("note").asText());
    }

    private static Path write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
