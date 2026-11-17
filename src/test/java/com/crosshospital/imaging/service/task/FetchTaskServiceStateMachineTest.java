package com.crosshospital.imaging.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.exception.IllegalTaskTransitionException;
import com.crosshospital.imaging.exception.NotFoundException;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.routing.DlqEnqueuePort;
import com.crosshospital.imaging.service.routing.DlqEnqueueRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Task 20 — FetchTaskService 五态机单元测试（Mockito 替身，不连数据库）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>合法跃迁持久化 + 每态落一行审计；</li>
 *   <li>非法跃迁被拒且状态不变；</li>
 *   <li>同态跃迁幂等（不重复落审计、不重复跑子步）；</li>
 *   <li>状态推进按目标状态触发协议子步，产出回写任务行；</li>
 *   <li>子步失败 → 任务转 failed + 落失败码 + 落失败审计 + 进死信；</li>
 *   <li>{@code markFailed}（失败出口端口）只推终态不重复进死信；</li>
 *   <li>已通知的任务拒绝翻成失败；</li>
 *   <li>幂等去重（同一 patient_mpi + study_uid 只入队一次）与终值可查。</li>
 * </ul>
 */
@DisplayName("Task 20 — FetchTaskService 五态机推进")
class FetchTaskServiceStateMachineTest {

    private FetchTaskRepository repository;
    private AuditRepository auditRepository;
    private AuditService auditService;
    private DlqEnqueuePort dlqPort;
    private FetchTaskService service;
    private Map<UUID, FetchTask> store;

    @BeforeEach
    void setUp() {
        repository = mock(FetchTaskRepository.class);
        auditRepository = mock(AuditRepository.class);
        auditService = new AuditService(auditRepository);
        dlqPort = mock(DlqEnqueuePort.class);
        service = new FetchTaskService(repository, new InMemoryFetchTaskQueue(16), auditService);
        service.setDlqEnqueuePort(dlqPort);
        store = new HashMap<>();

        when(repository.save(any(FetchTask.class))).thenAnswer(inv -> {
            FetchTask task = inv.getArgument(0);
            store.put(task.getTaskId(), task);
            return task;
        });
        when(repository.findByTaskId(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(store.get((UUID) inv.getArgument(0))));
        when(repository.findActiveByPatientMpiAndStudyUid(anyString(), anyString()))
                .thenAnswer(inv -> store.values().stream()
                        .filter(t -> t.getPatientMpi().equals(inv.getArgument(0))
                                && t.getStudyUid().equals(inv.getArgument(1))
                                && t.getStatus().isIdempotentBlock())
                        .findFirst());
        when(repository.findByPatientMpiAndStudyUidOrderByCreatedAtDesc(anyString(), anyString()))
                .thenAnswer(inv -> {
                    List<FetchTask> out = new ArrayList<>();
                    for (FetchTask t : store.values()) {
                        if (t.getPatientMpi().equals(inv.getArgument(0))
                                && t.getStudyUid().equals(inv.getArgument(1))) {
                            out.add(t);
                        }
                    }
                    return out;
                });
        when(auditRepository.save(any(AuditRow.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ──────── 合法跃迁 ────────

    @Test
    @DisplayName("queued → querying：状态落库 + 落一行 task_querying 审计")
    void legalAdvancePersistsStateAndAudit() {
        FetchTask task = seedTask(TaskStatus.QUEUED);

        FetchTask advanced = service.advance(task.getTaskId(), TaskStatus.QUERYING, "employee_007");

        assertEquals(TaskStatus.QUERYING, advanced.getStatus());
        assertEquals(TaskStatus.QUERYING, store.get(task.getTaskId()).getStatus());

        ArgumentCaptor<AuditRow> captor = ArgumentCaptor.forClass(AuditRow.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("task_querying", captor.getValue().getStatusTerminal());
        assertEquals("employee_007", captor.getValue().getWho());
        assertNull(captor.getValue().getFailureCode());
    }

    @Test
    @DisplayName("五态主链 queued→querying→pulling→stored→notified 逐态可推")
    void fullChainAdvances() {
        FetchTask task = seedTask(TaskStatus.QUEUED);

        service.advance(task.getTaskId(), TaskStatus.QUERYING);
        service.advance(task.getTaskId(), TaskStatus.PULLING);
        service.advance(task.getTaskId(), TaskStatus.STORED);
        FetchTask notified = service.advance(task.getTaskId(), TaskStatus.NOTIFIED);

        assertEquals(TaskStatus.NOTIFIED, notified.getStatus());
        assertTrue(service.isTerminal(task.getTaskId()));
    }

    @Test
    @DisplayName("非法跃迁被拒：状态不变、不落审计")
    void illegalAdvanceRejected() {
        FetchTask task = seedTask(TaskStatus.STORED);

        assertThrows(IllegalTaskTransitionException.class,
                () -> service.advance(task.getTaskId(), TaskStatus.QUERYING));

        assertEquals(TaskStatus.STORED, store.get(task.getTaskId()).getStatus());
        verify(auditRepository, never()).save(any(AuditRow.class));
    }

    @Test
    @DisplayName("同态跃迁幂等：不报错、不重复落审计、不重复跑子步")
    void sameStateAdvanceIsIdempotent() {
        FetchTask task = seedTask(TaskStatus.QUERYING);
        FetchTaskStepExecutor executor = mock(FetchTaskStepExecutor.class);
        when(executor.supports(any(TaskStatus.class))).thenReturn(true);
        service.setStepExecutor(executor);

        FetchTask again = service.advance(task.getTaskId(), TaskStatus.QUERYING);

        assertEquals(TaskStatus.QUERYING, again.getStatus());
        verify(auditRepository, never()).save(any(AuditRow.class));
        verify(executor, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("任务不存在 → NotFoundException")
    void missingTaskThrows() {
        assertThrows(NotFoundException.class,
                () -> service.advance(UUID.randomUUID(), TaskStatus.QUERYING));
    }

    // ──────── 状态推进触发协议子步 ────────

    @Test
    @DisplayName("推进到 querying 触发 C-FIND 子步，产出回写任务行")
    void advanceTriggersStepExecutorAndWritesBackOutcome() {
        FetchTask task = seedTask(TaskStatus.QUEUED);
        FetchTaskStepExecutor executor = mock(FetchTaskStepExecutor.class);
        when(executor.supports(TaskStatus.QUERYING)).thenReturn(true);
        when(executor.execute(any(), any(), any())).thenReturn(
                new FetchTaskStepOutcome("1.2.840.10008.5.1.4.1.1.2", 42, 1024L, 87L));
        service.setStepExecutor(executor);

        FetchTask advanced = service.advance(task.getTaskId(), TaskStatus.QUERYING);

        verify(executor, times(1)).execute(any(), any(), org.mockito.ArgumentMatchers.eq(TaskStatus.QUERYING));
        assertEquals("1.2.840.10008.5.1.4.1.1.2", advanced.getSopClassUid());
        assertEquals(42, advanced.getSopInstanceCount());
        assertEquals(1024L, advanced.getBytesTotal());
        assertEquals(87L, advanced.getDurationMs());
    }

    @Test
    @DisplayName("子步失败 → 任务转 failed + 落失败码 + 落失败审计 + 进死信（不落第二行成功审计）")
    void stepFailureTransitionsTaskToFailed() {
        FetchTask task = seedTask(TaskStatus.QUEUED);
        FetchTaskStepExecutor executor = mock(FetchTaskStepExecutor.class);
        when(executor.supports(TaskStatus.QUERYING)).thenReturn(true);
        when(executor.execute(any(), any(), any()))
                .thenThrow(new com.crosshospital.imaging.exception.RoutingFailureException(
                        com.crosshospital.imaging.exception.RoutingFailureException.FailureCode.QUERY_TIMEOUT,
                        "C-FIND 响应超时", "PEER_HZ", "PAT_MPI_EXAMPLE"));
        service.setStepExecutor(executor);

        FetchTask advanced = service.advance(task.getTaskId(), TaskStatus.QUERYING);

        assertEquals(TaskStatus.FAILED, advanced.getStatus());
        assertEquals("query_timeout", advanced.getFailureCode());

        ArgumentCaptor<AuditRow> captor = ArgumentCaptor.forClass(AuditRow.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("task_failed", captor.getValue().getStatusTerminal());
        assertEquals("query_timeout", captor.getValue().getFailureCode());

        ArgumentCaptor<DlqEnqueueRequest> dlqCaptor = ArgumentCaptor.forClass(DlqEnqueueRequest.class);
        verify(dlqPort, times(1)).enqueue(dlqCaptor.capture());
        assertEquals("query_timeout", dlqCaptor.getValue().failureCode());
        assertEquals(task.getTaskId().toString(), dlqCaptor.getValue().taskId());
    }

    // ──────── 失败终态 ────────

    @Test
    @DisplayName("markFailed（失败出口端口）：推终态 + 落审计，不重复进死信")
    void markFailedViaPortDoesNotDoubleEnqueueDlq() {
        FetchTask task = seedTask(TaskStatus.PULLING);

        service.markFailed(task.getTaskId().toString(), "connection_refused");

        assertEquals(TaskStatus.FAILED, store.get(task.getTaskId()).getStatus());
        assertEquals("connection_refused", store.get(task.getTaskId()).getFailureCode());

        ArgumentCaptor<AuditRow> captor = ArgumentCaptor.forClass(AuditRow.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("task_failed", captor.getValue().getStatusTerminal());
        assertEquals("connection_refused", captor.getValue().getFailureCode());

        // 调用方（RoutingFailureHandler）自己已经进过死信，这里不能再进一次
        verify(dlqPort, never()).enqueue(any(DlqEnqueueRequest.class));
    }

    @Test
    @DisplayName("markFailed 对已失败任务幂等：不重复落审计")
    void markFailedOnFailedTaskIsIdempotent() {
        FetchTask task = seedTask(TaskStatus.FAILED);

        service.markFailed(task.getTaskId().toString(), "connection_refused");

        verify(auditRepository, never()).save(any(AuditRow.class));
    }

    @Test
    @DisplayName("已通知的任务拒绝翻成失败（业务状态不许倒退）")
    void notifiedTaskIsNotFlippedToFailed() {
        FetchTask task = seedTask(TaskStatus.NOTIFIED);

        service.markFailed(task.getTaskId().toString(), "connection_refused");

        assertEquals(TaskStatus.NOTIFIED, store.get(task.getTaskId()).getStatus());
        assertNull(store.get(task.getTaskId()).getFailureCode());
        verify(auditRepository, never()).save(any(AuditRow.class));
    }

    @Test
    @DisplayName("markFailedAndDeadLetter：推终态 + 进死信（死信只落一次）")
    void markFailedAndDeadLetterEnqueuesDlqOnce() {
        FetchTask task = seedTask(TaskStatus.QUEUED);

        service.markFailedAndDeadLetter(task.getTaskId(), "tls_mismatch", null);

        assertEquals(TaskStatus.FAILED, store.get(task.getTaskId()).getStatus());
        verify(dlqPort, times(1)).enqueue(any(DlqEnqueueRequest.class));
    }

    @Test
    @DisplayName("重试计数累加")
    void retryCountIncrements() {
        FetchTask task = seedTask(TaskStatus.QUERYING);

        assertEquals(1, service.incrementRetry(task.getTaskId()));
        assertEquals(2, service.incrementRetry(task.getTaskId()));
        assertEquals(2, store.get(task.getTaskId()).getRetryCount());
    }

    // ──────── 幂等去重与终值可查 ────────

    @Test
    @DisplayName("同一 patient_mpi + study_uid 二次入队复用既有任务（不建第二条）")
    void enqueueIsIdempotentOnCompositeKey() {
        FetchTaskEnqueueRequest request = new FetchTaskEnqueueRequest(
                "PAT_MPI_EXAMPLE", "STUDY_EXAMPLE_1", "PEER_HZ", "employee_007",
                FetchTaskEnqueueRequest.SRC_API, null, null);

        UUID first = service.enqueue(request);
        UUID second = service.enqueue(request);

        assertEquals(first, second);
        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("终值可查：按 task_id 取回终态任务 + 历史可查")
    void terminalValueIsQueryable() {
        FetchTask task = seedTask(TaskStatus.STORED);
        service.advance(task.getTaskId(), TaskStatus.NOTIFIED);

        Optional<FetchTask> found = service.findByTaskId(task.getTaskId());
        assertTrue(found.isPresent());
        assertEquals(TaskStatus.NOTIFIED, found.get().getStatus());
        assertTrue(service.isTerminal(task.getTaskId()));
        assertFalse(service.isTerminal(UUID.randomUUID()));

        List<FetchTask> history =
                service.findHistoryByPatientMpiAndStudyUid(task.getPatientMpi(), task.getStudyUid());
        assertEquals(1, history.size());
        assertEquals(TaskStatus.NOTIFIED, history.get(0).getStatus());
    }

    // ──────── helpers ────────

    private FetchTask seedTask(TaskStatus status) {
        FetchTask task = new FetchTask();
        task.setTaskId(UUID.randomUUID());
        task.setPatientMpi("PAT_MPI_EXAMPLE");
        task.setStudyUid("STUDY_EXAMPLE_1");
        task.setPeerAet("PEER_HZ");
        task.setAccessionNo("ACC_EXAMPLE");
        task.setStatus(status);
        task.setRetryCount(0);
        task.setInitiatedBy("employee_007");
        task.setInitiatedSrc("api");
        store.put(task.getTaskId(), task);
        return task;
    }
}
