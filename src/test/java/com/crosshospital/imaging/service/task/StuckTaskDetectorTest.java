package com.crosshospital.imaging.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.routing.DlqEnqueuePort;
import com.crosshospital.imaging.service.routing.DlqEnqueueRequest;

import java.time.Instant;
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
 * Task 21 — StuckTaskDetector 单元测试（Mockito 替身，不连数据库）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>卡死任务（{@code querying} 且超时未更新）→ 重置 {@code queued} +
 *       {@code retry_count+1} + 落一行 {@code task_stuck_requeued} 审计 + 重新入队；</li>
 *   <li>巡检只盯 {@code querying} 这一态（{@code queued} 排队不算卡死）；</li>
 *   <li>重试预算耗尽 → 转失败终态 + 进死信，而不是永远在 queued/querying 之间来回；</li>
 *   <li>巡检自身异常不外抛（不让调度器带崩）。</li>
 * </ul>
 */
@DisplayName("Task 21 — StuckTaskDetector 卡死任务巡检")
class StuckTaskDetectorTest {

    /** 卡死阈值：5 分钟（与生产默认一致）。 */
    private static final long STUCK_THRESHOLD_MS = 300_000L;

    private FetchTaskRepository repository;
    private AuditRepository auditRepository;
    private PeerRepository peerRepository;
    private DlqEnqueuePort dlqPort;
    private FetchTaskService fetchTaskService;
    private TaskRequeueService requeueService;
    private RetryBudgetResolver budgetResolver;
    private InMemoryFetchTaskQueue queue;
    private StuckTaskDetector detector;

    private Map<UUID, FetchTask> store;
    private Map<String, Peer> peers;
    private List<FetchTask> stuckResult;

    @BeforeEach
    void setUp() {
        repository = mock(FetchTaskRepository.class);
        auditRepository = mock(AuditRepository.class);
        peerRepository = mock(PeerRepository.class);
        dlqPort = mock(DlqEnqueuePort.class);
        queue = new InMemoryFetchTaskQueue(16);
        store = new HashMap<>();
        peers = new HashMap<>();
        stuckResult = new ArrayList<>();

        when(repository.save(any(FetchTask.class))).thenAnswer(inv -> {
            FetchTask task = inv.getArgument(0);
            store.put(task.getTaskId(), task);
            return task;
        });
        when(repository.findByTaskId(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(store.get((UUID) inv.getArgument(0))));
        when(repository.findStuckTasks(any(TaskStatus.class), any(Instant.class)))
                .thenAnswer(inv -> stuckResult);
        when(peerRepository.findByPeerAet(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(peers.get((String) inv.getArgument(0))));
        when(auditRepository.save(any(AuditRow.class))).thenAnswer(inv -> inv.getArgument(0));

        fetchTaskService = new FetchTaskService(repository, queue, new AuditService(auditRepository));
        fetchTaskService.setDlqEnqueuePort(dlqPort);
        requeueService = new TaskRequeueService(repository, new AuditService(auditRepository), queue);
        budgetResolver = new RetryBudgetResolver(peerRepository);
        detector = new StuckTaskDetector(repository, fetchTaskService, requeueService,
                budgetResolver, STUCK_THRESHOLD_MS);
    }

    // ──────── 状态重置 ────────

    @Test
    @DisplayName("卡死任务被捞回：重置 queued + retry_count+1 + 落审计 + 重新入队")
    void stuckTaskIsResetToQueuedWithAudit() {
        FetchTask task = seedTask(TaskStatus.QUERYING, 0, "PEER_HZ");
        stuckResult.add(task);
        seedPeer("PEER_HZ", 3, 1000);

        StuckTaskDetector.ScanReport report = detector.scanOnce();

        assertEquals(1, report.requeued());
        assertEquals(0, report.failed());
        assertEquals(0, report.skipped());

        FetchTask stored = store.get(task.getTaskId());
        assertEquals(TaskStatus.QUEUED, stored.getStatus(), "卡死任务必须重置回 queued");
        assertEquals(1, stored.getRetryCount());
        assertEquals("stuck_task_timeout", stored.getFailureCode());

        ArgumentCaptor<AuditRow> captor = ArgumentCaptor.forClass(AuditRow.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("task_stuck_requeued", captor.getValue().getStatusTerminal());
        assertEquals("stuck_task_timeout", captor.getValue().getFailureCode());
        assertEquals(1, captor.getValue().getRetryCount());

        assertEquals(1, queue.size(), "捞回后必须重新投回执行队列");
        assertEquals(task.getTaskId(), queue.poll());
    }

    @Test
    @DisplayName("巡检只盯 querying 这一态（排队中的 queued 不算卡死）")
    void scanOnlyLooksAtQueryingTasks() {
        detector.scanOnce();

        verify(repository, times(1)).findStuckTasks(eq(TaskStatus.QUERYING), any(Instant.class));
    }

    @Test
    @DisplayName("没有卡死任务：不动任何状态、不落审计")
    void freshTasksAreUntouched() {
        StuckTaskDetector.ScanReport report = detector.scanOnce();

        assertEquals(0, report.requeued());
        assertEquals(0, report.failed());
        verify(auditRepository, never()).save(any(AuditRow.class));
        assertEquals(0, queue.size());
    }

    @Test
    @DisplayName("卡死任务重试计数已 +1：捞回后计数继续累加")
    void requeueIncrementsExistingRetryCount() {
        FetchTask task = seedTask(TaskStatus.QUERYING, 2, "PEER_HZ");
        stuckResult.add(task);
        seedPeer("PEER_HZ", 5, 1000);

        detector.scanOnce();

        assertEquals(3, store.get(task.getTaskId()).getRetryCount());
    }

    // ──────── 预算耗尽 ────────

    @Test
    @DisplayName("重试预算已耗尽：转失败终态 + 进死信，不再无休止捞回")
    void exhaustedBudgetFailsTheTask() {
        FetchTask task = seedTask(TaskStatus.QUERYING, 3, "PEER_HZ");
        stuckResult.add(task);
        seedPeer("PEER_HZ", 3, 1000);

        StuckTaskDetector.ScanReport report = detector.scanOnce();

        assertEquals(0, report.requeued());
        assertEquals(1, report.failed());

        FetchTask stored = store.get(task.getTaskId());
        assertEquals(TaskStatus.FAILED, stored.getStatus());
        assertEquals("stuck_task_timeout", stored.getFailureCode());

        ArgumentCaptor<AuditRow> captor = ArgumentCaptor.forClass(AuditRow.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("task_failed", captor.getValue().getStatusTerminal());

        ArgumentCaptor<DlqEnqueueRequest> dlqCaptor = ArgumentCaptor.forClass(DlqEnqueueRequest.class);
        verify(dlqPort, times(1)).enqueue(dlqCaptor.capture());
        assertEquals("stuck_task_timeout", dlqCaptor.getValue().failureCode());
        assertEquals(task.getTaskId().toString(), dlqCaptor.getValue().taskId());
        assertEquals(0, queue.size(), "转失败的任务不应再进队列");
    }

    // ──────── 健壮性 ────────

    @Test
    @DisplayName("巡检自身异常不外抛（不让调度器带崩）")
    void scanSwallowsRepositoryFailure() {
        when(repository.findStuckTasks(any(TaskStatus.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("数据库连不上"));

        detector.scan(); // 不抛即通过

        assertEquals(0, queue.size());
    }

    @Test
    @DisplayName("巡检遇到终态任务时跳过（不回退已终结的任务，也不重新入队）")
    void terminalTaskIsSkipped() {
        FetchTask task = seedTask(TaskStatus.NOTIFIED, 0, "PEER_HZ");
        stuckResult.add(task);
        seedPeer("PEER_HZ", 3, 1000);

        StuckTaskDetector.ScanReport report = detector.scanOnce();

        assertEquals(0, report.requeued());
        assertEquals(1, report.skipped());
        assertEquals(TaskStatus.NOTIFIED, store.get(task.getTaskId()).getStatus());
        assertEquals(0, queue.size());
        verify(auditRepository, never()).save(any(AuditRow.class));
    }

    // ──────── helpers ────────

    private FetchTask seedTask(TaskStatus status, int retryCount, String peerAet) {
        FetchTask task = new FetchTask();
        task.setTaskId(UUID.randomUUID());
        task.setPatientMpi("PAT_MPI_EXAMPLE");
        task.setStudyUid("STUDY_EXAMPLE_1");
        task.setPeerAet(peerAet);
        task.setAccessionNo("ACC_EXAMPLE");
        task.setStatus(status);
        task.setRetryCount(retryCount);
        task.setInitiatedBy("employee_007");
        task.setInitiatedSrc("api");
        task.setUpdatedAt(Instant.now().minusSeconds(600));
        store.put(task.getTaskId(), task);
        return task;
    }

    private void seedPeer(String aet, int maxRetries, int backoffMs) {
        Peer peer = new Peer();
        peer.setPeerAet(aet);
        peer.setHost("127.0.0.1");
        peer.setPort(11112);
        peer.setMaxRetries(maxRetries);
        peer.setBackoffMs(backoffMs);
        peers.put(aet, peer);
    }
}
