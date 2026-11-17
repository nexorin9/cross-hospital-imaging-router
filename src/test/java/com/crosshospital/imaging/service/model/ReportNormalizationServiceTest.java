package com.crosshospital.imaging.service.model;

import com.crosshospital.imaging.domain.AiSummary;
import com.crosshospital.imaging.domain.NormalizedReport;
import com.crosshospital.imaging.exception.InvalidReportTextException;
import com.crosshospital.imaging.support.AiEnrichmentTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Task 28 — 报告术语归一的模型路径 / 规则兜底路径 / 空报告边界。 */
@DisplayName("Task 28 — 报告文本归一服务")
class ReportNormalizationServiceTest {

    private static final String STUDY_UID = "1.2.840.10008.5.1.4.1.1.2.EXAMPLE";
    private static final String MODEL_JSON = """
            {"bodyPart":"胸部","findings":"双肺纹理清晰","impression":"双肺未见明显异常",
             "studyItems":["胸部 CT 平扫"]}
            """;

    @Test
    @DisplayName("模型返回合法 JSON → 归一为四字段结构，来源标记为 model")
    void modelResponseBecomesNormalizedReport() {
        AiEnrichmentTestSupport.Fixture f = AiEnrichmentTestSupport.withModelResponse(MODEL_JSON);

        NormalizedReport report = f.reports().normalize(STUDY_UID, AiEnrichmentTestSupport.SAMPLE_REPORT);

        assertEquals("胸部", report.bodyPart());
        assertEquals("双肺纹理清晰", report.findings());
        assertEquals("双肺未见明显异常", report.impression());
        assertEquals(List.of("胸部 CT 平扫"), report.studyItems());
        assertEquals(AiResult.SOURCE_MODEL, f.reports().normalizeRaw(STUDY_UID, "外院报告原文").source());
    }

    @Test
    @DisplayName("notified 入口 → 落 ai_summaries(kind=report_normalize, source=model)")
    void normalizeForTaskPersistsModelSummary() {
        AiEnrichmentTestSupport.Fixture f = AiEnrichmentTestSupport.withModelResponse(MODEL_JSON);
        UUID taskId = UUID.randomUUID();

        var outcome = f.reports().normalizeForTask(taskId, STUDY_UID, AiEnrichmentTestSupport.SAMPLE_REPORT);

        assertTrue(outcome.hasSummary());
        assertEquals(AiResult.SOURCE_MODEL, outcome.source());
        ArgumentCaptor<AiSummary> captor = ArgumentCaptor.forClass(AiSummary.class);
        verify(f.summaries()).save(captor.capture());
        AiSummary row = captor.getValue();
        assertEquals(taskId, row.getTaskId());
        assertEquals(AiResult.KIND_REPORT_NORMALIZE, row.getKind());
        assertEquals("model", row.getSource());
        assertEquals("mock-model", row.getModelName());
        assertTrue(row.getPayload().contains("胸部"));
    }

    @Test
    @DisplayName("外院报告为空 → 抛 InvalidReportTextException，模型与落库零调用")
    void emptyReportTextThrowsAndWritesNothing() {
        AiEnrichmentTestSupport.Fixture f = AiEnrichmentTestSupport.withModelResponse(MODEL_JSON);

        assertThrows(InvalidReportTextException.class,
                () -> f.reports().normalize(STUDY_UID, "  "));
        assertThrows(InvalidReportTextException.class,
                () -> f.reports().normalizeForTask(UUID.randomUUID(), STUDY_UID, null));

        assertEquals(0, f.gateway().callCount());
        verifyNoInteractions(f.summaries());
    }

    @Test
    @DisplayName("模型不可达 → 规则模板兜底，落库 source=rule_fallback 且 model_name 为空")
    void modelUnreachableFallsBackAndPersistsRuleFallback() throws Exception {
        AiEnrichmentTestSupport.Fixture f = AiEnrichmentTestSupport.withFailingModel();

        var outcome = f.reports().normalizeForTask(UUID.randomUUID(), STUDY_UID,
                AiEnrichmentTestSupport.SAMPLE_REPORT);

        assertTrue(outcome.hasSummary());
        assertEquals(AiResult.SOURCE_RULE_FALLBACK, outcome.source());
        ArgumentCaptor<AiSummary> captor = ArgumentCaptor.forClass(AiSummary.class);
        verify(f.summaries()).save(captor.capture());
        AiSummary row = captor.getValue();
        assertEquals("rule_fallback", row.getSource());
        assertNull(row.getModelName(), "规则兜底没有模型参与，model_name 必须是 NULL");
        var payload = new ObjectMapper().readTree(row.getPayload());
        assertEquals("胸部", payload.path("bodyPart").asText());
        assertTrue(payload.path("findings").asText().contains("双肺纹理清晰"));
        assertTrue(payload.path("impression").asText().contains("双肺未见明显异常"));
        assertTrue(payload.path("studyItems").toString().contains("CT 平扫"));
        assertFalse(payload.path("findings").asText().contains("${"), "占位符不能出现在给医生看的草稿里");
    }

    @Test
    @DisplayName("规则兜底输入由原文真实切分，不臆造段落")
    void ruleInputsAreParsedFromReportText() {
        AiEnrichmentTestSupport.Fixture f = AiEnrichmentTestSupport.withFailingModel();

        var inputs = f.reports().ruleInputs(AiEnrichmentTestSupport.SAMPLE_REPORT);

        assertEquals("CHEST", inputs.get(RuleTemplateEngine.INPUT_RAW_BODY_PART));
        assertEquals("CT", inputs.get(RuleTemplateEngine.INPUT_RAW_MODALITY));
        assertTrue(String.valueOf(inputs.get(RuleTemplateEngine.INPUT_RAW_FINDINGS)).contains("双肺纹理清晰"));
        assertEquals("双肺未见明显异常。",
                String.valueOf(inputs.get(RuleTemplateEngine.INPUT_RAW_IMPRESSION)));
    }

    @Test
    @DisplayName("提示词写明「不新增医学内容 / 不出诊断结论」，且带上 studyUid")
    void promptsCarrySafetyBoundary() {
        AiEnrichmentTestSupport.Fixture f = AiEnrichmentTestSupport.withModelResponse(MODEL_JSON);
        f.reports().normalize(STUDY_UID, AiEnrichmentTestSupport.SAMPLE_REPORT);

        List<ChatMessage> messages = f.gateway().lastMessages();
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).role());
        assertTrue(messages.get(0).content().contains("不新增"));
        assertTrue(messages.get(0).content().contains("不得给出诊断结论"));
        assertTrue(messages.get(1).content().contains(STUDY_UID));
    }

    @Test
    @DisplayName("schema 名与必填字段锁定模型输出结构")
    void schemaFixesOutputShape() {
        JsonSchema schema = NormalizedReport.schema();
        assertEquals(NormalizedReport.SCHEMA_NAME, schema.name());
        assertTrue(schema.strict());
        assertEquals(List.of("bodyPart", "findings", "impression", "studyItems"),
                schema.schema().get("required"));
    }

    @Test
    @DisplayName("空报告不触发任何网关调用（含 normalizeRaw）")
    void emptyTextDoesNotInvokeGatewayOnRawPath() {
        AiEnrichmentTestSupport.Fixture f = AiEnrichmentTestSupport.withModelResponse(MODEL_JSON);
        assertThrows(InvalidReportTextException.class, () -> f.reports().normalizeRaw(STUDY_UID, ""));
        assertEquals(0, f.gateway().callCount());
    }
}
