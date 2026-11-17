package com.crosshospital.imaging.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 21 核查 —— 执行器（task 20）与任务级重试（task 21）的衔接。
 *
 * <p>一条<b>可重试</b>的失败会让任务退回 {@code queued} 等待退避后重跑，
 * 这不是「失败」。执行器必须认得出这个结局：它既不是推进成功（{@code stored}），
 * 也不是推进失败（{@code failed}），而是「这一轮到此为止，等重试」。
 *
 * <p>若执行器只认 {@code failed}，退回 {@code queued} 的任务会被当成
 * 「还在往下推进」，于是接着推 {@code pulling} —— 而 {@code queued → pulling}
 * 是非法跃迁，于是每次可重试失败都伴随一条「非法跃迁」告警和一个虚高的失败计数。
 */
@DisplayName("Task 21 核查 — 执行器识别「已安排重试」这一结局")
class FetchTaskRunnerRetryTest {

    private FetchTaskRepository repository;
    private PeerRepository peerRepository;
    private AuditRepository auditRepository;
    private FetchTaskService fetchTaskService;
    private FetchTaskRunner runner;
    private RecordingStepExecutor executor;
    private Map<UUID, FetchTask> store;
    private Map<String, Peer> peers;

    @BeforeEach
    void setUp() {
        repository = mock(FetchTaskRepository.class);
        peerRepository = mock(PeerRepository.class);
        auditRepository = mock(AuditRepository.class);
        store = new HashMap<>();
        peers = new HashMap<>();

        when(repository.save(any(FetchTask.class))).thenAnswer(inv -> {
            FetchTask task = inv.getArgument(0);
            store.put(task.getTaskId(), task);
            return task;
        });
        when(repository.findByTaskId(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(store.get((UUID) inv.getArgument(0))));
        when(repository.findActiveByPatientMpiAndStudyUid(anyString(), anyString()))
                .thenAnswer(inv -> Optional.empty());
        when(peerRepository.findByPeerAet(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(peers.get((String) inv.getArgument(0))));
        when(auditRepository.save(any(AuditRow.class))).thenAnswer(inv -> inv.getArgument(0));

        InMemoryFetchTaskQueue queue = new InMemoryFetchTaskQueue(8);
        executor = new RecordingStepExecutor();
        fetchTaskService = new FetchTaskService(repository, queue, new AuditService(auditRepository));
        fetchTaskService.setStepExecutor(executor);
        attachRetryPort(queue);
        runner = new FetchTaskRunner(fetchTaskService, queue);
    }

    @Test
    @DisplayName("可重试失败：任务退回 queued 等待重试，执行器就此停手（不推进下一步、不算失败）")
    void retryableFailureStopsPipelineWithoutCountingAsFailure() {
        UUID taskId = seedTask(TaskStatus.QUEUED);
        executor.failOn = TaskStatus.QUERYING;

        runner.process(taskId);

        FetchTask stored = store.get(taskId);
        assertEquals(TaskStatus.QUEUED, stored.getStatus(), "可重试失败应退回 queued 等待重跑");
        assertEquals(1, stored.getRetryCount(), "重试次数应 +1");
        assertEquals("query_timeout", stored.getFailureCode());

        assertEquals(1, executor.executedStatuses.size(),
                "退回 queued 后不得再推进 pulling（queued → pulling 是非法跃迁）：" + executor.executedStatuses);
        assertEquals(0, runner.failedCount(),
                "已安排重试的任务不是失败，不该计入失败计数");
        assertEquals(0, runner.processedCount(), "还没推到 stored，不算处理完成");
    }

    @Test
    @DisplayName("不可重试失败：任务转 failed，执行器停手并计入失败（与 task 20 行为一致）")
    void nonRetryableFailureStillFailsTerminally() {
        UUID taskId = seedTask(TaskStatus.QUEUED);
        executor.failOn = TaskStatus.QUERYING;
        executor.failureCode = RoutingFailureException.FailureCode.ASSOCIATION_REJECTED;

        runner.process(taskId);

        assertEquals(TaskStatus.FAILED, store.get(taskId).getStatus());
        assertEquals("association_rejected", store.get(taskId).getFailureCode());
        assertEquals(1, runner.failedCount());
        assertEquals(1, executor.executedStatuses.size());
    }

    @Test
    @DisplayName("可重试失败后重跑成功：执行器认得回 queued 这一次，一路推到 notified")
    void retryThenSuccessReachesNotified() {
        UUID taskId = seedTask(TaskStatus.QUEUED);
        executor.failOn = TaskStatus.QUERYING;

        runner.process(taskId);
        assertEquals(TaskStatus.QUEUED, store.get(taskId).getStatus());

        // 退避到点，任务重新入队；这一次查询步成功
        executor.failOn = null;
        executor.executedStatuses.clear();
        runner.process(taskId);

        assertEquals(TaskStatus.NOTIFIED, store.get(taskId).getStatus());
        assertEquals(1, runner.processedCount());
        assertNotEquals(TaskStatus.FAILED, store.get(taskId).getStatus());
    }

    // ──────── helpers ────────

    private void attachRetryPort(InMemoryFetchTaskQueue queue) {
        RetryPolicy policy = new RetryPolicy();
        policy.setJitterSource(() -> 0.0d);
        TaskRequeueService requeueService =
                new TaskRequeueService(repository, new AuditService(auditRepository), queue);
        RetryScheduler scheduler = new RetryScheduler(policy, requeueService,
                new RetryBudgetResolver(peerRepository), new BackoffScheduler() {
                    @Override
                    public void schedule(Runnable task, long delayMs) {
                        // 单测不退避真等
                    }
                });
        fetchTaskService.setTaskRetryPort(scheduler);
    }

    private UUID seedTask(TaskStatus status) {
        FetchTask task = new FetchTask();
        task.setTaskId(UUID.randomUUID());
        task.setPatientMpi("PAT_MPI_EXAMPLE");
        task.setStudyUid("STUDY_EXAMPLE_1");
        task.setPeerAet("PEER_HZ");
        task.setStatus(status);
        task.setRetryCount(0);
        task.setInitiatedBy("system");
        task.setInitiatedSrc("api");
        store.put(task.getTaskId(), task);

        Peer peer = new Peer();
        peer.setPeerAet("PEER_HZ");
        peer.setHost("127.0.0.1");
        peer.setPort(11112);
        peer.setMaxRetries(3);
        peer.setBackoffMs(1000);
        peers.put("PEER_HZ", peer);
        return task.getTaskId();
    }

    /** 记录型子步替身：可指定在某一步抛可重试 / 不可重试的失败。 */
    private static final class RecordingStepExecutor implements FetchTaskStepExecutor {

        private final java.util.List<TaskStatus> executedStatuses = new java.util.ArrayList<>();
        private TaskStatus failOn;
        private RoutingFailureException.FailureCode failureCode =
                RoutingFailureException.FailureCode.QUERY_TIMEOUT;

        @Override
        public boolean supports(TaskStatus status) {
            return status == TaskStatus.QUERYING
                    || status == TaskStatus.PULLING
                    || status == TaskStatus.STORED;
        }

        @Override
        public FetchTaskStepOutcome execute(UUID taskId, FetchTask task, TaskStatus targetStatus) {
            executedStatuses.add(targetStatus);
            if (targetStatus == failOn) {
                throw new RoutingFailureException(failureCode, "子步失败（测试注入）", "PEER_HZ", "PAT_MPI_EXAMPLE");
            }
            return new FetchTaskStepOutcome("1.2.840.10008.5.1.4.1.1.2", 3, 512L, 12L);
        }
    }
}
