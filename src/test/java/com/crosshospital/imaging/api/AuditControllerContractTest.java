package com.crosshospital.imaging.api;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.exception.GlobalExceptionHandler;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.service.AuditQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 31 — 审计只读查询（多条件 / 分页 / 只读 / 不泄露校验和）。 */
@DisplayName("Task 31 — AuditController 契约")
class AuditControllerContractTest {

    private MockMvc mvc;
    private AuditRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(AuditRepository.class);
        AuditQueryService service = new AuditQueryService(repository);
        mvc = MockMvcBuilders.standaloneSetup(new AuditController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        AuditRow row = new AuditRow()
                .withWho("employee_007")
                .withWhenTs(Instant.parse("2026-10-20T08:34:12Z"))
                .withFromAet("IMAGER")
                .withToAet("PEER_HZ")
                .withStudyUid("1.2.840.EXAMPLE")
                .withStatusTerminal("task_notified");
        row.withChecksums(new byte[]{1, 2}, new byte[]{3, 4});
        when(repository.queryByTimeWindow(any(), any(),
                nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class),
                any(Pageable.class)))
                // 生产 AuditQueryService 用 PageRequest.of(...) 返回 paged Page；
        // 这里若用 unpaged PageImpl，Jackson 序列化 Unpaged.getOffset() 会抛 UnsupportedOperationException → 500。
        .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));
    }

    @Test
    @DisplayName("时间窗查询 → 200 + content 含 who / AET / 终态")
    void timeWindowQueryReturnsPage() throws Exception {
        mvc.perform(get("/api/imaging/audit")
                        .param("from", "2026-10-20T00:00:00Z")
                        .param("to", "2026-10-21T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].who").value("employee_007"))
                .andExpect(jsonPath("$.content[0].toAet").value("PEER_HZ"))
                .andExpect(jsonPath("$.content[0].statusTerminal").value("task_notified"));
    }

    @Test
    @DisplayName("响应不暴露 rowChecksum / prevChecksum")
    void checksumsAreNotExposed() throws Exception {
        mvc.perform(get("/api/imaging/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].rowChecksum").doesNotExist())
                .andExpect(jsonPath("$.content[0].prevChecksum").doesNotExist());
    }

    @Test
    @DisplayName("多条件组合（fromAet + statusTerminal + who + page/size）→ 200")
    void combinedFiltersAreAccepted() throws Exception {
        mvc.perform(get("/api/imaging/audit")
                        .param("from", "2026-10-20T00:00:00Z")
                        .param("to", "2026-10-21T00:00:00Z")
                        .param("fromAet", "IMAGER")
                        .param("toAet", "PEER_HZ")
                        .param("statusTerminal", "task_notified")
                        .param("who", "employee_007")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    @DisplayName("非 GET 方法 → 405（审计只读）")
    void nonGetIsRejected() throws Exception {
        mvc.perform(post("/api/imaging/audit"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("size 超过 200 → 400")
    void oversizedPageIsRejected() throws Exception {
        mvc.perform(get("/api/imaging/audit").param("size", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("时间格式非法 → 400（不冒出 500）")
    void invalidTimestampReturns400() throws Exception {
        mvc.perform(get("/api/imaging/audit").param("from", "not-a-timestamp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }
}
