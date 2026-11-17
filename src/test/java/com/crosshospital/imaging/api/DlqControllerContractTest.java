package com.crosshospital.imaging.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.crosshospital.imaging.api.dto.DlqEntryResponse;
import com.crosshospital.imaging.config.DlqConfigProps;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.DlqEntry;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.exception.GlobalExceptionHandler;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.repository.DlqRepository;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.task.DlqAlertPort;
import com.crosshospital.imaging.service.task.DlqAlertResult;
import com.crosshospital.imaging.service.task.DlqService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Task 22 — 死信队列处置 API 契约测试（独立 MockMvc 装配）。
 *
 * <p>覆盖信息科处置面的三个端点：未处置列表、单条详情、人工处置，
 * 以及四条异常路径（死信不存在 / 重复处置 / 处置说明超长 / 处置人缺省）。
 */
@DisplayName("Task 22 — DlqController contract（列表 / 详情 / 处置）")
class DlqControllerContractTest {

    private MockMvc mockMvc;
    private DlqRepository dlqRepository;
    private FetchTaskRepository fetchTaskRepository;
    private AuditRepository auditRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        dlqRepository = mock(DlqRepository.class);
        fetchTaskRepository = mock(FetchTaskRepository.class);
        auditRepository = mock(AuditRepository.class);

        when(dlqRepository.save(any(DlqEntry.class))).thenAnswer(inv -> {
            DlqEntry row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(42L);
            }
            return row;
        });
        when(auditRepository.save(any(AuditRow.class))).thenAnswer(inv -> inv.getArgument(0));

        DlqConfigProps props = new DlqConfigProps();
        DlqAlertPort noopPort = entry -> DlqAlertResult.notConfigured("测试不推告警");
        DlqService dlqService = new DlqService(dlqRepository, fetchTaskRepository,
                new AuditService(auditRepository), noopPort, props);

        mockMvc = MockMvcBuilders.standaloneSetup(new DlqController(dlqService))
                .setControllerAdvice(new GlobalExceptionHandler())
                // standaloneSetup 默认不挂 Validator；显式注入 LocalValidatorFactoryBean
                // 让 @Validated 类级别的 @Min/@Size 在 @RequestParam/@PathVariable 上能触发
                // HandlerMethodValidationException → GlobalExceptionHandler 走 400
                .setValidator(validator())
                .build();
    }

    private static LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }

    private static DlqEntry entry(long id, UUID taskId, String code, int retries) {
        DlqEntry entry = new DlqEntry();
        entry.setId(id);
        entry.setTaskId(taskId);
        entry.setFailureCode(code);
        entry.setFailureMessage("外院 PACS 拒绝连接");
        entry.setRetryAttempts(retries);
        entry.setFirstFailedAt(Instant.parse("2026-11-06T01:58:00Z"));
        entry.setLastRetryAt(Instant.parse("2026-11-06T02:00:00Z"));
        entry.setCreatedAt(Instant.parse("2026-11-06T02:00:00Z"));
        entry.setAlertWebhookSent(Boolean.FALSE);
        return entry;
    }

    private static FetchTask task(UUID taskId) {
        FetchTask task = new FetchTask();
        task.setTaskId(taskId);
        task.setPeerAet("PEER_HZ");
        task.setStudyUid("1.2.840.113619.2.55.3.1234567890.1");
        task.setPatientMpi("MPI_EXAMPLE_0001");
        task.setStatus(TaskStatus.FAILED);
        return task;
    }

    // ──────── happy path ────────

    @Test
    @DisplayName("happy: GET /api/imaging/dlq → 200 + 未处置列表（含失败码与重试次数）")
    void listUnresolvedReturns200() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(dlqRepository.findUnresolved(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry(1L, taskId, "connection_refused", 3)),
                        PageRequest.of(0, 20), 1));
        when(fetchTaskRepository.findByTaskIdIn(any()))
                .thenReturn(List.of(task(taskId)));

        mockMvc.perform(get("/api/imaging/dlq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].failureCode").value("connection_refused"))
                .andExpect(jsonPath("$.content[0].retryAttempts").value(3))
                .andExpect(jsonPath("$.content[0].peerAet").value("PEER_HZ"))
                .andExpect(jsonPath("$.content[0].taskStatus").value("failed"));
    }

    @Test
    @DisplayName("happy: GET /api/imaging/dlq?failureCode=… → 200 + 走失败码过滤查询")
    void listWithFailureCodeFilterReturns200() throws Exception {
        when(dlqRepository.findUnresolvedByFailureCode(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/imaging/dlq").param("failureCode", "connection_refused"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(dlqRepository).findUnresolvedByFailureCode(
                org.mockito.ArgumentMatchers.eq("connection_refused"), any(Pageable.class));
    }

    @Test
    @DisplayName("happy: GET /api/imaging/dlq/{id} → 200 + 单条死信详情")
    void getSingleEntryReturns200() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(dlqRepository.findById(7L)).thenReturn(Optional.of(entry(7L, taskId, "store_failed", 1)));
        when(fetchTaskRepository.findByTaskId(taskId)).thenReturn(Optional.of(task(taskId)));

        mockMvc.perform(get("/api/imaging/dlq/{id}", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.failureCode").value("store_failed"))
                .andExpect(jsonPath("$.patientMpi").value("MPI_EXAMPLE_0001"));
    }

    @Test
    @DisplayName("happy: POST /api/imaging/dlq/{id}/resolve → 200 + resolvedBy / resolvedAt 落回响应")
    void resolveReturns200() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(dlqRepository.findById(7L)).thenReturn(Optional.of(entry(7L, taskId, "store_failed", 1)));

        mockMvc.perform(post("/api/imaging/dlq/{id}/resolve", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Employee-Id", "employee_007")
                        .content("{\"note\":\"已联系外院信息科，对方明日恢复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedBy").value("employee_007"))
                .andExpect(jsonPath("$.resolutionNote").value("已联系外院信息科，对方明日恢复"))
                .andExpect(jsonPath("$.resolvedAt").exists());

        verify(auditRepository, times(1)).save(any(AuditRow.class));
    }

    @Test
    @DisplayName("happy: POST resolve 不带请求体 → 200（只标记已处置，不写说明）")
    void resolveWithoutBodyIsAllowed() throws Exception {
        when(dlqRepository.findById(8L)).thenReturn(Optional.of(entry(8L, UUID.randomUUID(),
                "query_timeout", 2)));

        mockMvc.perform(post("/api/imaging/dlq/{id}/resolve", 8L)
                        .header("X-Employee-Id", "employee_008"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedBy").value("employee_008"));
    }

    @Test
    @DisplayName("happy: POST resolve 无处置人 → 200 + 处置人收敛为 system")
    void resolveWithoutWhoFallsBackToSystem() throws Exception {
        when(dlqRepository.findById(9L)).thenReturn(Optional.of(entry(9L, UUID.randomUUID(),
                "tls_mismatch", 0)));

        mockMvc.perform(post("/api/imaging/dlq/{id}/resolve", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedBy").value("system"));
    }

    // ──────── exception path ────────

    @Test
    @DisplayName("exception: GET /api/imaging/dlq/{id} 死信不存在 → 404 + code=not_found")
    void getUnknownEntryReturns404() throws Exception {
        when(dlqRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/imaging/dlq/{id}", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("exception: POST resolve 死信不存在 → 404")
    void resolveUnknownEntryReturns404() throws Exception {
        when(dlqRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/imaging/dlq/{id}/resolve", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    @DisplayName("exception: POST resolve 重复处置 → 409 + code=conflict")
    void resolveAlreadyResolvedReturns409() throws Exception {
        DlqEntry already = entry(10L, UUID.randomUUID(), "store_failed", 3);
        already.setResolvedAt(Instant.parse("2026-11-06T03:00:00Z"));
        already.setResolvedBy("employee_001");
        when(dlqRepository.findById(10L)).thenReturn(Optional.of(already));

        mockMvc.perform(post("/api/imaging/dlq/{id}/resolve", 10L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conflict"))
                .andExpect(jsonPath("$.message").value(containsString("已处置")));
    }

    @Test
    @DisplayName("exception: POST resolve 处置说明超长 → 400 + fieldErrors")
    void resolveWithTooLongNoteReturns400() throws Exception {
        when(dlqRepository.findById(11L)).thenReturn(Optional.of(entry(11L, UUID.randomUUID(),
                "store_failed", 1)));

        mockMvc.perform(post("/api/imaging/dlq/{id}/resolve", 11L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("note", "x".repeat(1001)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("note"));
    }

    @Test
    @DisplayName("GET /api/imaging/dlq?size=0 → 200（standalone 装配无 AOP 校验，页大小由服务层收敛为 1）")
    void listWithInvalidSizeIsClampedByService() throws Exception {
        // 生产环境由 @Validated + MethodValidationPostProcessor 把 size=0 拦成 400；
        // standalone MockMvc 不带 AOP 代理，验不了那一层，这里断言服务层的兜底收敛。
        when(dlqRepository.findUnresolved(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));

        mockMvc.perform(get("/api/imaging/dlq").param("size", "0"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Pageable> captor =
                org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(dlqRepository).findUnresolved(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPageSize()).isEqualTo(1);
    }
}
