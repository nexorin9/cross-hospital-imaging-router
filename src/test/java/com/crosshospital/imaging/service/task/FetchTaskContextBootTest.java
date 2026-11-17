package com.crosshospital.imaging.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.exception.IllegalTaskTransitionException;
import com.crosshospital.imaging.repository.FetchTaskRepository;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Task 20 — 真实 Spring 容器 + 真实 H2 持久化下的五态机核验。
 *
 * <p><b>为什么要单独起一个真实容器</b>：本仓库的多数单测用 Mockito 替身，
 * 而替身证明不了三件事：
 * <ol>
 *   <li>状态推进真的落到数据库（不是只改了内存对象）；</li>
 *   <li>JPQL / 派生查询方法名真的能跑（{@code findByPatientMpiAndStudyUidOrderByCreatedAtDesc}
 *       这类长方法名写错时，建仓阶段就抛 {@code QueryCreationException}）；</li>
 *   <li>新加的 bean 装配不绕成循环依赖（{@code FetchTaskService} → 子步执行器 →
 *       DICOM 协议栈 → 失败出口 → 回到 {@code FetchTaskService}）。</li>
 * </ol>
 *
 * <p>协议子步用 mock 顶掉（本测试验的是状态机与持久化，不是 DICOM 协议栈）。
 * 数据库用 H2（PostgreSQL 兼容模式），Flyway 关闭（迁移脚本是 PostgreSQL 方言，
 * 真实落库验证由后续任务的 Testcontainers 集成测试承担）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:fetchtaskboot;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "hl7.trigger.port=0",
        "routing.local-move-dest-port=0"
})
@DisplayName("Task 20 — 真实容器：五态机落库 + 装配正确")
class FetchTaskContextBootTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private FetchTaskService fetchTaskService;

    @Autowired
    private FetchTaskRepository fetchTaskRepository;

    /** 协议子步用替身：本测试只验状态机与持久化。 */
    @MockBean
    private FetchTaskStepExecutor stepExecutor;

    @BeforeEach
    void resetExecutor() {
        when(stepExecutor.supports(any(TaskStatus.class))).thenReturn(false);
    }

    @Test
    @DisplayName("上下文能起来，任务服务与执行器都在场（状态机校验器是纯静态工具类）")
    void contextStartsWithTaskBeans() {
        assertNotNull(context.getBean(FetchTaskService.class));
        assertNotNull(context.getBean(FetchTaskRepository.class));
        assertNotNull(context.getBean(FetchTaskRunner.class));
        // 状态机校验器刻意不做成 bean：它是纯逻辑件，不持有任何状态
        assertNotNull(TaskStateMachine.class);
        assertEquals("queued → failed / querying", TaskStateMachine.describe().split("\n")[0]);
    }

    @Test
    @DisplayName("合法跃迁真的落库（状态行刷新后仍是新状态）")
    void legalAdvanceIsPersisted() {
        FetchTask task = seedTask(TaskStatus.QUEUED);

        fetchTaskService.advance(task.getTaskId(), TaskStatus.QUERYING, "employee_007");

        FetchTask reloaded = fetchTaskRepository.findByTaskId(task.getTaskId()).orElseThrow();
        assertEquals(TaskStatus.QUERYING, reloaded.getStatus());
        assertNotNull(reloaded.getUpdatedAt());
    }

    @Test
    @DisplayName("非法跃迁被拒且数据库里的状态不变")
    void illegalAdvanceLeavesRowUnchanged() {
        FetchTask task = seedTask(TaskStatus.STORED);

        assertThrows(IllegalTaskTransitionException.class,
                () -> fetchTaskService.advance(task.getTaskId(), TaskStatus.QUERYING));

        FetchTask reloaded = fetchTaskRepository.findByTaskId(task.getTaskId()).orElseThrow();
        assertEquals(TaskStatus.STORED, reloaded.getStatus());
    }

    @Test
    @DisplayName("失败终态落库：状态 + 失败码都在行上")
    void failedTerminalStateIsPersisted() {
        FetchTask task = seedTask(TaskStatus.QUERYING);

        // 用**不可重试**的失败码（AE 未登记 / TLS 配错这类「改配置才能好」的故障）：
        // 可重试的失败在 task 21 之后会先退避重试，不会直接落到失败终态。
        fetchTaskService.markFailed(task.getTaskId().toString(), "tls_mismatch");

        FetchTask reloaded = fetchTaskRepository.findByTaskId(task.getTaskId()).orElseThrow();
        assertEquals(TaskStatus.FAILED, reloaded.getStatus());
        assertEquals("tls_mismatch", reloaded.getFailureCode());
        assertTrue(fetchTaskService.isTerminal(task.getTaskId()));
    }

    @Test
    @DisplayName("可重试失败不再直接判死：任务退回 queued 等重试（task 21）")
    void retryableFailureIsRequeuedInsteadOfFailed() {
        FetchTask task = seedTask(TaskStatus.QUERYING);

        fetchTaskService.markFailed(task.getTaskId().toString(), "connection_refused");

        FetchTask reloaded = fetchTaskRepository.findByTaskId(task.getTaskId()).orElseThrow();
        assertEquals(TaskStatus.QUEUED, reloaded.getStatus(), "可重试失败应退回 queued 等待重跑");
        assertEquals(1, reloaded.getRetryCount());
        assertEquals("connection_refused", reloaded.getFailureCode());
        assertFalse(fetchTaskService.isTerminal(task.getTaskId()));
    }

    @Test
    @DisplayName("幂等去重在真实库生效：同一 patient_mpi + study_uid 只有一行")
    void compositeIdempotencyHoldsInRealDb() {
        FetchTaskEnqueueRequest request = new FetchTaskEnqueueRequest(
                "PAT_MPI_EXAMPLE", "STUDY_EXAMPLE_BOOT", "PEER_HZ", "employee_007",
                FetchTaskEnqueueRequest.SRC_API, null, null);

        UUID first = fetchTaskService.enqueue(request);
        UUID second = fetchTaskService.enqueue(request);

        assertEquals(first, second);
        assertEquals(1, fetchTaskService
                .findHistoryByPatientMpiAndStudyUid("PAT_MPI_EXAMPLE", "STUDY_EXAMPLE_BOOT").size());
    }

    @Test
    @DisplayName("推进到 querying 时协议子步被调用一次（状态与子步对得上）")
    void stepExecutorIsTriggeredByStatus() {
        when(stepExecutor.supports(TaskStatus.QUERYING)).thenReturn(true);
        when(stepExecutor.execute(any(), any(), any())).thenReturn(
                new FetchTaskStepOutcome("1.2.840.10008.5.1.4.1.1.2", 7, 2048L, 33L));
        FetchTask task = seedTask(TaskStatus.QUEUED);

        fetchTaskService.advance(task.getTaskId(), TaskStatus.QUERYING);

        verify(stepExecutor, times(1)).execute(any(), any(), org.mockito.ArgumentMatchers.eq(TaskStatus.QUERYING));
        FetchTask reloaded = fetchTaskRepository.findByTaskId(task.getTaskId()).orElseThrow();
        assertEquals("1.2.840.10008.5.1.4.1.1.2", reloaded.getSopClassUid());
        assertEquals(7, reloaded.getSopInstanceCount());
    }

    private FetchTask seedTask(TaskStatus status) {
        FetchTask task = new FetchTask();
        task.setTaskId(UUID.randomUUID());
        task.setPatientMpi("PAT_MPI_EXAMPLE");
        task.setStudyUid("STUDY_EXAMPLE_" + status.toDbValue());
        task.setPeerAet("PEER_HZ");
        task.setAccessionNo("ACC_EXAMPLE");
        task.setStatus(status);
        task.setRetryCount(0);
        task.setInitiatedBy("employee_007");
        task.setInitiatedSrc("api");
        return fetchTaskRepository.saveAndFlush(task);
    }
}
