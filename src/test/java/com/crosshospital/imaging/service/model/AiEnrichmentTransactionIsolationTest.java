package com.crosshospital.imaging.service.model;

import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.service.task.AiEnrichmentPort;
import com.crosshospital.imaging.support.EmbeddedPostgresSupport;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Task 27 核查补强：<b>AI 摘要落库失败不得拖垮 notified 主路径的事务</b>。
 *
 * <p><b>为什么这一组用例不能写在 mock 版里</b>：
 * {@code FetchTaskAiEnrichmentTest} 直接 {@code new FetchTaskService(...)}，
 * <b>没有 Spring 代理、没有事务管理器</b> —— {@code @Transactional} 在那里是空注解。
 * 而生产里 {@code advance()} 是走代理的真事务，AI 摘要在<b>同一个事务</b>里落库。
 * 「AI 失败不阻断 notified 推进」这条承诺，只有在<b>真事务</b>里才验得出来；
 * 替身环境里它永远成立，于是这个缺陷在测试里<b>根本不可能出现</b>。
 *
 * <p><b>本类验的是真实形状</b>：真 PostgreSQL（V1–V8 迁移原样执行）+
 * 真 Spring 事务管理器 + 真 {@code AiSummaryService} + 真 {@code FetchTaskService}，
 * 模型服务用 WireMock 起真实 HTTP 端点（真 OkHttp 出站）。
 *
 * <p><b>怎么让「AI 摘要落库」失败</b>：用一个违反 V4 CHECK 约束的 kind
 * （{@code kind IN ('report_normalize','feasibility_screener')}）。
 * 这是真实场景：介入点类型写错 / 新增 kind 忘了同步迁移脚本，
 * 就会在每一次 notified 推进时稳定触发同一条约束冲突。
 */
@EnabledIf("com.crosshospital.imaging.support.EmbeddedPostgresSupport#isAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "hl7.trigger.port=0",
        "routing.local-move-dest-port=0",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@DisplayName("Task 27 核查 — AI 摘要落库失败不拖垮 notified 主路径事务")
class AiEnrichmentTransactionIsolationTest {

    /** 违反 V4 的 kind CHECK 约束 —— 用来稳定地让摘要落库失败。 */
    private static final String BOGUS_KIND = "report_normalize_v2";

    private static final String CHAT_PATH = "/v1/chat/completions";

    /** 与模型路径一致的 schema。 */
    private static final JsonSchema REPORT_SCHEMA = JsonSchema.of("normalized_report", Map.of(
            "type", "object",
            "required", List.of("bodyPart", "findings", "impression", "studyItems"),
            "properties", Map.of(
                    "bodyPart", Map.of("type", "string"),
                    "findings", Map.of("type", "string"),
                    "impression", Map.of("type", "string"),
                    "studyItems", Map.of("type", "array", "items", Map.of("type", "string")))));

    private static final String OK_BODY = """
            {
              "id": "chatcmpl-ok-001",
              "model": "test-model",
              "choices": [
                {"index": 0, "message": {"role": "assistant",
                 "content": "{\\"bodyPart\\":\\"胸部\\",\\"findings\\":\\"双肺未见明显异常\\",\\"impression\\":\\"未见异常\\",\\"studyItems\\":[\\"CT 平扫\\"]}"},
                 "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150}
            }
            """;

    private static WireMockServer server;

    @Autowired
    private com.crosshospital.imaging.service.task.FetchTaskService fetchTaskService;

    @Autowired
    private FetchTaskRepository fetchTaskRepository;

    @Autowired
    private AiEnrichmentSupport aiEnrichmentSupport;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(OK_BODY)));
        registry.add("model.base-url", () -> "http://127.0.0.1:" + server.port());
        registry.add("model.api-key", () -> "test-api-key");
        registry.add("model.model", () -> "test-model");
        registry.add("model.timeout-ms", () -> "2000");
        registry.add("spring.datasource.url", EmbeddedPostgresSupport::jdbcUrl);
        registry.add("spring.datasource.username", EmbeddedPostgresSupport::username);
        registry.add("spring.datasource.password", EmbeddedPostgresSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @BeforeEach
    void clearPort() {
        fetchTaskService.setAiEnrichmentPort(null);
    }

    private FetchTask seedTask(TaskStatus status) {
        FetchTask task = new FetchTask();
        task.setTaskId(UUID.randomUUID());
        task.setPatientMpi("PAT_MPI_EXAMPLE");
        // 幂等唯一索引是 (patient_mpi, study_uid) —— 每个用例必须用不同的 study_uid
        task.setStudyUid("STUDY_EXAMPLE_" + status.toDbValue() + "_" + UUID.randomUUID());
        task.setPeerAet("PEER_HZ");
        task.setAccessionNo("ACC_EXAMPLE");
        task.setStatus(status);
        task.setRetryCount(0);
        task.setInitiatedBy("employee_007");
        task.setInitiatedSrc("api");
        return fetchTaskRepository.saveAndFlush(task);
    }

    /**
     * 端口实现与 task 28/29 将要写的实现同形：
     * 调模型 → 落库 → 把「有没有产出」交回主路径。
     */
    private AiEnrichmentPort portCallingRealSupport() {
        return task -> aiEnrichmentSupport.run(
                BOGUS_KIND,
                task.getTaskId(),
                List.of(ChatMessage.system("你是影像术语归一助手"), ChatMessage.user("外院报告原文")),
                REPORT_SCHEMA,
                Map.of(RuleTemplateEngine.INPUT_RAW_BODY_PART, "CHEST",
                        RuleTemplateEngine.INPUT_RAW_MODALITY, "CT"));
    }

    @Test
    @DisplayName("AI 摘要落库失败（约束冲突）→ notified 照常推进，审计行照常落库")
    void aiSummaryPersistFailureDoesNotBreakNotifiedTransaction() {
        FetchTask task = seedTask(TaskStatus.STORED);
        fetchTaskService.setAiEnrichmentPort(portCallingRealSupport());

        FetchTask after = assertDoesNotThrow(
                () -> fetchTaskService.advance(task.getTaskId(), TaskStatus.NOTIFIED, "employee_007"),
                "AI 摘要落库失败不得让 notified 推进失败 —— 增强层不是主路径的前置条件");

        assertEquals(TaskStatus.NOTIFIED, after.getStatus());
        assertNull(after.getAiSummaryId(), "摘要没落成就不该回填 id");

        FetchTask reloaded = fetchTaskRepository.findByTaskId(task.getTaskId()).orElseThrow();
        assertEquals(TaskStatus.NOTIFIED, reloaded.getStatus(), "数据库里的状态必须真的落到 notified");

        Integer notifiedRows = jdbcTemplate.queryForObject(
                "select count(*) from audit_imaging_fetch where status_terminal = 'task_notified' and study_uid = ?",
                Integer.class, task.getStudyUid());
        assertEquals(1, notifiedRows, "notified 的审计行必须落库（审计写失败会被吞掉，但事务不能因此废掉）");
    }

    @Test
    @DisplayName("AI 摘要落库失败 → 主路径事务仍然可用（没有 'current transaction is aborted'）")
    void aiSummaryPersistFailureLeavesOuterTransactionUsable() {
        FetchTask task = seedTask(TaskStatus.STORED);
        fetchTaskService.setAiEnrichmentPort(portCallingRealSupport());

        assertDoesNotThrow(() -> fetchTaskService.advance(task.getTaskId(), TaskStatus.NOTIFIED, "employee_007"));

        // 主路径的事务必须还能正常写库（不是被摘要那次失败拖成 aborted 状态）
        Integer probe = jdbcTemplate.queryForObject(
                "select count(*) from imaging_fetch_tasks where task_id = ? and status = 'notified'",
                Integer.class, task.getTaskId());
        assertEquals(1, probe, "任务行必须留在 notified");

        Integer auditRows = jdbcTemplate.queryForObject(
                "select count(*) from audit_imaging_fetch where study_uid = ?", Integer.class, task.getStudyUid());
        assertEquals(1, auditRows, "本次推进只应落一行 notified 审计");
    }
}
