package com.crosshospital.imaging.service.task;

import com.crosshospital.imaging.domain.AiSummary;
import com.crosshospital.imaging.domain.ExternalStudyMaterial;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.repository.ExternalStudyMaterialRepository;
import com.crosshospital.imaging.support.AiEnrichmentTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Task 28/29 接入主路径：组合端口 + 真实两个介入点服务 + 真实模型降级链路。
 *
 * <p>不 mock 任何具体服务类：两个介入点是否真的跑、两条 {@code ai_summaries}
 * 是否真的落库、模型不可达时是不是真的走了规则兜底，全部由调用计数与落库捕获来证明。
 */
@DisplayName("Task 28/29 — 两个 AI 介入点接入调阅主路径")
class ImagingAiEnrichmentTest {

    /** 同时满足两个 schema 的模型回话（多余字段各自忽略）。 */
    private static final String COMBINED_JSON = """
            {"bodyPart":"胸部","findings":"双肺纹理清晰","impression":"双肺未见明显异常",
             "studyItems":["胸部 CT 平扫"],
             "decision":"recognizable","reasons":["外院模态与本院检查项目匹配"],
             "requiresHumanReview":true}
            """;

    private ExternalStudyMaterialRepository materials;
    private AiEnrichmentTestSupport.Fixture f;
    private ImagingAiEnrichment port;
    private FetchTask task;

    @BeforeEach
    void setUp() {
        f = AiEnrichmentTestSupport.withModelResponse(COMBINED_JSON);
        materials = mock(ExternalStudyMaterialRepository.class);
        port = new ImagingAiEnrichment(materials, f.reports(), f.screener(), f.mapper());
        task = new FetchTask();
        task.setTaskId(UUID.randomUUID());
        task.setStudyUid("1.2.840.EXAMPLE");
    }

    private ExternalStudyMaterial material(String reportText) {
        ExternalStudyMaterial m = new ExternalStudyMaterial();
        m.setStudyUid(task.getStudyUid());
        m.setReportText(reportText);
        m.setModality("CT");
        m.setSeries("[\"SE1 轴位\",\"SE2 冠状位\"]");
        m.setStudyItems("[\"胸部 CT 平扫\"]");
        m.setLocalStudyItems("[\"胸部 CT 平扫\"]");
        return m;
    }

    private List<AiSummary> savedRows(int count) {
        ArgumentCaptor<AiSummary> captor = ArgumentCaptor.forClass(AiSummary.class);
        verify(f.summaries(), times(count)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("资料齐备 → 两次模型介入 + 落两条 ai_summaries（任务行引用主摘要）")
    void bothInterventionsRunWhenMaterialExists() {
        when(materials.findByStudyUid(task.getStudyUid()))
                .thenReturn(Optional.of(material("影像所见：双肺纹理清晰")));

        AiEnrichmentOutcome outcome = port.enrich(task);

        assertTrue(outcome.hasSummary());
        assertEquals("model", outcome.source());
        assertEquals(2, f.gateway().callCount(), "报告归一 + 互认初筛各调一次模型");
        List<AiSummary> rows = savedRows(2);
        Set<String> kinds = rows.stream().map(AiSummary::getKind).collect(Collectors.toSet());
        assertEquals(Set.of("report_normalize", "feasibility_screener"), kinds);
        assertTrue(rows.stream().allMatch(row -> task.getTaskId().equals(row.getTaskId())));
    }

    @Test
    @DisplayName("资料未登记 → 不臆造报告内容，模型与落库零调用")
    void missingMaterialProducesNothing() {
        when(materials.findByStudyUid(task.getStudyUid())).thenReturn(Optional.empty());

        AiEnrichmentOutcome outcome = port.enrich(task);

        assertFalse(outcome.hasSummary());
        assertTrue(outcome.note().contains("未登记"));
        assertEquals(0, f.gateway().callCount());
        verifyNoInteractions(f.summaries());
    }

    @Test
    @DisplayName("外院没给报告原文 → 跳过归一，只落互认初筛摘要")
    void blankReportTextSkipsNormalization() {
        when(materials.findByStudyUid(task.getStudyUid())).thenReturn(Optional.of(material("  ")));

        AiEnrichmentOutcome outcome = port.enrich(task);

        assertTrue(outcome.hasSummary());
        assertEquals(1, f.gateway().callCount());
        List<AiSummary> rows = savedRows(1);
        assertEquals("feasibility_screener", rows.get(0).getKind());
    }

    @Test
    @DisplayName("资料读取异常 → 降级为本次无产出，不冒泡成路由失败")
    void repositoryFailureIsDegraded() {
        when(materials.findByStudyUid(task.getStudyUid()))
                .thenThrow(new IllegalStateException("数据库连接中断"));

        AiEnrichmentOutcome outcome = port.enrich(task);

        assertFalse(outcome.hasSummary());
        assertTrue(outcome.note().contains("数据库连接中断"));
        assertEquals(0, f.gateway().callCount());
    }

    @Test
    @DisplayName("模型不可达 → 两个介入点都走规则兜底，两条摘要均标 rule_fallback")
    void modelUnreachableStillProducesTwoRuleFallbackSummaries() {
        AiEnrichmentTestSupport.Fixture failing = AiEnrichmentTestSupport.withFailingModel();
        ImagingAiEnrichment failingPort =
                new ImagingAiEnrichment(materials, failing.reports(), failing.screener(), failing.mapper());
        when(materials.findByStudyUid(task.getStudyUid()))
                .thenReturn(Optional.of(material(AiEnrichmentTestSupport.SAMPLE_REPORT)));

        AiEnrichmentOutcome outcome = failingPort.enrich(task);

        assertTrue(outcome.hasSummary());
        assertEquals("rule_fallback", outcome.source());
        ArgumentCaptor<AiSummary> captor = ArgumentCaptor.forClass(AiSummary.class);
        verify(failing.summaries(), times(2)).save(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .allMatch(row -> "rule_fallback".equals(row.getSource()) && row.getModelName() == null));
    }
}
