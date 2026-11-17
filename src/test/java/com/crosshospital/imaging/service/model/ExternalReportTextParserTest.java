package com.crosshospital.imaging.service.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 28 — 规则兜底输入的外院报告分段抽取。 */
@DisplayName("Task 28 — 外院报告分段抽取")
class ExternalReportTextParserTest {

    @Test
    @DisplayName("中文段落标题 → 所见 / 意见分栏，部位与模态归一到词典键")
    void chineseSections() {
        Map<String, Object> inputs = ExternalReportTextParser.ruleInputs("""
                检查部位：胸部
                检查项目：胸部 CT 平扫
                影像所见：双肺纹理清晰，未见异常密度影。
                诊断意见：双肺未见明显异常。
                """);

        assertEquals("CHEST", inputs.get(RuleTemplateEngine.INPUT_RAW_BODY_PART));
        assertEquals("CT", inputs.get(RuleTemplateEngine.INPUT_RAW_MODALITY));
        assertTrue(String.valueOf(inputs.get(RuleTemplateEngine.INPUT_RAW_FINDINGS))
                .contains("双肺纹理清晰"));
        assertEquals("双肺未见明显异常。", inputs.get(RuleTemplateEngine.INPUT_RAW_IMPRESSION));
    }

    @Test
    @DisplayName("外院英文段落标题（FINDINGS / IMPRESSION）同样能切")
    void englishSections() {
        Map<String, Object> inputs = ExternalReportTextParser.ruleInputs("""
                EXAM: ABDOMEN CT
                FINDINGS: no focal lesion.
                IMPRESSION: normal study.
                """);

        assertEquals("ABDOMEN", inputs.get(RuleTemplateEngine.INPUT_RAW_BODY_PART));
        assertEquals("CT", inputs.get(RuleTemplateEngine.INPUT_RAW_MODALITY));
        assertTrue(String.valueOf(inputs.get(RuleTemplateEngine.INPUT_RAW_FINDINGS))
                .contains("no focal lesion"));
        assertEquals("normal study.", inputs.get(RuleTemplateEngine.INPUT_RAW_IMPRESSION));
    }

    @Test
    @DisplayName("无段落标题 → 整篇按所见处理，意见留空交兜底文案")
    void untitledReport() {
        Map<String, Object> inputs = ExternalReportTextParser.ruleInputs("双肺纹理清晰，未见异常。");

        assertTrue(String.valueOf(inputs.get(RuleTemplateEngine.INPUT_RAW_FINDINGS))
                .contains("双肺纹理清晰"));
        assertEquals("", inputs.get(RuleTemplateEngine.INPUT_RAW_IMPRESSION));
    }

    @Test
    @DisplayName("空原文 → 输入表为空（由词典兜底文案接管，不臆造）")
    void blankReport() {
        assertTrue(ExternalReportTextParser.ruleInputs(null).isEmpty());
        assertTrue(ExternalReportTextParser.ruleInputs("   ").isEmpty());
    }
}
