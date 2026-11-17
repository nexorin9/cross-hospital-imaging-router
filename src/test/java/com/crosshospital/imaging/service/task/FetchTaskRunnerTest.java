package com.crosshospital.imaging.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.exception.RoutingFailureException;
import com.crosshospital.imaging.repository.FetchTaskRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 20 — FetchTaskRunner 队列消费与流水线推进单元测试。
 *
 * <p>用<b>真实的 {@link FetchTaskService}</b>（Repository 用 Mockito 替身）而不是
 * mock 服务本身 —— 执行器与状态机是一对，单独 mock 掉服务就等于把
 * 「推进是否真的按五态机走」这件事从测试里拿掉了。
 *
 * <p>覆盖：
 * <ul>
 *   <li>queued 的任务被依次推进 querying → pulling → stored → notified；</li>
 *   <li>不在 queued 的任务不重复推进；</li>
 *   <li>库里查不到的任务被跳过（不抛异常，不拖垮执行器）；</li>
 *   <li>某一步失败 → 任务转 failed → 执行器不再往下推；</li>
 *   <li>非法跃迁抛出时执行器不退出（后续任务仍可处理）。</li>
 * </ul>
 */
@DisplayName("Task 20 — FetchTaskRunner 队列消费")
class FetchTaskRunnerTest {

    private FetchTaskRepository repository;
    private FetchTaskService fetchTaskService;
    private FetchTaskRunner runner;
    private RecordingStepExecutor executor;
    private Map<UUID, FetchTask> store;

    @BeforeEach
    void setUp() {
        repository = mock(FetchTaskRepository.class);
        store = new HashMap<>();
        when(repository.save(any(FetchTask.class))).thenAnswer(inv -> {
            FetchTask task = inv.getArgument(0);
            store.put(task.getTaskId(), task);
            return task;
        });
        when(repository.findByTaskId(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(store.get((UUID) inv.getArgument(0))));
        when(repository.findActiveByPatientMpiAndStudyUid(anyString(), anyString()))
                .thenAnswer(inv -> Optional.empty());

        executor = new RecordingStepExecutor();
        fetchTaskService = new FetchTaskService(repository, new InMemoryFetchTaskQueue(8));
        fetchTaskService.setStepExecutor(executor);
        runner = new FetchTaskRunner(fetchTaskService, new InMemoryFetchTaskQueue(8));
    }

    @Test
    @DisplayName("queued 的任务被依次推进 querying → pulling → stored → notified")
    void queuedTaskIsDrivenThroughPipeline() {
        UUID taskId = seedTask(TaskStatus.QUEUED);

        runner.process(taskId);

        // 协议子步只有三步；notified 是状态 + 审计 + 通知，不再发 DICOM 请求
        assertEquals(List.of(TaskStatus.QUERYING, TaskStatus.PULLING, TaskStatus.STORED),
                executor.executedStatuses);
        assertEquals(TaskStatus.NOTIFIED, store.get(taskId).getStatus(),
                "流水线必须推到第五态 notified，否则 HIS 通知与 AI 增强永远不会触发");
        assertEquals(1, runner.processedCount());
        assertEquals(0, runner.failedCount());
    }

    @Test
    @DisplayName("推到 notified 时真的调用通知端口（回归：旧流水线停在 stored）")
    void notifiedTriggersNotificationPort() {
        List<String> notifications = new ArrayList<>();
        fetchTaskService.setNotificationPort((id, studyUid, status) ->
                notifications.add(status + ":" + studyUid));
        UUID taskId = seedTask(TaskStatus.QUEUED);

        runner.process(taskId);

        assertEquals(List.of("notified:STUDY_EXAMPLE_1"), notifications);
        assertEquals(TaskStatus.NOTIFIED, store.get(taskId).getStatus());
    }

    @Test
    @DisplayName("不在 queued 的任务不重复推进")
    void nonQueuedTaskIsSkipped() {
        UUID taskId = seedTask(TaskStatus.STORED);

        runner.process(taskId);

        assertTrue(executor.executedStatuses.isEmpty());
        assertEquals(0, runner.processedCount());
    }

    @Test
    @DisplayName("库里查不到的任务被跳过（不抛异常）")
    void missingTaskIsSkipped() {
        runner.process(UUID.randomUUID());

        assertTrue(executor.executedStatuses.isEmpty());
        assertEquals(0, runner.failedCount());
    }

    @Test
    @DisplayName("某一步失败 → 任务转 failed → 执行器不再往下推")
    void failedStepStopsPipeline() {
        UUID taskId = seedTask(TaskStatus.QUEUED);
        executor.failOn = TaskStatus.QUERYING;

        runner.process(taskId);

        assertEquals(List.of(TaskStatus.QUERYING), executor.executedStatuses);
        assertEquals(TaskStatus.FAILED, store.get(taskId).getStatus());
        assertEquals("query_timeout", store.get(taskId).getFailureCode());
        assertEquals(1, runner.failedCount());
    }

    @Test
    @DisplayName("并发推进导致的非法跃迁：执行器只停手，不退出")
    void illegalTransitionStopsRunnerWithoutKillingIt() {
        UUID taskId = seedTask(TaskStatus.QUEUED);
        // 模拟另一条路径在查询步之后把任务推到了 stored
        executor.mutateToStoredAfterQuerying = true;

        runner.process(taskId);

        // 拉回步在状态机校验处就被拒（stored → pulling 非法），子步根本没被调用
        assertEquals(List.of(TaskStatus.QUERYING), executor.executedStatuses);
        assertEquals(TaskStatus.STORED, store.get(taskId).getStatus());
        assertEquals(1, runner.failedCount());
        assertEquals(0, runner.processedCount());
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
        return task.getTaskId();
    }

    /** 记录型子步替身：记下被推到过哪些状态，可指定在某一步抛错。 */
    private static final class RecordingStepExecutor implements FetchTaskStepExecutor {

        private final List<TaskStatus> executedStatuses = new ArrayList<>();
        private TaskStatus failOn;
        private boolean mutateToStoredAfterQuerying;

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
                throw new RoutingFailureException(
                        RoutingFailureException.FailureCode.QUERY_TIMEOUT,
                        "C-FIND 响应超时", "PEER_HZ", "PAT_MPI_EXAMPLE");
            }
            if (targetStatus == TaskStatus.QUERYING && mutateToStoredAfterQuerying) {
                task.setStatus(TaskStatus.STORED);
            }
            return new FetchTaskStepOutcome("1.2.840.10008.5.1.4.1.1.2", 3, 512L, 12L);
        }
    }
}
