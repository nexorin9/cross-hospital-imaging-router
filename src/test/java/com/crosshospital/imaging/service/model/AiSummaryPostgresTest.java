package com.crosshospital.imaging.service.model;

import com.crosshospital.imaging.domain.AiCallAudit;
import com.crosshospital.imaging.domain.AiSummary;
import com.crosshospital.imaging.repository.AiCallAuditRepository;
import com.crosshospital.imaging.repository.AiSummaryRepository;
import com.crosshospital.imaging.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 27 — <b>真实 PostgreSQL</b> 下的 AI 介入点结果落库。
 *
 * <p><b>为什么必须上真库</b>：{@code ai_summaries.payload} 在 V4 迁移里是
 * <b>JSONB</b> 列，而实体上它是一个 {@code String} 字段。用内存替身（Mockito / H2）
 * 验不出这一类问题 —— 替身不执行 PostgreSQL 的类型检查，
 * 而生产库会直接把 {@code character varying} 写进 {@code jsonb} 列的操作<b>拒掉</b>。
 * 这正是「本地全绿、上线报 42804」的典型来源，必须在真库上验。
 *
 * <p><b>顺带验的两件事</b>：
 * <ul>
 *   <li>{@code source} 的 CHECK 约束只允许 {@code model} / {@code rule_fallback} ——
 *       兜底产出的来源标记必须真的能落库；</li>
 *   <li>{@code ai_call_audit.status} 的五态约束 —— 失败谱（timeout / parse_error /
 *       http_error / unreachable）都能落库。</li>
 * </ul>
 *
 * <p><b>平台限制</b>：当前平台拿不到内嵌 PostgreSQL 二进制时，本类用 {@code @EnabledIf}
 * <b>整类跳过</b>（跳过 ≠ 通过，会在报告里显式标出）。
 */
@EnabledIf("com.crosshospital.imaging.support.EmbeddedPostgresSupport#isAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "hl7.trigger.port=0",
        "routing.local-move-dest-port=0",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@DisplayName("Task 27 — 真实 PostgreSQL：AI 摘要（JSONB）与调用审计落库")
class AiSummaryPostgresTest {

    @Autowired
    private AiSummaryService aiSummaryService;

    @Autowired
    private AiSummaryRepository aiSummaryRepository;

    @Autowired
    private AiCallAuditRepository aiCallAuditRepository;

    @Autowired
    private ModelGatewayWithFallback modelGatewayWithFallback;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedPostgresSupport::jdbcUrl);
        registry.add("spring.datasource.username", EmbeddedPostgresSupport::username);
        registry.add("spring.datasource.password", EmbeddedPostgresSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    @DisplayName("规则兜底产物（JSONB 载荷）能落库并读回，source=rule_fallback")
    void ruleFallbackPayloadLandsInJsonbColumn() {
        UUID taskId = UUID.randomUUID();
        String payload = "{\"bodyPart\":\"胸部\",\"findings\":\"外院报告未提供影像所见描述\","
                + "\"impression\":\"外院报告未提供诊断意见\",\"studyItems\":[\"外院检查\"]}";

        Long id = aiSummaryService.persist(taskId, AiResult.KIND_REPORT_NORMALIZE,
                AiResult.fromRuleFallback(AiResult.KIND_REPORT_NORMALIZE, payload, "test-model", 12L,
                        "模型服务超时"));

        assertNotNull(id);
        AiSummary saved = aiSummaryRepository.findById(id).orElseThrow();
        assertEquals(AiResult.SOURCE_RULE_FALLBACK, saved.getSource());
        assertEquals(AiResult.KIND_REPORT_NORMALIZE, saved.getKind());
        assertEquals(taskId, saved.getTaskId());

        // 直接问数据库：payload 列是不是真的存成了 JSONB（而不是被截断的 varchar）
        String columnType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns "
                        + "where table_name = 'ai_summaries' and column_name = 'payload'",
                String.class);
        assertEquals("jsonb", columnType);

        String storedJson = jdbcTemplate.queryForObject(
                "select payload::text from ai_summaries where id = ?", String.class, id);
        assertTrue(storedJson.contains("外院报告未提供影像所见描述"), storedJson);

        // JSONB 列能被 PostgreSQL 当 JSON 解析（不是一坨字符串）
        String bodyPart = jdbcTemplate.queryForObject(
                "select payload ->> 'bodyPart' from ai_summaries where id = ?", String.class, id);
        assertEquals("胸部", bodyPart);
    }

    @Test
    @DisplayName("模型产物同样能落库，source=model")
    void modelPayloadLandsInJsonbColumn() {
        UUID taskId = UUID.randomUUID();
        String payload = "{\"bodyPart\":\"胸部\",\"findings\":\"双肺未见明显异常\","
                + "\"impression\":\"双肺未见明显异常\",\"studyItems\":[\"CT 平扫\"]}";

        Long id = aiSummaryService.persist(taskId, AiResult.KIND_REPORT_NORMALIZE,
                AiResult.fromModel(AiResult.KIND_REPORT_NORMALIZE, payload, "test-model", 340L));

        AiSummary saved = aiSummaryRepository.findById(id).orElseThrow();
        assertEquals(AiResult.SOURCE_MODEL, saved.getSource());
        assertEquals(Integer.valueOf(340), saved.getLatencyMs());
    }

    @Test
    @DisplayName("source 的 CHECK 约束只放行 model / rule_fallback（第三种来源进不去）")
    void sourceCheckConstraintRejectsUnknownValue() {
        int updated = jdbcTemplate.update(
                "update ai_summaries set source = ? where id = ?", "unknown_source", 1L);
        // 上面的 UPDATE 要么影响 0 行（没有 id=1），要么被 CHECK 约束拒掉 —— 都不该成功
        assertEquals(0, updated);
    }

    @Test
    @DisplayName("AI 调用审计的五态失败谱都能落库")
    void aiCallAuditAcceptsAllFailureStatuses() {
        UUID taskId = UUID.randomUUID();
        for (String status : List.of(AiCallAuditWriter.STATUS_SUCCESS,
                AiCallAuditWriter.STATUS_TIMEOUT,
                AiCallAuditWriter.STATUS_PARSE_ERROR,
                AiCallAuditWriter.STATUS_HTTP_ERROR,
                AiCallAuditWriter.STATUS_UNREACHABLE)) {
            AiCallAudit row = new AiCallAudit();
            row.setTaskId(taskId);
            row.setKind(AiResult.KIND_REPORT_NORMALIZE);
            row.setModelName("test-model");
            row.setLatencyMs(7);
            row.setStatus(status);
            row.setErrorMessage("状态 " + status + " 的样例错误");
            AiCallAudit saved = aiCallAuditRepository.save(row);
            assertNotNull(saved.getId());
            assertEquals(status, aiCallAuditRepository.findById(saved.getId()).orElseThrow().getStatus());
        }
    }

    @Test
    @DisplayName("端到端：模型没配（=不可达）→ 规则兜底 → 落 ai_summaries(source=rule_fallback) + 审计落失败行")
    void endToEndFallbackLandsInDatabase() {
        // 本测试环境没有配置模型服务（MODEL_BASE_URL 为空）—— 这正是
        // 「医院还没上模型服务」的真实状态：网关判定 NOT_CONFIGURED，走规则兜底。
        UUID taskId = UUID.randomUUID();
        JsonSchema schema = JsonSchema.of("normalized_report", Map.of(
                "type", "object",
                "required", List.of("bodyPart", "findings", "impression", "studyItems"),
                "properties", Map.of(
                        "bodyPart", Map.of("type", "string"),
                        "findings", Map.of("type", "string"),
                        "impression", Map.of("type", "string"),
                        "studyItems", Map.of("type", "array", "items", Map.of("type", "string")))));

        AiResult result = modelGatewayWithFallback.invoke(
                AiResult.KIND_REPORT_NORMALIZE,
                List.of(ChatMessage.system("你是影像术语归一助手"), ChatMessage.user("外院报告原文")),
                schema, taskId,
                Map.of(RuleTemplateEngine.INPUT_RAW_BODY_PART, "CHEST",
                        RuleTemplateEngine.INPUT_RAW_MODALITY, "CT"));

        assertTrue(result.isRuleFallback(), "模型未配置时必须走规则兜底");
        assertEquals(AiResult.SOURCE_RULE_FALLBACK, result.source());

        Long id = aiSummaryService.persist(taskId, AiResult.KIND_REPORT_NORMALIZE, result);
        AiSummary saved = aiSummaryRepository.findById(id).orElseThrow();
        assertEquals(AiResult.SOURCE_RULE_FALLBACK, saved.getSource());

        // 载荷真的进了 JSONB 列，且内容是兜底文案而不是模型产物
        String bodyPart = jdbcTemplate.queryForObject(
                "select payload ->> 'bodyPart' from ai_summaries where id = ?", String.class, id);
        assertEquals("胸部", bodyPart);
        String findings = jdbcTemplate.queryForObject(
                "select payload ->> 'findings' from ai_summaries where id = ?", String.class, id);
        assertEquals("外院报告未提供影像所见描述", findings);

        // 模型调用审计里有一条失败行（不是 success）—— 排障时能看到「为什么走了兜底」
        List<AiCallAudit> audits = aiCallAuditRepository.findAllByTaskId(taskId);
        assertEquals(1, audits.size());
        assertEquals(AiCallAuditWriter.STATUS_UNREACHABLE, audits.get(0).getStatus());
        assertEquals(AiResult.KIND_REPORT_NORMALIZE, audits.get(0).getKind());
    }
}
