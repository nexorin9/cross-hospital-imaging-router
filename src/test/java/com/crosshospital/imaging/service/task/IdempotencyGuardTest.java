package com.crosshospital.imaging.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.repository.FetchTaskRepository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 23 — 幂等守卫单元测试（Mockito 替身，不连数据库）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>幂等块状态判定（与 V2 局部唯一索引的谓词逐字对齐）；</li>
 *   <li>命中幂等键 → 复用既有任务；未终结期外 → 放行新任务；</li>
 *   <li>进程内预留的独占性与释放；</li>
 *   <li>等同键并发入队的<b>有界等待</b>：等得到就复用，等不到就交给数据库仲裁。</li>
 * </ul>
 */
@DisplayName("Task 23 — 幂等守卫")
class IdempotencyGuardTest {

    /** V2 里局部唯一索引的谓词抓取（只抓 idx_fetch_tasks_idem 这一处，不抓 CHECK 约束）。 */
    private static final Pattern IDEM_INDEX_PREDICATE = Pattern.compile(
            "CREATE\\s+UNIQUE\\s+INDEX\\s+idx_fetch_tasks_idem.*?"
                    + "WHERE\\s+status\\s+IN\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private FetchTaskRepository repository;
    private IdempotencyGuard guard;

    @BeforeEach
    void setUp() {
        repository = mock(FetchTaskRepository.class);
        guard = new IdempotencyGuard(repository, 300L);
    }

    // ------------------------------------------------------------------
    // 幂等块状态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("幂等块状态 = 未终结的四个态；notified / failed 不占位")
    void idempotentBlockStatusSet() {
        assertTrue(IdempotencyGuard.isIdempotentBlock(TaskStatus.QUEUED));
        assertTrue(IdempotencyGuard.isIdempotentBlock(TaskStatus.QUERYING));
        assertTrue(IdempotencyGuard.isIdempotentBlock(TaskStatus.PULLING));
        assertTrue(IdempotencyGuard.isIdempotentBlock(TaskStatus.STORED));
        assertFalse(IdempotencyGuard.isIdempotentBlock(TaskStatus.NOTIFIED));
        assertFalse(IdempotencyGuard.isIdempotentBlock(TaskStatus.FAILED));
        assertFalse(IdempotencyGuard.isIdempotentBlock(null));
    }

    @Test
    @DisplayName("代码里的幂等块状态集合与 V2 局部唯一索引的谓词逐字一致")
    void idempotentBlockStatesMatchMigrationPredicate() throws IOException {
        Set<String> fromCode = new LinkedHashSet<>(IdempotencyGuard.idempotentBlockDbValues());
        Set<String> fromMigration = readMigrationPredicate();

        assertEquals(4, fromCode.size(), "幂等块状态应有 4 个：" + fromCode);
        assertEquals(fromCode, fromMigration,
                "代码里的幂等块状态集合与 V2 索引谓词不一致 —— "
                        + "数据库会在生产上挡住一条本该允许的调阅（代码：" + fromCode
                        + " / 迁移：" + fromMigration + "）");
    }

    // ------------------------------------------------------------------
    // tryAcquire
    // ------------------------------------------------------------------

    @Test
    @DisplayName("命中幂等键：库里已有未终结任务 → 返回它（调用方复用其 task_id）")
    void tryAcquireReturnsExistingBlockingTask() {
        FetchTask existing = task(TaskStatus.QUEUED);
        when(repository.findActiveByPatientMpiAndStudyUid("PAT_MPI_EXAMPLE", "STUDY_EXAMPLE"))
                .thenReturn(Optional.of(existing));

        Optional<FetchTask> found = guard.tryAcquire("PAT_MPI_EXAMPLE", "STUDY_EXAMPLE");

        assertTrue(found.isPresent());
        assertEquals(existing.getTaskId(), found.get().getTaskId());
    }

    @Test
    @DisplayName("库里没有占位的任务 → 放行（返回空，调用方可以建新任务）")
    void tryAcquireReturnsEmptyWhenNothingBlocks() {
        when(repository.findActiveByPatientMpiAndStudyUid(anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertTrue(guard.tryAcquire("PAT_MPI_EXAMPLE", "STUDY_EXAMPLE").isEmpty());
    }

    @Test
    @DisplayName("终态任务（notified / failed）不占幂等键 → 放行新任务")
    void terminalTaskDoesNotBlockNewTask() {
        FetchTask terminal = task(TaskStatus.NOTIFIED);
        when(repository.findActiveByPatientMpiAndStudyUid(anyString(), anyString()))
                .thenReturn(Optional.of(terminal));

        assertTrue(guard.tryAcquire("PAT_MPI_EXAMPLE", "STUDY_EXAMPLE").isEmpty(),
                "已通知 / 已失败的任务不该再占着幂等键 —— 同一检查下次再调阅应当是一条新任务");
    }

    @Test
    @DisplayName("参数缺失直接拒绝（不查库）")
    void blankArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> guard.tryAcquire(null, "STUDY_EXAMPLE"));
        assertThrows(IllegalArgumentException.class, () -> guard.tryAcquire("PAT_MPI_EXAMPLE", " "));
        assertThrows(IllegalArgumentException.class,
                () -> guard.reserve("PAT_MPI_EXAMPLE", "STUDY_EXAMPLE", null));
    }

    // ------------------------------------------------------------------
    // 进程内预留
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一幂等键的预留是独占的：第二个线程抢不到")
    void reserveIsExclusivePerKey() {
        UUID mine = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertTrue(guard.reserve("PAT_MPI_EXAMPLE", "STUDY_EXAMPLE", mine));
        assertFalse(guard.reserve("PAT_MPI_EXAMPLE", "STUDY_EXAMPLE", other),
                "同一幂等键的第二个预留应当失败");
        assertTrue(guard.reserve("PAT_MPI_EXAMPLE", "STUDY_EXAMPLE_OTHER", other),
                "不同幂等键互不影响");
    }

    @Test
    @DisplayName("释放预留后同一个键可以重新占（否则一次插入失败会把键永久占死）")
    void releaseFreesTheKey() {
        UUID taskId = UUID.randomUUID();
        assertTrue(guard.reserve("PAT_MPI_EXAMPLE", "STUDY_EXAMPLE", taskId));
        assertEquals(1, guard.reservedCount());

        guard.release(taskId);

        assertEquals(0, guard.reservedCount());
        assertTrue(guard.reserve("PAT_MPI_EXAMPLE", "STUDY_EXAMPLE", UUID.randomUUID()),
                "释放后应当能重新占到这个键");
    }

    @Test
    @DisplayName("释放 null 是安全的空操作")
    void releaseNullIsNoop() {
        guard.release(null);
        assertEquals(0, guard.reservedCount());
    }

    // ------------------------------------------------------------------
    // 等同键并发入队
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同键正在入队时，等它落库后复用它的任务（而不是再插一条）")
    void tryAcquireWaitsForConcurrentReservationToLand() throws InterruptedException {
        AtomicReference<FetchTask> landed = new AtomicReference<>();
        when(repository.findActiveByPatientMpiAndStudyUid(anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(landed.get()));

        // 另一个线程先占住这个键
        assertTrue(guard.reserve("PAT_MPI_EXAMPLE", "STUDY_EXAMPLE", UUID.randomUUID()));

        // 它的任务稍后落库
        FetchTask winner = task(TaskStatus.QUEUED);
        Thread publisher = new Thread(() -> {
            try {
                Thread.sleep(60L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            landed.set(winner);
        });
        publisher.start();

        Optional<FetchTask> found = guard.tryAcquire("PAT_MPI_EXAMPLE", "STUDY_EXAMPLE");
        publisher.join();

        assertTrue(found.isPresent(), "应当等到同键先落库的任务");
        assertEquals(winner.getTaskId(), found.get().getTaskId());
    }

    @Test
    @DisplayName("等不到（对方插入失败）→ 返回空，交由数据库唯一索引仲裁")
    void awaitBlockingReturnsNullOnTimeout() {
        IdempotencyGuard impatient = new IdempotencyGuard(repository, 0L);
        when(repository.findActiveByPatientMpiAndStudyUid(anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertNull(impatient.awaitBlocking("PAT_MPI_EXAMPLE", "STUDY_EXAMPLE"));
        assertTrue(impatient.tryAcquire("PAT_MPI_EXAMPLE", "STUDY_EXAMPLE").isEmpty());
    }

    @Test
    @DisplayName("仓储不可用时守卫不让流程炸掉（查不到就当没命中）")
    void repositoryFailureIsNotSwallowedSilently() {
        when(repository.findActiveByPatientMpiAndStudyUid(anyString(), anyString()))
                .thenThrow(new IllegalStateException("库连不上"));
        assertThrows(IllegalStateException.class,
                () -> guard.tryAcquire("PAT_MPI_EXAMPLE", "STUDY_EXAMPLE"),
                "库故障必须让调用方看见 —— 静默当成「没命中」会插出第二条任务");
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 造一条任务行。 */
    private static FetchTask task(TaskStatus status) {
        FetchTask task = new FetchTask();
        task.setTaskId(UUID.randomUUID());
        task.setPatientMpi("PAT_MPI_EXAMPLE");
        task.setStudyUid("STUDY_EXAMPLE");
        task.setPeerAet("PEER_HZ");
        task.setStatus(status);
        task.setRetryCount(0);
        task.setInitiatedBy("employee_007");
        task.setInitiatedSrc(FetchTaskEnqueueRequest.SRC_API);
        return task;
    }

    /** 从 V2 迁移原文里解析出局部唯一索引的状态谓词清单。 */
    private static Set<String> readMigrationPredicate() throws IOException {
        String sql;
        try (InputStream in = IdempotencyGuardTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V2__imaging_fetch_tasks.sql")) {
            if (in == null) {
                throw new IllegalStateException("找不到 V2 迁移脚本");
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("读取 V2 迁移脚本失败", ex);
        }
        Matcher matcher = IDEM_INDEX_PREDICATE.matcher(sql);
        assertTrue(matcher.find(), "V2 迁移里找不到 idx_fetch_tasks_idem 的 WHERE 谓词");
        List<String> values = new ArrayList<>();
        for (String part : matcher.group(1).split(",")) {
            String v = part.trim().replace("'", "");
            if (!v.isEmpty()) {
                values.add(v);
            }
        }
        assertNotNull(values);
        return new LinkedHashSet<>(values);
    }
}
