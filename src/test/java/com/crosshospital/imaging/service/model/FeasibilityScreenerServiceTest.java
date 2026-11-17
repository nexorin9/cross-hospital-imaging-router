package com.crosshospital.imaging.service.model;

import com.crosshospital.imaging.domain.AiSummary;
import com.crosshospital.imaging.domain.ExternalStudyMetadata;
import com.crosshospital.imaging.domain.FeasibilityDraft;
import com.crosshospital.imaging.support.AiEnrichmentTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/** Task 29 — 互认可行性初筛：同/异模态、规则降级、人工复核边界。 */
@DisplayName("Task 29 — 互认可行性初筛服务")
class FeasibilityScreenerServiceTest {

    private static final List<String> SERIES = List.of("SE1 轴位", "SE2 冠状位");
    private static final ExternalStudyMetadata METADATA =
            new ExternalStudyMetadata("1.2.840.EXAMPLE", "CT", "胸部 CT 平扫", SERIES);
    private static final List<String> LOCAL_ITEMS = List.of("胸部 CT 平扫");

    private static String modelJson(String decision, boolean humanReview) {
        return "{\"decision\":\"" + decision + "\",\"reasons\":[\"外院模态与本院检查项目匹配\"],"
                + "\"requiresHumanReview\":" + humanReview + "}";
    }

    @Test
    @DisplayName("同模态 + 项目一致 → 初筛可互认，序列归到 recognizableSeries")
    void sameModalityAndItemIsRecognizable() {
        AiEnrichmentTestSupport.Fixture f =
                AiEnrichmentTestSupport.withModelResponse(modelJson("recognizable", true));

        FeasibilityDraft draft = f.screener().screen(METADATA, LOCAL_ITEMS);

        assertEquals("recognizable", draft.decision());
        assertEquals(SERIES, draft.recognizableSeries());
        assertTrue(draft.notRecommendedSeries().isEmpty());
        assertTrue(draft.requiresHumanReview());
        assertEquals(AiResult.SOURCE_MODEL,
                f.screener().screenRaw(METADATA, LOCAL_ITEMS).source());
    }

    @Test
    @DisplayName("不同模态 → 初筛不建议互认，序列归到 notRecommendedSeries")
    void differentModalityIsNotRecommended() {
        AiEnrichmentTestSupport.Fixture f =
                AiEnrichmentTestSupport.withModelResponse(modelJson("not_recommended", true));
        ExternalStudyMetadata mr = new ExternalStudyMetadata("1.2.840.EXAMPLE", "MR", "头颅 MR 平扫", SERIES);

        FeasibilityDraft draft = f.screener().screen(mr, LOCAL_ITEMS);

        assertEquals("not_recommended", draft.decision());
        assertTrue(draft.recognizableSeries().isEmpty());
        assertEquals(SERIES, draft.notRecommendedSeries());
    }

    @Test
    @DisplayName("模型不可达 → 规则按模态匹配兜底，显式标注 rule_fallback")
    void modelUnreachableUsesRuleFallbackByModality() {
        AiEnrichmentTestSupport.Fixture f = AiEnrichmentTestSupport.withFailingModel();

        AiResult matched = f.screener().screenRaw(METADATA, LOCAL_ITEMS);
        FeasibilityDraft matchedDraft = FeasibilityDraft.fromPayload(matched.payload(), f.mapper(), SERIES);
        assertEquals(AiResult.SOURCE_RULE_FALLBACK, matched.source());
        assertEquals("recognizable", matchedDraft.decision());
        assertTrue(matchedDraft.requiresHumanReview());

        ExternalStudyMetadata different =
                new ExternalStudyMetadata("1.2.840.EXAMPLE", "MR", "头颅 MR 平扫", SERIES);
        AiResult unmatched = f.screener().screenRaw(different, LOCAL_ITEMS);
        FeasibilityDraft unmatchedDraft =
                FeasibilityDraft.fromPayload(unmatched.payload(), f.mapper(), SERIES);
        assertEquals("not_recommended", unmatchedDraft.decision(),
                "模态不一致时规则兜底必须判不建议互认");
        assertEquals(SERIES, unmatchedDraft.notRecommendedSeries());
    }

    @Test
    @DisplayName("模型返回 requiresHumanReview=false 也不采信：恒为 true")
    void requiresHumanReviewIsAlwaysTrue() {
        AiEnrichmentTestSupport.Fixture f =
                AiEnrichmentTestSupport.withModelResponse(modelJson("recognizable", false));

        FeasibilityDraft draft = f.screener().screen(METADATA, LOCAL_ITEMS);

        assertTrue(draft.requiresHumanReview(), "互认结论永远由影像科医生复核");
    }

    @Test
    @DisplayName("notified 入口 → 落 ai_summaries(kind=feasibility_screener)")
    void screenForTaskPersistsFeasibilityKind() {
        AiEnrichmentTestSupport.Fixture f =
                AiEnrichmentTestSupport.withModelResponse(modelJson("recognizable", true));

        var outcome = f.screener().screenForTask(UUID.randomUUID(), METADATA, LOCAL_ITEMS);

        assertTrue(outcome.hasSummary());
        ArgumentCaptor<AiSummary> captor = ArgumentCaptor.forClass(AiSummary.class);
        verify(f.summaries()).save(captor.capture());
        AiSummary row = captor.getValue();
        assertEquals(AiResult.KIND_FEASIBILITY_SCREENER, row.getKind());
        assertEquals("model", row.getSource());
    }

    @Test
    @DisplayName("模型不可达且本院项目认不出模态 → 判据缺失往保守一侧倒")
    void missingEvidenceFallsToNotRecommended() {
        AiEnrichmentTestSupport.Fixture f = AiEnrichmentTestSupport.withFailingModel();

        var inputs = f.screener().ruleInputs(METADATA, List.of("本院常规检查"));
        assertEquals("", inputs.get(RuleTemplateEngine.INPUT_LOCAL_MODALITY));

        AiResult result = f.screener().screenRaw(METADATA, List.of("本院常规检查"));
        FeasibilityDraft draft = FeasibilityDraft.fromPayload(result.payload(), f.mapper(), SERIES);
        assertEquals("not_recommended", draft.decision());
        assertEquals(AiResult.SOURCE_RULE_FALLBACK, result.source());
    }

    @Test
    @DisplayName("schema 必填字段与规则兜底骨架一致（模型/兜底同形状）")
    void schemaMatchesRuleFallbackShape() {
        JsonSchema schema = FeasibilityDraft.schema();
        assertEquals(List.of("decision", "reasons", "requiresHumanReview"),
                schema.schema().get("required"));
        assertEquals(FeasibilityDraft.SCHEMA_NAME, schema.name());
    }

    @Test
    @DisplayName("规则兜底落库行不带 model_name（没有模型参与产出）")
    void ruleFallbackSummaryHasNoModelName() {
        AiEnrichmentTestSupport.Fixture f = AiEnrichmentTestSupport.withFailingModel();

        f.screener().screenForTask(UUID.randomUUID(), METADATA, LOCAL_ITEMS);

        ArgumentCaptor<AiSummary> captor = ArgumentCaptor.forClass(AiSummary.class);
        verify(f.summaries()).save(captor.capture());
        assertEquals("rule_fallback", captor.getValue().getSource());
        assertNull(captor.getValue().getModelName());
    }
}
