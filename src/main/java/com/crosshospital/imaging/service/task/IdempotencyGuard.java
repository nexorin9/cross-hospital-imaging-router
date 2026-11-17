package com.crosshospital.imaging.service.task;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.repository.FetchTaskRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 幂等守卫 —— 「同一 (patient_mpi, study_uid) 在未终结期内只允许存在一条调阅任务」的唯一口径。
 *
 * <p><b>为什么需要它</b>：HIS 侧重投同一条调阅医嘱、医生在工作站上连点两下「调阅」、
 * HL7 触发消息被中间件重发 —— 这三种情况都会让<b>同一次跨院调阅</b>被受理两遍。
 * 后果不是「多一行日志」，而是<b>两次真实的跨院影像拉取</b>：外院 PACS 被拉两遍、
 * 本院 PACS 里落两份同样的影像、医生在报告栏看到两条一模一样的检查记录。
 * 所以幂等不是"锦上添花"，它是<b>跨院调阅这件事能算数的���提</b>。
 *
 * <p><b>两层防护，各管一段</b>：
 * <ol>
 *   <li><b>应用层（本类）</b>：入队前先查库，命中未终结任务就直接复用它的 task_id，
 *       不新建任务。绝大多数重复触发在这一层就被挡掉，不打扰数据库；</li>
 *   <li><b>数据库层（V2 的 {@code idx_fetch_tasks_idem}）</b>：局部唯一索引
 *       {@code UNIQUE (patient_mpi, study_uid) WHERE status IN
 *       ('queued','querying','pulling','stored')} 兜住并发 race ——
 *       两个线程同时「查不到 → 各自插入」时，数据库只让其中一个成功。</li>
 * </ol>
 * 两层缺一不可：只有应用层，两个线程会同时查到「没有」然后各插一行；
 * 只有数据库层，每次重复触发都要撞一次唯一索引异常（异常路径比命中路径贵得多，
 * 而且会把正常业务打成 ERROR 日志）。
 *
 * <p><b>幂等块状态集合</b>（{@code queued / querying / pulling / stored}）与 V2 索引的
 * {@code WHERE} 谓词<b>逐字对齐</b>：任务到了 {@code notified}（已推 HIS）或
 * {@code failed}（终局失败）就不再占位 —— 同一个病人同一��检查<b>下一次</b>再调阅时，
 * 应当是一条全新的任务，不是复活旧任务。
 *
 * <p><b>进程内预留表</b>（{@link #reservedByKey}）：同一 JVM 内、同一幂等键的并发入队，
 * 只让第一个线程去碰数据库，其余线程等它落库后直接复用它的 task_id。
 * 这不是正确性的保证（正确性由数据库唯一索引兜底），而是把「N 个线程撞 N-1 次唯一索引
 * 异常 + N-1 次回查」降成「1 次插入 + N-1 次等待」。
 *
 * <p><b>为什么不是 Spring bean</b>：本类只持有 {@link FetchTaskRepository} 与两张进程内
 * 映射表，没有需要外部配置的属性；由 {@link FetchTaskService} 直接持有（服务本身是单例，
 * 预留表随之单例），少一条装配边、少一个「注入的是哪个实例」的排障问题。
 *
 * <p>源主路径证据：源产品的 DICOM 关联层用「连接按 AET 配对、超出配对表直接拒」
 * 的方式保证同一台设备不会被两条关联同时驱动（{@code CompatibleConnection}）；
 * 本产品把同一套「先查配对表、命中就复用、配对表由数据库唯一约束兜底」的思路
 * 搬到<b>业务对象</b>（调阅任务）上。
 */
public class IdempotencyGuard {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyGuard.class);

    /** 等同键并发入队落库的最长等待时间（毫秒）。 */
    public static final long DEFAULT_ACQUIRE_WAIT_MS = 2000L;

    /** 等待期间的轮询间隔（毫秒）。 */
    private static final long POLL_INTERVAL_MS = 20L;

    /** 幂等键：患者主索引 + 影像引用（外院那次检查的唯一标识）。 */
    private record IdemKey(String patientMpi, String studyUid) { }

    private final FetchTaskRepository repository;

    /** 进程内「正在入队」的幂等键 → 本次分配到的 task_id。 */
    private final ConcurrentMap<IdemKey, UUID> reservedByKey = new ConcurrentHashMap<>();

    /** task_id → 幂等键（{@link #release} 时 O(1) 反查）。 */
    private final ConcurrentMap<UUID, IdemKey> keyByTaskId = new ConcurrentHashMap<>();

    /** 等同键并发入队落库的最长等待时间（毫秒）。 */
    private final long acquireWaitMs;

    public IdempotencyGuard(FetchTaskRepository repository) {
        this(repository, DEFAULT_ACQUIRE_WAIT_MS);
    }

    public IdempotencyGuard(FetchTaskRepository repository, long acquireWaitMs) {
        if (repository == null) {
            throw new IllegalArgumentException("FetchTaskRepository 不能为 null");
        }
        this.repository = repository;
        this.acquireWaitMs = Math.max(0L, acquireWaitMs);
    }

    /**
     * 该状态是否<b>占着</b>幂等键（未终结期内不允许同键再建第二条任务）。
     *
     * <p>与 V2 局部唯一索引 {@code idx_fetch_tasks_idem} 的谓词逐字对应：
     * {@code status IN ('queued','querying','pulling','stored')}。
     */
    public static boolean isIdempotentBlock(TaskStatus status) {
        return status != null && status.isIdempotentBlock();
    }

    /**
     * 幂等块状态的库内取值集合（小写码，与 V2 索引谓词一致）。
     *
     * <p>给「代码里的状态集合」与「迁移脚本里的状态清单」做对账用 ——
     * 两边一旦漂移，数据库约束就会在生产上挡住一条本该允许的调阅。
     */
    public static Set<String> idempotentBlockDbValues() {
        Set<String> values = new LinkedHashSet<>();
        for (TaskStatus status : TaskStatus.values()) {
            if (status.isIdempotentBlock()) {
                values.add(status.toDbValue());
            }
        }
        return values;
    }

    /**
     * 尝试占用幂等键 {@code (patient_mpi, study_uid)}。
     *
     * <p><b>返回非空</b>：命中幂等键 —— 库里已有一条处于幂等块状态的任务，
     * 调用方应<b>直接复用它的 task_id</b>，不得再建第二条任务。
     *
     * <p><b>返回空</b>：当前没有占着这个键的任务，调用方可以创建新任务。
     * 若同一 JVM 内已有同键正在入队，本方法会<b>等它落库</b>再给结论
     * （等不到才返回空，由数据库唯一索引兜底）。
     *
     * @param patientMpi 患者主索引（PID-3）
     * @param studyUid   外院那次检查的唯一标识
     */
    public Optional<FetchTask> tryAcquire(String patientMpi, String studyUid) {
        requireText(patientMpi, "patient_mpi");
        requireText(studyUid, "study_uid");
        IdemKey key = new IdemKey(patientMpi, studyUid);

        // ① 库里已有一条占着这个键的任务 → 命中幂等键
        Optional<FetchTask> existing = findBlocking(patientMpi, studyUid);
        if (existing.isPresent()) {
            return existing;
        }

        // ② 同进程内同键正在入队 → 等它落库，拿到它的 task_id
        if (reservedByKey.containsKey(key)) {
            FetchTask winner = awaitBlocking(patientMpi, studyUid);
            if (winner != null) {
                return Optional.of(winner);
            }
            // 等不到（对方插入失败并释放了）→ 交给调用方自己插入，数据库唯一索引兜底
            LOG.debug("幂等键等待超时（对方未落库），交由数据库唯一索引仲裁：patient_mpi={} study_uid={}",
                    patientMpi, studyUid);
        }
        return Optional.empty();
    }

    /**
     * 登记「正在入队」的进程内预留。
     *
     * <p>调用方在<b>真正插入之前</b>调用它：抢到预留的线程才有资格去碰数据库，
     * 其余线程应当改走 {@link #awaitBlocking} 等前者落库。
     *
     * @return true = 本次占到了预留；false = 同一幂等键已被别的线程预留
     */
    public boolean reserve(String patientMpi, String studyUid, UUID taskId) {
        requireText(patientMpi, "patient_mpi");
        requireText(studyUid, "study_uid");
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不能为 null");
        }
        IdemKey key = new IdemKey(patientMpi, studyUid);
        UUID prev = reservedByKey.putIfAbsent(key, taskId);
        if (prev != null && !prev.equals(taskId)) {
            return false;
        }
        keyByTaskId.put(taskId, key);
        return true;
    }

    /**
     * 释放预留 —— 插入成功或失败后都应调用。
     *
     * <p>插入成功时释放是安全的：任务行已落库，后续同键请求会在
     * {@link #tryAcquire} 的第 ① 步就命中它。
     * 插入失败时释放是<b>必须的</b>：否则这个键会被永久占住，
     * 下一次重投的调阅请求会一直等一个永远不会落库的任务。
     *
     * @param taskId 本次入队分配到的 task_id
     */
    public void release(UUID taskId) {
        if (taskId == null) {
            return;
        }
        IdemKey key = keyByTaskId.remove(taskId);
        if (key != null) {
            reservedByKey.remove(key, taskId);
            return;
        }
        // 兜底：预留登记与 task_id 绑定不一致时，按值反查清掉
        reservedByKey.values().remove(taskId);
    }

    /**
     * 查库：返回占着这个幂等键的任务（未终结期内的那一条），没有则空。
     *
     * <p>这是「命中幂等键」的唯一判定口径 —— 状态集合由
     * {@link com.crosshospital.imaging.repository.FetchTaskRepository}
     * 的 JPQL 限定为四个幂等块状态，这里再做一次同口径过滤（防御仓储替身返回越界状态）。
     */
    public Optional<FetchTask> findBlocking(String patientMpi, String studyUid) {
        return repository.findActiveByPatientMpiAndStudyUid(patientMpi, studyUid)
                .filter(task -> isIdempotentBlock(task.getStatus()));
    }

    /**
     * 等同键的并发入队落库（有界等待）。
     *
     * @return 等到的任务；超时仍未落库返回 {@code null}
     */
    public FetchTask awaitBlocking(String patientMpi, String studyUid) {
        long deadlineNanos = System.nanoTime() + acquireWaitMs * 1_000_000L;
        while (true) {
            Optional<FetchTask> found = findBlocking(patientMpi, studyUid);
            if (found.isPresent()) {
                return found.get();
            }
            if (System.nanoTime() >= deadlineNanos) {
                return null;
            }
            if (!sleep(POLL_INTERVAL_MS)) {
                return null;
            }
        }
    }

    /** 当前进程内被预留的幂等键数量（自检与排障用）。 */
    public int reservedCount() {
        return reservedByKey.size();
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
