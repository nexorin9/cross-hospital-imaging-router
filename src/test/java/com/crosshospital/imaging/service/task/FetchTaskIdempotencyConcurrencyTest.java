package com.crosshospital.imaging.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.service.AuditService;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Task 23 — 并发入队的<b>数据库约束</b>核验（真实容器 + 真实 H2 + 真实唯一索引）。
 *
 * <p><b>为什么必须连真库</b>：幂等这件事的正确性最终由数据库的局部唯一索引兜底，
 * 而「唯一索引在并发下只让一个 INSERT 成功」这件事，用仓储替身是验不出来的 ——
 * 替身只会老老实实按你写的返回你写的。task 16/20/22 踩过的坑都在这里：
 * <b>替身证明不了数据库真的挡住了第二个写入</b>。
 *
 * <p><b>H2 上的局部唯一索引怎么还原 PostgreSQL 的 {@code WHERE} 谓词</b>：
 * PostgreSQL 的 V2 迁移写的是
 * {@code CREATE UNIQUE INDEX idx_fetch_tasks_idem ON imaging_fetch_tasks (patient_mpi, study_uid)
 * WHERE status IN ('queued','querying','pulling','stored')}，
 * H2 不支持带谓词的部分索引。本测试用<b>生成列</b>等价还原：
 * <pre>
 *   ALTER TABLE imaging_fetch_tasks ADD COLUMN idem_key VARCHAR(600)
 *     GENERATED ALWAYS AS (CASE WHEN status IN ('queued','querying','pulling','stored')
 *                               THEN patient_mpi || '|' || study_uid END);
 *   CREATE UNIQUE INDEX idx_h2_fetch_tasks_idem ON imaging_fetch_tasks (idem_key);
 * </pre>
 * 生成列在谓词不成立时为 {@code NULL}，而唯一索引把多个 {@code NULL} 视为互不相同 ——
 * 于是「不在谓词内的行互不冲突、在谓词内的行唯一」这条语义与 PostgreSQL 部分索引
 * <b>完全一致</b>（已实测：重复键报 23505，终态行 idem_key 为 NULL 不挡新行）。
 *
 * <p>真实 PostgreSQL 上的验证留待 task 35 的 Testcontainers 集成测试
 * （本机无 Docker / PostgreSQL 实例）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:idempotencyrace;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE;LOCK_TIMEOUT=20000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // 刻意<b>不</b>覆盖 maximum-pool-size —— 用产品 application.yml 的默认值（16），
        // 让本类直接对着生产连接池大小验收。
        // 一次入队<b>同时只占一条连接</b>（插入走 REQUIRES_NEW 独立事务，提交即归还连接；
        // 入队方法本身不开外层事务），所以并发数 = 池大小时仍是安全的。
        // （早前这里覆盖成 30，是因为当时入队方法还开着一个外层事务，一次入队占两条连接：
        //  16 个并发入队正好撞上产品默认的 16，连接池被抽干、集体卡到 connection-timeout。
        //  那个掩盖问题的覆盖已去掉，改由「并发入队不抽干连接池」一节守住这条不变量。）
        // minimum-idle 压到 2（Hikari 默认等于 maximum，会一上来就预建满池子，白吃堆）
        "spring.datasource.hikari.minimum-idle=2",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "hl7.trigger.port=0",
        "routing.local-move-dest-port=0"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Task 23 — 并发入队：数据库唯一约束只让一个任务落库")
class FetchTaskIdempotencyConcurrencyTest {

    private static final String PATIENT_MPI = "PAT_MPI_EXAMPLE";
    private static final String STUDY_UID = "STUDY_EXAMPLE_RACE";

    @Autowired
    private FetchTaskService fetchTaskService;

    @Autowired
    private FetchTaskRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuditService auditService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 连接池（只为拿到产品默认的池大小，好把并发数顶到池大小上）。 */
    @Autowired
    private DataSource dataSource;

    /** 协议子步用替身：本测试只验入队幂等，不跑 DICOM 协议栈。 */
    @MockBean
    private FetchTaskStepExecutor stepExecutor;

    /** 队列用替身：不让执行器真的去消费任务，断言才确定。 */
    @MockBean
    private FetchTaskQueuePort queue;

    @BeforeAll
    void installIdempotencyConstraint() {
        jdbcTemplate.execute("ALTER TABLE imaging_fetch_tasks ADD COLUMN IF NOT EXISTS idem_key VARCHAR(600) "
                + "GENERATED ALWAYS AS (CASE WHEN status IN ('queued','querying','pulling','stored') "
                + "THEN patient_mpi || '|' || study_uid END)");
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_h2_fetch_tasks_idem "
                + "ON imaging_fetch_tasks (idem_key)");
    }

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.update("DELETE FROM imaging_fetch_tasks");
    }

    // ------------------------------------------------------------------
    // 并发
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5 个线程同时入队同一 (patient_mpi, study_uid) → 只落 1 行，5 个线程拿到同一个 task_id")
    void fiveConcurrentEnqueuesCreateExactlyOneTask() throws Exception {
        List<UUID> taskIds = runConcurrently(5, STUDY_UID);

        Set<UUID> distinct = new HashSet<>(taskIds);
        assertEquals(1, distinct.size(), "5 个并发入队应当拿到同一个 task_id，实际：" + distinct);
        assertEquals(1, countRows(STUDY_UID), "同一个幂等键在库里只能有一行");
    }

    @Test
    @DisplayName("10 个线程同时入队同一 (patient_mpi, study_uid) → 数据库约束保证只 1 个成功")
    void tenConcurrentEnqueuesCreateExactlyOneTask() throws Exception {
        List<UUID> taskIds = runConcurrently(10, STUDY_UID);

        Set<UUID> distinct = new HashSet<>(taskIds);
        assertEquals(1, distinct.size(), "10 个并发入队应当拿到同一个 task_id，实际：" + distinct);
        assertEquals(1, countRows(STUDY_UID), "数据库唯一约束下只能落 1 行");
    }

    @Test
    @DisplayName("并发时只有一个任务被投进执行队列（其余线程复用它，不会跑两遍）")
    void onlyTheWinnerIsScheduled() throws Exception {
        runConcurrently(8, STUDY_UID);

        verify(queue, timeout(3000).times(1)).submit(any(UUID.class));
    }

    @Test
    @DisplayName("两个互相独立的入队服务实例并发入队 → 仍只落 1 行（此时只剩数据库约束兜底）")
    void twoIndependentServicesRelyOnTheDatabaseConstraint() throws Exception {
        // 第二个服务实例：自带一份幂等守卫（进程内预留表与容器里的那个不共享），
        // 于是两个线程之间<b>没有</b>进程内互斥 —— 能不能只落一行，
        // 完全取决于数据库的局部唯一索引。
        FetchTaskService standalone = new FetchTaskService(repository, queue, auditService);
        standalone.setTransactionManager(transactionManager);

        CountDownLatch startGun = new CountDownLatch(1);
        List<UUID> results = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                try {
                    startGun.await();
                    results.add(fetchTaskService.enqueue(request(STUDY_UID)));
                } catch (Throwable ex) {
                    failures.add(ex);
                }
            });
            pool.submit(() -> {
                try {
                    startGun.await();
                    results.add(standalone.enqueue(request(STUDY_UID)));
                } catch (Throwable ex) {
                    failures.add(ex);
                }
            });
            startGun.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发入队超时");
        } finally {
            pool.shutdownNow();
        }

        assertTrue(failures.isEmpty(), "并发入队不应有异常：" + failures);
        assertEquals(2, results.size());
        assertEquals(1, new HashSet<>(results).size(),
                "两个独立服务实例并发入队应当拿到同一个 task_id，实际：" + new HashSet<>(results));
        assertEquals(1, countRows(STUDY_UID), "只剩数据库唯一索引兜底时也只能落 1 行");
    }

    // ------------------------------------------------------------------
    // 约束本身
    // ------------------------------------------------------------------

    @Test
    @DisplayName("库里的唯一约束真的存在：绕过服务直接插重复行会被数据库挡下")
    void databaseConstraintReallyRejectsDuplicate() {
        fetchTaskService.enqueue(request(STUDY_UID));

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "insert into imaging_fetch_tasks (task_id, patient_mpi, study_uid, peer_aet, status, "
                        + "retry_count, initiated_by, initiated_src, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'queued', 0, 'employee_007', 'api', now(), now())",
                UUID.randomUUID(), PATIENT_MPI, STUDY_UID, "PEER_HZ"),
                "唯一约束没生效 —— 并发兜底就是假的");
    }

    @Test
    @DisplayName("任务到了终态就不再占幂等键：同一个检查再调阅会是一条新任务")
    void terminalTaskStopsBlockingTheKey() {
        UUID first = fetchTaskService.enqueue(request(STUDY_UID));

        // 走真实状态机推到终态（notified）
        fetchTaskService.advance(first, TaskStatus.QUERYING, "employee_007");
        fetchTaskService.advance(first, TaskStatus.PULLING, "employee_007");
        fetchTaskService.advance(first, TaskStatus.STORED, "employee_007");
        fetchTaskService.advance(first, TaskStatus.NOTIFIED, "employee_007");

        UUID second = fetchTaskService.enqueue(request(STUDY_UID));

        assertTrue(!first.equals(second), "终态任务不该被复用，应新建一条");
        assertEquals(2, countRows(STUDY_UID), "终态后同键应允许再建一条（与 PostgreSQL 部分索引语义一致）");
        assertEquals(2, repository.findByPatientMpiAndStudyUidOrderByCreatedAtDesc(PATIENT_MPI, STUDY_UID).size(),
                "仓储口径也应是两条历史任务（信息科查互认记录时看到的行数）");
    }

    @Test
    @DisplayName("不同 study_uid 互不影响（幂等键是复合键，不是单看患者）")
    void differentStudyUidCreatesDifferentTask() {
        UUID a = fetchTaskService.enqueue(request("STUDY_EXAMPLE_A"));
        UUID b = fetchTaskService.enqueue(request("STUDY_EXAMPLE_B"));

        assertTrue(!a.equals(b));
        assertEquals(1, countRows("STUDY_EXAMPLE_A"));
        assertEquals(1, countRows("STUDY_EXAMPLE_B"));
    }

    // ------------------------------------------------------------------
    // 连接池不变量（task 23 核查补正）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("并发入队不抽干连接池：并发数 = 产品默认池大小时全部成功且不卡在 connection-timeout")
    void concurrentEnqueuesDoNotExhaustTheConnectionPool() throws Exception {
        // 本类刻意不覆盖 maximum-pool-size —— 池大小就是产品 application.yml 的默认值。
        // 一次入队若同时占两条连接（外层事务 + 内层独立事务），并发一多就会互相等对方
        // 释放连接，一路卡到 connection-timeout（10s）后集体失败。
        // 并发数取<b>池大小的两倍</b>：一条连接就够时，2 倍并发只是排队；
        // 两条连接才够时，池子直接被抽干 —— 两种实现的结果截然不同，测试才能真正咬住。
        int threads = poolSize() * 2;

        long t0 = System.currentTimeMillis();
        List<UUID> taskIds = runConcurrently(threads, STUDY_UID);
        long elapsedMs = System.currentTimeMillis() - t0;

        assertEquals(1, new HashSet<>(taskIds).size(),
                threads + " 个并发入队应当拿到同一个 task_id，实际：" + new HashSet<>(taskIds));
        assertEquals(1, countRows(STUDY_UID), "并发入队只能落 1 行");
        assertTrue(elapsedMs < 5000L,
                "并发入队耗时 " + elapsedMs + "ms，疑似卡在连接池等待上（connection-timeout 是 10s）；"
                        + "一次入队应同时只占一条连接");
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 起 n 个线程同时入队同一个幂等键，返回各自的 task_id。 */
    private List<UUID> runConcurrently(int threads, String studyUid) throws Exception {
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<UUID> results = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startGun.await();
                        results.add(fetchTaskService.enqueue(request(studyUid)));
                    } catch (Throwable ex) {
                        failures.add(ex);
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGun.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "并发入队超时");
        } finally {
            pool.shutdownNow();
        }
        assertTrue(failures.isEmpty(), "并发入队不应有异常：" + failures);
        assertEquals(threads, results.size());
        return results;
    }

    /** 产品实际的连接池大小（本类不覆盖 maximum-pool-size，所以就是默认值）。 */
    private int poolSize() {
        return ((HikariDataSource) dataSource).getMaximumPoolSize();
    }

    /** 该幂等键在库里的行数。 */
    private int countRows(String studyUid) {        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from imaging_fetch_tasks where patient_mpi = ? and study_uid = ?",
                Integer.class, PATIENT_MPI, studyUid);
        assertNotNull(count);
        return count;
    }

    private static FetchTaskEnqueueRequest request(String studyUid) {
        return new FetchTaskEnqueueRequest(PATIENT_MPI, studyUid, "PEER_HZ", "employee_007",
                FetchTaskEnqueueRequest.SRC_API, "ACC_EXAMPLE", null);
    }
}
