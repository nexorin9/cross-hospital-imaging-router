package com.crosshospital.imaging.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.service.AuditService;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Task 23 — 入队幂等的服务层单元测试（Mockito 替身，不连数据库）。
 *
 * <p>覆盖三条主线：
 * <ol>
 *   <li><b>命中幂等键</b>：库里已有未终结任务 → 复用它的 task_id，<b>不</b>新建任务、
 *       <b>不</b>重复投队列（否则一次调阅会被跑两遍）；</li>
 *   <li><b>并发落败</b>：插入撞数据库唯一索引 → 回查先落库的那条并复用它的 task_id；</li>
 *   <li><b>真故障不被吞</b>：插入失败且回查不到先落库者 → 原样抛出。</li>
 * </ol>
 */
@DisplayName("Task 23 — 入队幂等（服务层）")
class FetchTaskIdempotencyServiceTest {

    private static final String PATIENT_MPI = "PAT_MPI_EXAMPLE";
    private static final String STUDY_UID = "STUDY_EXAMPLE_IDEM";

    private FetchTaskRepository repository;
    private InMemoryFetchTaskQueue queue;
    private FetchTaskService service;

    @BeforeEach
    void setUp() {
        repository = mock(FetchTaskRepository.class);
        queue = new InMemoryFetchTaskQueue(16);
        service = new FetchTaskService(repository, queue, new AuditService(mock(AuditRepository.class)));

        when(repository.save(any(FetchTask.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findActiveByPatientMpiAndStudyUid(any(), any())).thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------------
    // 第一层：命中幂等键
    // ------------------------------------------------------------------

    @Test
    @DisplayName("命中幂等键：复用既有 task_id，不新建任务、不重复投队列")
    void existingTaskIsReusedWithoutNewInsert() {
        FetchTask existing = task(TaskStatus.QUEUED);
        when(repository.findActiveByPatientMpiAndStudyUid(PATIENT_MPI, STUDY_UID))
                .thenReturn(Optional.of(existing));

        UUID taskId = service.enqueue(request());

        assertEquals(existing.getTaskId(), taskId, "应复用既有任务的 id");
        verify(repository, never()).save(any(FetchTask.class));
        assertEquals(0, queue.submittedCount(), "复用既有任务时不应再投一次队列（否则同一任务会被跑两遍）");
    }

    @Test
    @DisplayName("终态任务（notified / failed）不占幂等键 → 建一条新任务")
    void terminalTaskDoesNotPreventNewEnqueue() {
        FetchTask terminal = task(TaskStatus.NOTIFIED);
        when(repository.findActiveByPatientMpiAndStudyUid(PATIENT_MPI, STUDY_UID))
                .thenReturn(Optional.of(terminal));

        UUID taskId = service.enqueue(request());

        assertNotEquals(terminal.getTaskId(), taskId, "已终结的任务不该被复用");
        verify(repository, times(1)).save(any(FetchTask.class));
        assertEquals(1, queue.submittedCount());
    }

    // ------------------------------------------------------------------
    // 第二层：数据库唯一索引兜底
    // ------------------------------------------------------------------

    @Test
    @DisplayName("并发落败：插入撞唯一索引 → 回查先落库者并复用它的 task_id")
    void uniqueViolationFallsBackToTheWinner() {
        FetchTask winner = task(TaskStatus.QUEUED);
        // 第 1 次查：没有占位的任务；第 2 次查（回查）：并发线程先落库了
        when(repository.findActiveByPatientMpiAndStudyUid(PATIENT_MPI, STUDY_UID))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.save(any(FetchTask.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "could not execute statement; Unique index or primary key violation: "
                                + "\"PUBLIC.IDX_FETCH_TASKS_IDEM ON PUBLIC.IMAGING_FETCH_TASKS\""));

        UUID taskId = service.enqueue(request());

        assertEquals(winner.getTaskId(), taskId, "并发落败方应复用先落库者的 task_id");
        assertEquals(0, queue.submittedCount(), "落败方不应再投队列（赢家已经投过了）");
    }

    @Test
    @DisplayName("插入失败且回查不到先落库者 → 原样抛出（真故障不被当成幂等吞掉）")
    void nonIdempotentFailureIsRethrown() {
        when(repository.save(any(FetchTask.class)))
                .thenThrow(new DataIntegrityViolationException("null value in column \"peer_aet\""));

        assertThrows(DataIntegrityViolationException.class, () -> service.enqueue(request()));
        assertEquals(0, queue.submittedCount());
    }

    @Test
    @DisplayName("非完整性异常（编程错误）原样抛出，不当幂等处理")
    void unexpectedRuntimeExceptionIsRethrown() {
        when(repository.save(any(FetchTask.class)))
                .thenThrow(new IllegalStateException("连接池耗尽"));

        assertThrows(IllegalStateException.class, () -> service.enqueue(request()));
    }

    // ------------------------------------------------------------------
    // 正常路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新键入队：插入一行并投一次队列")
    void freshKeyCreatesOneTaskAndSchedulesIt() {
        UUID taskId = service.enqueue(request());

        assertNotNull(taskId);
        verify(repository, times(1)).save(any(FetchTask.class));
        assertEquals(1, queue.submittedCount());
        assertEquals(taskId, queue.drain().get(0));
    }

    @Test
    @DisplayName("入队参数缺失直接拒绝（不碰库）")
    void invalidRequestIsRejectedBeforeAnyDbCall() {
        FetchTaskEnqueueRequest bad = new FetchTaskEnqueueRequest(
                "", STUDY_UID, "PEER_HZ", "employee_007", FetchTaskEnqueueRequest.SRC_API, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.enqueue(bad));
        verify(repository, never()).save(any(FetchTask.class));
    }

    @Test
    @DisplayName("预留用完即释放：插入成功后键不再被进程内占住（否则下一次入队会被永久挡住）")
    void reservationIsReleasedAfterInsert() {
        service.enqueue(request());

        // 第二次入队：库里已有未终结任务 → 复用；进程内不应还有残留预留
        verify(repository, times(1)).save(any(FetchTask.class));
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static FetchTaskEnqueueRequest request() {
        return new FetchTaskEnqueueRequest(PATIENT_MPI, STUDY_UID, "PEER_HZ", "employee_007",
                FetchTaskEnqueueRequest.SRC_API, "ACC_EXAMPLE", null);
    }

    private static FetchTask task(TaskStatus status) {
        FetchTask task = new FetchTask();
        task.setTaskId(UUID.randomUUID());
        task.setPatientMpi(PATIENT_MPI);
        task.setStudyUid(STUDY_UID);
        task.setPeerAet("PEER_HZ");
        task.setStatus(status);
        task.setRetryCount(0);
        task.setInitiatedBy("employee_007");
        task.setInitiatedSrc(FetchTaskEnqueueRequest.SRC_API);
        return task;
    }
}
