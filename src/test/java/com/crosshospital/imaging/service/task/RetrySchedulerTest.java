package com.crosshospital.imaging.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.service.AuditService;

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
 * Task 21 — RetryScheduler 单元测试（Mockito 替身，不连数据库）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>可重试失败 → 任务退回 queued + {@code retry_count+1} + 落一行重试审计 +
 *       按退避时长排一次重新入队；</li>
 *   <li>不可重试失败（配置型）→ 不重试、不回退、不落审计；</li>
 *   <li>重试预算耗尽 → 不重试；</li>
 *   <li>节点未登记 → 回落默认预算（3 次 / 1000ms）；</li>
 *   <li>同一次失败被问两次 → 幂等（只回退一次、只排一次退避）；</li>
 *   <li>终态任务不回退；</li>
 *   <li>退避到点后任务真的回到执行队列。</li>
 * </ul>
 */
@DisplayName("Task 21 — RetryScheduler 任务级重试调度")
class RetrySchedulerTest {

    private FetchTaskRepository repository;
    private AuditRepository auditRepository;
    private PeerRepository peerRepository;
    private TaskRequeueService requeueService;
    private RetryPolicy policy;
    private RetryBudgetResolver budgetResolver;
    private InMemoryFetchTaskQueue queue;
    private RecordingBackoffScheduler backoff;
    private RetryScheduler scheduler;

    private Map<UUID, FetchTask> store;
    private Map<String, Peer> peers;

    /** 记录调度动作的退避调度器替身（不真等）。 */
    static final class RecordingBackoffScheduler implements BackoffScheduler {
        final List<long[]> delays = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void schedule(Runnable task, long delayMs) {
            delays.add(new long[]{delayMs});
            tasks.add(task);
        }

        /** 触发到点的退避动作。 */
        void fire() {
            tasks.forEach(Runnable::run);
        }
    }

    @BeforeEach
    void setUp() {
        repository = mock(FetchTaskRepository.class);
        auditRepository = mock(AuditRepository.class);
        peerRepository = mock(PeerRepository.class);
        policy = new RetryPolicy();
        policy.setJitterSource(() -> 0.0d); // 退避时长可精确断言
        queue = new InMemoryFetchTaskQueue(16);
        backoff = new RecordingBackoffScheduler();
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

        requeueService = new TaskRequeueService(repository, new AuditService(auditRepository), queue);
        budgetResolver = new RetryBudgetResolver(peerRepository);
        scheduler = new RetryScheduler(policy, requeueService, budgetResolver, backoff);
    }

    // ──────── 成功路径：安排重试 ────────

    @Test
    @DisplayName("可重试失败：任务退回 queued + retry_count+1 + 落一行重试审计 + 按退避排期")
    void retryableFailureResetsTaskAndSchedulesRetry() {
        FetchTask task = seedTask(TaskStatus.QUERYING, 0, "PEER_HZ");
        seedPeer("PEER_HZ", 3, 1000);

        RetryScheduleResult result = scheduler.scheduleRetry(task.getTaskId(), "connection_refused", "system");

        assertTrue(result.retried(), "connection_refused 应可重试");
        assertEquals(1, result.retryCount());
        assertEquals(1000L, result.backoffMs());
        assertEquals("connection_refused", result.failureCode());

        FetchTask stored = store.get(task.getTaskId());
        assertEquals(TaskStatus.QUEUED, stored.getStatus(), "重试前任务必须退回 queued");
        assertEquals(1, stored.getRetryCount());
        assertEquals("connection_refused", stored.getFailureCode());

        ArgumentCaptor<AuditRow> captor = ArgumentCaptor.forClass(AuditRow.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("task_retry_scheduled", captor.getValue().getStatusTerminal());
        assertEquals("connection_refused", captor.getValue().getFailureCode());
        assertEquals(1, captor.getValue().getRetryCount());

        assertEquals(1, backoff.delays.size(), "应排一次退避");
        assertEquals(1000L, backoff.delays.get(0)[0]);
    }

    @Test
    @DisplayName("退避到点后任务真的回到执行队列")
    void taskIsResubmittedToQueueAfterBackoff() {
        FetchTask task = seedTask(TaskStatus.QUERYING, 0, "PEER_HZ");
        seedPeer("PEER_HZ", 3, 1000);

        scheduler.scheduleRetry(task.getTaskId(), "query_timeout", "system");
        assertEquals(0, queue.size(), "退避未到点前不得入队");

        backoff.fire();

        assertEquals(1, queue.size());
        assertEquals(task.getTaskId(), queue.poll());
        assertEquals(0, scheduler.pendingRetryCount(), "退避触发后待重试计数应销账");
    }

    @Test
    @DisplayName("退避时长随节点的 backoff_ms 基数走（逐节点预算）")
    void backoffBaseComesFromPeerConfig() {
        FetchTask task = seedTask(TaskStatus.QUERYING, 0, "PEER_HZ");
        seedPeer("PEER_HZ", 3, 2000);

        RetryScheduleResult result = scheduler.scheduleRetry(task.getTaskId(), "retrieve_failed", "system");

        assertEquals(2000L, result.backoffMs());
        assertEquals(2000L, backoff.delays.get(0)[0]);
    }

    @Test
    @DisplayName("节点未登记：回落默认预算（3 次 / 1000ms），仍可重试")
    void unregisteredPeerFallsBackToDefaultBudget() {
        FetchTask task = seedTask(TaskStatus.QUERYING, 0, "PEER_UNKNOWN");

        RetryScheduleResult result = scheduler.scheduleRetry(task.getTaskId(), "connection_refused", "system");

        assertTrue(result.retried());
        assertEquals(1000L, result.backoffMs());
    }

    @Test
    @DisplayName("连续失败按重试计数递增退避：第 2 次等 2s、第 3 次等 4s")
    void backoffGrowsWithRetryCount() {
        FetchTask task = seedTask(TaskStatus.QUERYING, 1, "PEER_HZ");
        seedPeer("PEER_HZ", 3, 1000);

        RetryScheduleResult second = scheduler.scheduleRetry(task.getTaskId(), "connection_refused", "system");
        assertEquals(2, second.retryCount());
        assertEquals(2000L, second.backoffMs(), "第 2 次重试的基线应是 1000 × 2^1");
    }

    // ──────── 不重试的路径 ────────

    @Test
    @DisplayName("配置型失败码不重试：任务原地不动、不落审计、不排退避")
    void nonRetryableFailureIsNotRetried() {
        FetchTask task = seedTask(TaskStatus.QUERYING, 0, "PEER_HZ");
        seedPeer("PEER_HZ", 3, 1000);

        RetryScheduleResult result = scheduler.scheduleRetry(task.getTaskId(), "tls_mismatch", "system");

        assertFalse(result.retried());
        assertTrue(result.reason().contains("tls_mismatch"), result.reason());
        assertEquals(TaskStatus.QUERYING, store.get(task.getTaskId()).getStatus());
        assertEquals(0, store.get(task.getTaskId()).getRetryCount());
        verify(auditRepository, never()).save(any(AuditRow.class));
        assertEquals(0, backoff.delays.size());
    }

    @Test
    @DisplayName("重试预算耗尽（retry_count=3 / 上限 3）→ 不重试")
    void exhaustedBudgetIsNotRetried() {
        FetchTask task = seedTask(TaskStatus.QUERYING, 3, "PEER_HZ");
        seedPeer("PEER_HZ", 3, 1000);

        RetryScheduleResult result = scheduler.scheduleRetry(task.getTaskId(), "connection_refused", "system");

        assertFalse(result.retried());
        assertTrue(result.reason().contains("预算耗尽"), result.reason());
        assertEquals(0, backoff.delays.size());
    }

    @Test
    @DisplayName("未知失败码不重试")
    void unknownFailureCodeIsNotRetried() {
        FetchTask task = seedTask(TaskStatus.QUERYING, 0, "PEER_HZ");
        seedPeer("PEER_HZ", 3, 1000);

        assertFalse(scheduler.scheduleRetry(task.getTaskId(), "stuck_task_timeout", "system").retried());
        assertFalse(scheduler.scheduleRetry(task.getTaskId(), null, "system").retried());
    }

    @Test
    @DisplayName("终态任务不回退重排（已通知 HIS 的任务不能翻回去重跑）")
    void terminalTaskIsNotRequeued() {
        FetchTask notified = seedTask(TaskStatus.NOTIFIED, 0, "PEER_HZ");
        seedPeer("PEER_HZ", 3, 1000);

        RetryScheduleResult result = scheduler.scheduleRetry(notified.getTaskId(), "connection_refused", "system");

        assertFalse(result.retried());
        assertEquals(TaskStatus.NOTIFIED, store.get(notified.getTaskId()).getStatus());
        assertEquals(0, backoff.delays.size());
    }

    @Test
    @DisplayName("任务不存在 → 不重试")
    void missingTaskIsNotRetried() {
        RetryScheduleResult result = scheduler.scheduleRetry(UUID.randomUUID(), "connection_refused", "system");
        assertFalse(result.retried());
    }

    // ──────── 幂等 ────────

    @Test
    @DisplayName("同一次失败被问两次：只回退一次、只排一次退避（幂等）")
    void secondCallForSameFailureIsIdempotent() {
        FetchTask task = seedTask(TaskStatus.QUERYING, 0, "PEER_HZ");
        seedPeer("PEER_HZ", 3, 1000);

        RetryScheduleResult first = scheduler.scheduleRetry(task.getTaskId(), "connection_refused", "system");
        RetryScheduleResult second = scheduler.scheduleRetry(task.getTaskId(), "connection_refused", "system");

        assertTrue(first.retried());
        assertTrue(second.retried(), "第二次也要回执「已安排重试」，否则调用方会把任务判死");
        assertEquals(first.retryCount(), second.retryCount(), "重试次数不能被加两次");

        assertEquals(1, store.get(task.getTaskId()).getRetryCount(), "retry_count 只能加一次");
        verify(auditRepository, times(1)).save(any(AuditRow.class));
        assertEquals(1, backoff.delays.size(), "只能排一次退避");
    }

    @Test
    @DisplayName("退避触发后再次询问 → 视为新一轮失败，可再排一次退避")
    void afterBackoffFiresANewRetryCanBeScheduled() {
        FetchTask task = seedTask(TaskStatus.QUERYING, 0, "PEER_HZ");
        seedPeer("PEER_HZ", 3, 1000);

        scheduler.scheduleRetry(task.getTaskId(), "connection_refused", "system");
        backoff.fire();

        // 任务已被退避触发重新入队（回到 queued），执行器再跑一次又失败
        FetchTask stored = store.get(task.getTaskId());
        stored.setStatus(TaskStatus.QUERYING);

        RetryScheduleResult next = scheduler.scheduleRetry(task.getTaskId(), "connection_refused", "system");
        assertTrue(next.retried());
        assertEquals(2, next.retryCount());
        assertEquals(2000L, next.backoffMs());
        assertEquals(2, backoff.delays.size());
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

    /** 重试审计行必须能定位到「哪条任务、哪个外院、哪次检查」，否则排障无从下手。 */
    @Test
    @DisplayName("重试审计行带定位字段：外院 AET + 影像引用 + 操作人")
    void retryAuditRowCarriesLocatingFields() {
        FetchTask task = seedTask(TaskStatus.QUERYING, 0, "PEER_HZ");
        seedPeer("PEER_HZ", 3, 1000);

        scheduler.scheduleRetry(task.getTaskId(), "connection_refused", "employee_007");

        ArgumentCaptor<AuditRow> captor = ArgumentCaptor.forClass(AuditRow.class);
        verify(auditRepository).save(captor.capture());
        AuditRow row = captor.getValue();
        assertEquals("PEER_HZ", row.getToAet());
        assertEquals("STUDY_EXAMPLE_1", row.getStudyUid());
        assertEquals("system", row.getWho());
        assertNotNull(row.getStatusTerminal());
    }
}
