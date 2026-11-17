package com.crosshospital.imaging.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.exception.RoutingFailureException;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.routing.DlqEnqueuePort;
import com.crosshospital.imaging.service.routing.DlqEnqueueRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Task 21 — FetchTaskService 与任务级重试端口的衔接测试。
 *
 * <p>这一组测的是「重试插在状态机的哪一格」——
 * <ul>
 *   <li>接上重试端口：子步失败 → 任务退回 queued，<b>不</b>推失败终态、<b>不</b>进死信、
 *       <b>不</b>落「推进成功」审计；</li>
 *   <li>不接重试端口（未启用任务级重试）：行为与 task 20 完全一致 ——
 *       失败即终态 + 失败审计 + 进死信（回归护栏）。</li>
 * </ul>
 */
@DisplayName("Task 21 — 任务级重试与五态机的衔接")
class FetchTaskServiceRetryTest {

    private FetchTaskRepository repository;
    private AuditRepository auditRepository;
    private PeerRepository peerRepository;
    private DlqEnqueuePort dlqPort;
    private FetchTaskService service;
    private InMemoryFetchTaskQueue queue;

    private Map<UUID, FetchTask> store;
    private Map<String, Peer> peers;

    @BeforeEach
    void setUp() {
        repository = mock(FetchTaskRepository.class);
        auditRepository = mock(AuditRepository.class);
        peerRepository = mock(PeerRepository.class);
        dlqPort = mock(DlqEnqueuePort.class);
        queue = new InMemoryFetchTaskQueue(16);
        store = new HashMap<>();
        peers = new HashMap<>();

        when(repository.save(any(FetchTask.class))).thenAnswer(inv -> {
            FetchTask task = inv.getArgument(0);
            store.put(task.getTaskId(), task);
            return task;
        });
        when(repository.findByTaskId(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(store.get((UUID) inv.getArgument(0))));
        when(peerRepository.findByPeerAet(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(peers.get((String) inv.getArgument(0))));
        when(auditRepository.save(any(AuditRow.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new FetchTaskService(repository, queue, new AuditService(auditRepository));
        service.setDlqEnqueuePort(dlqPort);
    }

    // ──────── 接上重试端口 ────────

    @Test
    @DisplayName("子步失败且失败码可重试：任务退回 queued，不推 failed、不进死信")
    void stepFailureWithRetryPortResetsTaskInsteadOfFailing() {
        attachRetryPort();
        FetchTask task = seedTask(TaskStatus.QUEUED, 0);
        service.setStepExecutor(failingExecutor());

        FetchTask result = service.advance(task.getTaskId(), TaskStatus.QUERYING, "system");

        assertNotEquals(TaskStatus.FAILED, result.getStatus(), "可重试失败不应把任务判死");
        assertEquals(TaskStatus.QUEUED, result.getStatus());
        assertEquals(1, result.getRetryCount());
        assertEquals("query_timeout", result.getFailureCode());
        verify(dlqPort, never()).enqueue(any(DlqEnqueueRequest.class));
    }

    @Test
    @DisplayName("重试已安排时不再落「推进成功」审计（避免审计里出现假成功）")
    void retryScheduledTaskDoesNotGetSuccessAudit() {
        attachRetryPort();
        FetchTask task = seedTask(TaskStatus.QUEUED, 0);
        service.setStepExecutor(failingExecutor());

        service.advance(task.getTaskId(), TaskStatus.QUERYING, "system");

        ArgumentCaptor<AuditRow> captor = ArgumentCaptor.forClass(AuditRow.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("task_retry_scheduled", captor.getValue().getStatusTerminal(),
                "只应落重试调度这一行审计");
    }

    @Test
    @DisplayName("失败出口端口（markFailed）同样走重试：任务不被判死")
    void markFailedViaPortWithRetryPortDoesNotFailTheTask() {
        attachRetryPort();
        FetchTask task = seedTask(TaskStatus.PULLING, 0);

        service.markFailed(task.getTaskId().toString(), "connection_refused");

        assertEquals(TaskStatus.QUEUED, store.get(task.getTaskId()).getStatus());
        assertEquals(1, store.get(task.getTaskId()).getRetryCount());
        verify(dlqPort, never()).enqueue(any(DlqEnqueueRequest.class));
    }

    @Test
    @DisplayName("配置型失败（不可重试）即便接了重试端口也照旧判死")
    void nonRetryableFailureStillFailsTerminally() {
        attachRetryPort();
        FetchTask task = seedTask(TaskStatus.QUEUED, 0);
        FetchTaskStepExecutor executor = mock(FetchTaskStepExecutor.class);
        when(executor.supports(TaskStatus.QUERYING)).thenReturn(true);
        when(executor.execute(any(), any(), any())).thenThrow(new RoutingFailureException(
                RoutingFailureException.FailureCode.ASSOCIATION_REJECTED,
                "外院 PACS 拒绝本院 AE Title", "PEER_HZ", "PAT_MPI_EXAMPLE"));
        service.setStepExecutor(executor);

        FetchTask result = service.advance(task.getTaskId(), TaskStatus.QUERYING, "system");

        assertEquals(TaskStatus.FAILED, result.getStatus());
        assertEquals("association_rejected", result.getFailureCode());
        verify(dlqPort, times(1)).enqueue(any(DlqEnqueueRequest.class));
    }

    // ──────── 不接重试端口（回归护栏） ────────

    @Test
    @DisplayName("未接重试端口：失败即终态 + 失败审计 + 进死信（与 task 20 行为一致）")
    void withoutRetryPortFailureIsTerminal() {
        FetchTask task = seedTask(TaskStatus.QUEUED, 0);
        service.setStepExecutor(failingExecutor());

        FetchTask result = service.advance(task.getTaskId(), TaskStatus.QUERYING, "system");

        assertEquals(TaskStatus.FAILED, result.getStatus());
        assertEquals("query_timeout", result.getFailureCode());

        ArgumentCaptor<AuditRow> captor = ArgumentCaptor.forClass(AuditRow.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("task_failed", captor.getValue().getStatusTerminal());

        verify(dlqPort, times(1)).enqueue(any(DlqEnqueueRequest.class));
    }

    // ──────── helpers ────────

    private void attachRetryPort() {
        RetryPolicy policy = new RetryPolicy();
        policy.setJitterSource(() -> 0.0d);
        TaskRequeueService requeueService =
                new TaskRequeueService(repository, new AuditService(auditRepository), queue);
        RetryScheduler scheduler = new RetryScheduler(policy, requeueService,
                new RetryBudgetResolver(peerRepository), new BackoffScheduler() {
                    @Override
                    public void schedule(Runnable task, long delayMs) {
                        // 单测里不真等，只记录
                    }
                });
        service.setTaskRetryPort(scheduler);
    }

    private FetchTaskStepExecutor failingExecutor() {
        FetchTaskStepExecutor executor = mock(FetchTaskStepExecutor.class);
        when(executor.supports(TaskStatus.QUERYING)).thenReturn(true);
        when(executor.execute(any(), any(), any())).thenThrow(new RoutingFailureException(
                RoutingFailureException.FailureCode.QUERY_TIMEOUT,
                "C-FIND 响应超时", "PEER_HZ", "PAT_MPI_EXAMPLE"));
        return executor;
    }

    private FetchTask seedTask(TaskStatus status, int retryCount) {
        FetchTask task = new FetchTask();
        task.setTaskId(UUID.randomUUID());
        task.setPatientMpi("PAT_MPI_EXAMPLE");
        task.setStudyUid("STUDY_EXAMPLE_1");
        task.setPeerAet("PEER_HZ");
        task.setAccessionNo("ACC_EXAMPLE");
        task.setStatus(status);
        task.setRetryCount(retryCount);
        task.setInitiatedBy("employee_007");
        task.setInitiatedSrc("api");
        store.put(task.getTaskId(), task);

        Peer peer = new Peer();
        peer.setPeerAet("PEER_HZ");
        peer.setHost("127.0.0.1");
        peer.setPort(11112);
        peer.setMaxRetries(3);
        peer.setBackoffMs(1000);
        peers.put("PEER_HZ", peer);
        return task;
    }
}
