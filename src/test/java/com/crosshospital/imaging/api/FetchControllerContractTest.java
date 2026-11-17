package com.crosshospital.imaging.api;

import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.exception.GlobalExceptionHandler;
import com.crosshospital.imaging.exception.NotFoundException;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.repository.RouteConfigRepository;
import com.crosshospital.imaging.service.routing.PeerRoutingResolver;
import com.crosshospital.imaging.service.task.FetchTaskQueuePort;
import com.crosshospital.imaging.service.task.FetchTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 30 — fetch 入口（202 异步受理）与任务状态查询契约。 */
@DisplayName("Task 30 — FetchController 契约")
class FetchControllerContractTest {

    private MockMvc mvc;
    private FetchTaskRepository tasks;
    private FetchTaskQueuePort queue;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tasks = mock(FetchTaskRepository.class);
        queue = mock(FetchTaskQueuePort.class);
        FetchTaskService service = new FetchTaskService(tasks, queue);
        RouteConfigRepository routeConfig = mock(RouteConfigRepository.class);
        when(routeConfig.findByKey(anyString())).thenReturn(Optional.empty());
        PeerRoutingResolver resolver = new PeerRoutingResolver(
                routeConfig, mock(PeerRepository.class), new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new FetchController(service, resolver))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(tasks.findActiveByPatientMpiAndStudyUid(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(tasks.save(any(FetchTask.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static String body(String patientMpi, String studyUid, String peerAet) {
        return "{\"patientMpi\":\"" + patientMpi + "\",\"studyUid\":\"" + studyUid + "\""
                + (peerAet == null ? "" : ",\"peerAet\":\"" + peerAet + "\"") + "}";
    }

    @Test
    @DisplayName("POST 显式外院节点 → 202 + Location + taskId/status=queued")
    void fetchReturns202() throws Exception {
        mvc.perform(post("/api/imaging/studies/fetch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("HZH^1234567", "1.2.840.EXAMPLE", "PEER_HZ")))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.taskId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.links.self").exists());
        verify(queue).submit(any(UUID.class));
    }

    @Test
    @DisplayName("幂等：同一 (patientMpi, studyUid) 已存在未终结任务 → 复用既有 taskId")
    void fetchIsIdempotent() throws Exception {
        FetchTask existing = new FetchTask();
        UUID existingId = UUID.randomUUID();
        existing.setTaskId(existingId);
        existing.setStatus(TaskStatus.QUEUED);
        when(tasks.findActiveByPatientMpiAndStudyUid("HZH^1234567", "1.2.840.EXAMPLE"))
                .thenReturn(Optional.of(existing));

        mvc.perform(post("/api/imaging/studies/fetch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("HZH^1234567", "1.2.840.EXAMPLE", "PEER_HZ")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value(existingId.toString()));
    }

    @Test
    @DisplayName("缺 patientMpi → 400 + 校验错误字段")
    void missingPatientMpiReturns400() throws Exception {
        mvc.perform(post("/api/imaging/studies/fetch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studyUid\":\"1.2.840.EXAMPLE\",\"peerAet\":\"PEER_HZ\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("省略 peerAet 且主索引无法定位 → 400，不猜节点去连")
    void unresolvedPeerReturns400() throws Exception {
        mvc.perform(post("/api/imaging/studies/fetch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("UNKNOWN^999", "1.2.840.EXAMPLE", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    @DisplayName("GET 任务状态 → 200 + 五态字段 + 失败码")
    void getTaskReturnsStatus() throws Exception {
        FetchTask task = new FetchTask();
        UUID id = UUID.randomUUID();
        task.setTaskId(id);
        task.setStatus(TaskStatus.NOTIFIED);
        task.setRetryCount(1);
        task.setCreatedAt(Instant.parse("2026-10-20T08:31:00Z"));
        task.setUpdatedAt(Instant.parse("2026-10-20T08:34:12Z"));
        when(tasks.findByTaskId(id)).thenReturn(Optional.of(task));

        mvc.perform(get("/api/imaging/tasks/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("notified"))
                .andExpect(jsonPath("$.retryCount").value(1));
    }

    @Test
    @DisplayName("GET 不存在的任务 → 404")
    void getMissingTaskReturns404() throws Exception {
        when(tasks.findByTaskId(any(UUID.class))).thenReturn(Optional.empty());

        mvc.perform(get("/api/imaging/tasks/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    @DisplayName("who 由 X-Employee-Id 透传（入队请求的 initiatedBy）")
    void employeeIdIsForwarded() throws Exception {
        mvc.perform(post("/api/imaging/studies/fetch")
                        .header("X-Employee-Id", "employee_007")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("HZH^1234567", "1.2.840.EXAMPLE", "PEER_HZ")))
                .andExpect(status().isAccepted());
        org.mockito.ArgumentCaptor<FetchTask> captor =
                org.mockito.ArgumentCaptor.forClass(FetchTask.class);
        verify(tasks).save(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("employee_007",
                captor.getValue().getInitiatedBy());
        org.junit.jupiter.api.Assertions.assertEquals("api", captor.getValue().getInitiatedSrc());
    }
}
