package com.crosshospital.imaging.service.task;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.crosshospital.imaging.config.TaskContext;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.exception.IllegalTaskTransitionException;
import com.crosshospital.imaging.exception.NotFoundException;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.metrics.ImagingMetrics;
import com.crosshospital.imaging.service.routing.DlqEnqueuePort;
import com.crosshospital.imaging.service.routing.DlqEnqueueRequest;
import com.crosshospital.imaging.service.routing.FailureCodeRegistry;
import com.crosshospital.imaging.service.routing.FetchTaskFailurePort;
import com.crosshospital.imaging.service.routing.RoutingFailureContext;
import com.crosshospital.imaging.service.routing.TaskFailureOutcome;

import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 调阅任务服务 —— 入队（task 19）+ 五态机推进（task 20）。
 *
 * <p><b>为什么入队这件事不能省</b>：HL7 触发消息收到后如果只记一行日志就 ACK，
 * 一次跨院调阅就永远不会发生，而 HIS 侧看到的是"成功"。本服务把触发变成
 * {@code imaging_fetch_tasks} 里一行 {@code status=queued} 的真实业务状态 ——
 * 这行状态可查、可被卡死扫描捞起、可被信息科在时间窗里核对。
 *
 * <p><b>五态机是本服务的另一半</b>：一次调阅要经过
 * {@code queued → querying → pulling → stored → notified}，
 * 中间任何一步都可能失败（外院拒接、传输语法不兼容、网���抖动）。
 * 状态字段是信息科唯一能看见的「这次调阅到哪一步了」，
 * 所以它必须：
 * <ul>
 *   <li><b>只进不退</b> —— 非法跃迁（如 {@code stored → querying}）一律拒绝并抛
 *       {@link IllegalTaskTransitionException}，绝不"纠正着推"；</li>
 *   <li><b>幂等</b> —— 重复推进（网络重投、重复回调）不报错、不重复落审计，
 *       状态原地不动；</li>
 *   <li><b>每态留痕</b> —— 每次推进落一行审计，失败另落一行带失败码；</li>
 *   <li><b>失败可查</b> —— 失败时把失败码写进任务行，并推进死信。</li>
 * </ul>
 *
 * <p><b>幂等键 = (patient_mpi, study_uid)</b>，两层防护：
 * <ol>
 *   <li>应用层：先查 {@code findActiveByPatientMpiAndStudyUid}，命中就返回既有 task_id，
 *       不再建第二个任务 —— HIS 侧重投同一条医嘱不会产生两条调阅；</li>
 *   <li>数据库层：V2 的 UNIQUE INDEX {@code idx_fetch_tasks_idem}
 *       （{@code WHERE status IN ('queued','querying','pulling','stored')}）兜住并发 race。</li>
 * </ol>
 *
 * <p><b>投队列的时机</b>：任务行先落库、事务提交后<b>才</b>投队列。
 * 反过来（先投队列再落库）会让执行侧拿到一个还没提交、查不到的 task_id，
 * 出现"队列里排着、库里查无此项"的幽灵任务。
 *
 * <p><b>与失败处置的分工</b>（避免同一次失败落两行审计、两行死信）：
 * <ul>
 *   <li>{@link #markFailed(String, String)} 实现 {@link FetchTaskFailurePort}，
 *       只做「推进终态 + 落审计」，<b>不</b>进死信 —— 因为调用方
 *       （task 16 的 {@code RoutingFailureHandler}）自己已经进过死信了；</li>
 *   <li>本服务自己发现子步失败时（{@link #advance} 内部）走
 *       {@link #markFailedAndDeadLetter}，死信只落一次。</li>
 * </ul>
 *
 * <p>源主路径证据：{@code github_ref/dcm4che/dcm4che-net-audit} 应用层审计模型 ——
 * 任务落库 + 审计落库两条独立写入线；以及
 * {@code github_ref/dcm4che/dcm4che-net/.../ConnectionMonitor.java:49-58} 的五态回调 ——
 * 源产品把一次连接的生命周期切成可枚举的几段，本产品把同一套思路搬到业务对象上。
 */
@Service
@Profile("!test")
public class FetchTaskService implements FetchTaskFailurePort {

    private static final Logger LOG = LoggerFactory.getLogger(FetchTaskService.class);

    /** 审计终态前缀：{@code task_} + 状态名（queued/querying/...）。 */
    private static final String AUDIT_PREFIX_TASK = "task_";
    /** 审计终态：任务被判失败。 */
    private static final String AUDIT_TASK_FAILED = "task_failed";

    private final FetchTaskRepository repository;
    private final FetchTaskQueuePort queue;
    private final AuditService auditService;

    /**
     * 幂等守卫（task 23）：判定「同一 (patient_mpi, study_uid) 是否已有一条未终结的任务」。
     *
     * <p>默认由本服务直接持有（它只需要同一个仓储），也可用
     * {@link #setIdempotencyGuard} 换成带自定义等待时长的实例。
     */
    private volatile IdempotencyGuard idempotencyGuard;

    /**
     * 事务管理器（可空）。
     *
     * <p>装了它，入队的 INSERT 会在<b>独立事务</b>（{@code REQUIRES_NEW}）里执行 ——
     * 并发撞唯一索引时，内层事务回滚、外层事务仍然可用，
     * 本服务才能在同一个请求里回查「谁先落库了」并复用它的 task_id。
     * 未注入时退化为「就地 INSERT」（单元测试与无事务容器的场景）。
     */
    private volatile PlatformTransactionManager transactionManager;

    /** 协议子步执行器（可空：未接入时只做状态推进，不跑协议）。 */
    private volatile FetchTaskStepExecutor stepExecutor;
    /** 死信入队端口（可空：task 22 落地前不存在实现）。 */
    private volatile DlqEnqueuePort dlqEnqueuePort;
    /** 任务级重试端口（可空：task 21 的 {@code RetryScheduler}；未接入时失败即终态）。 */
    private volatile TaskRetryPort taskRetryPort;
    /**
     * AI 增强端口（可空：task 27 引入；task 28 报告归一 / task 29 互认初筛各自实现）。
     *
     * <p>未接入时本服务的行为与「没有 AI 这一层」完全一致 —— 主路径不依赖增强层。
     */
    private volatile AiEnrichmentPort aiEnrichmentPort;
    /**
     * HIS 通知端口（可空：task 32 引入）。
     *
     * <p>只在任务推进到 notified 后调用；端口失败<b>不回退状态</b> ——
     * 影像已经在本院 PACS 里，回调失败应该落死信补推，而不是把一次成功的调阅翻成失败。
     */
    private volatile NotificationPort notificationPort;
    /**
     * 运行指标（task 37）。可空：单测直接 {@code new FetchTaskService(...)} 时
     * 没有指标门面，行为与没有可观测层完全一致。
     */
    private volatile ImagingMetrics metrics;

    public FetchTaskService(FetchTaskRepository repository, FetchTaskQueuePort queue) {
        this(repository, queue, null);
    }

    @Autowired
    public FetchTaskService(FetchTaskRepository repository,
                            FetchTaskQueuePort queue,
                            AuditService auditService) {
        if (repository == null || queue == null) {
            throw new IllegalArgumentException("FetchTaskService 依赖不能为 null");
        }
        this.repository = repository;
        this.queue = queue;
        this.auditService = auditService;
        this.idempotencyGuard = new IdempotencyGuard(repository);
    }

    /** 注入自定义幂等守卫（测试用：可调等待时长）。 */
    public void setIdempotencyGuard(IdempotencyGuard idempotencyGuard) {
        if (idempotencyGuard == null) {
            throw new IllegalArgumentException("IdempotencyGuard 不能为 null");
        }
        this.idempotencyGuard = idempotencyGuard;
    }

    /**
     * 注入事务管理器（生产环境由容器注入）。
     *
     * <p>setter 注入而非构造注入：单元测试大量直接 {@code new FetchTaskService(...)}，
     * 构造注入会把测试全部逼到改签名；这里用可选 setter，
     * <b>有</b>事务管理器走独立事务插入，<b>没有</b>就地插入 —— 两条路径都验过。
     */
    @Autowired(required = false)
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    /**
     * 注入协议子步执行器（{@code RoutingStepExecutor}）。
     *
     * <p>用 setter 注入而不是构造注入：{@code RoutingStepExecutor} 依赖 DICOM 协议栈，
     * 而协议栈的失败出口（{@code RoutingFailureHandler}）又依赖本服务 ——
     * 构造注入会绕成环，setter 注入在容器里才解得开。
     */
    @Autowired(required = false)
    public void setStepExecutor(FetchTaskStepExecutor stepExecutor) {
        this.stepExecutor = stepExecutor;
    }

    /** 注入死信入队端口（task 22 的 {@code DlqService}）。 */
    @Autowired(required = false)
    public void setDlqEnqueuePort(DlqEnqueuePort dlqEnqueuePort) {
        this.dlqEnqueuePort = dlqEnqueuePort;
    }

    /**
     * 注入任务级重试端口（task 21 的 {@code RetryScheduler}）。
     *
     * <p>setter 注入而非构造注入：{@code RetryScheduler} 需要本服务把任务退回 queued，
     * 构造注入会绕成环；setter 注入在容器里才解得开。
     *
     * <p>未注入时行为不变：一次失败即终局失败（推 failed + 落审计 + 进死信）。
     */
    @Autowired(required = false)
    public void setTaskRetryPort(TaskRetryPort taskRetryPort) {
        this.taskRetryPort = taskRetryPort;
    }

    /**
     * 注入 AI 增强端口（task 27）。
     *
     * <p>用 setter 注入而不是构造注入：AI 增强层（报告归一 / 互认初筛）依赖
     * {@code ModelGatewayWithFallback}，而增强层的落库又发生在任务推进过程中 ——
     * 构造注入会让测试全部逼到改签名。未注入时本服务不做任何 AI 相关动作。
     */
    @Autowired(required = false)
    public void setAiEnrichmentPort(AiEnrichmentPort aiEnrichmentPort) {
        this.aiEnrichmentPort = aiEnrichmentPort;
    }

    /** 注入 HIS 通知端口（task 32）；未注入时 notified 之后不做任何外发动作。 */
    @Autowired(required = false)
    public void setNotificationPort(NotificationPort notificationPort) {
        this.notificationPort = notificationPort;
    }

    /** 运行指标门面（task 37，容器按需注入；未注入时全部埋点自动旁路）。 */
    @Autowired(required = false)
    public void setMetrics(ImagingMetrics metrics) {
        this.metrics = metrics;
    }

    // ==================================================================
    // 入队（task 19）
    // ==================================================================

    /**
     * 入队一条调阅任务（幂等）。
     *
     * <p><b>幂等键 = (patient_mpi, study_uid)</b>，两层防护（task 23 落地）：
     * <ol>
     *   <li><b>应用层</b>：{@link IdempotencyGuard#tryAcquire} 先查库里占着这个键的任务，
     *       命中就<b>直接复用它的 task_id</b>，不再建第二条任务；</li>
     *   <li><b>数据库层</b>：并发下两个线程同时「查不到 → 各自插入」时，
     *       V2 的局部唯一索引 {@code idx_fetch_tasks_idem} 只让其中一个成功，
     *       落败的那个捕获唯一约束冲突后<b>回查并复用先落库者的 task_id</b>。</li>
     * </ol>
     *
     * <p><b>为什么插入要单独开一个事务</b>：唯一约束冲突会让当前事务变成
     * 「已中止」状态（PostgreSQL 上后续任何语句都报 {@code current transaction is aborted}），
     * 同一个事务里根本没法再回查「谁先落库了」。所以插入走
     * {@code REQUIRES_NEW} 独立事务：内层回滚、外层照常可用，回查才查得到。
     *
     * <p><b>为什么本方法自己不开事务</b>（task 23 核查补正）：插入既然已经在
     * {@code REQUIRES_NEW} 独立事务里提交，本方法再开一个外层事务就只剩一个作用 ——
     * <b>多占一条连接</b>。外层事务从头到尾持着连接不放（等它提交才还），内层事务
     * 又要另借一条：一次入队同时占两条连接。并发入队一多，连接池会被瞬间抽干 ——
     * 16 个并发入队正好撞上产品默认的 {@code maximum-pool-size: 16}，
     * 全部线程各持一条外层连接、内层事务一条也借不到，互相等对方释放，
     * 直到 {@code connection-timeout}（10s）到期集体抛
     * {@code CannotCreateTransactionException}。
     * <p>去掉外层事务后，一次入队<b>同时只占一条连接</b>（内层事务提交即归还），
     * 并发上限由池子大小正常决定，不再出现「越并发越全挂」。
     * <p>去掉外层事务还顺带消掉了一个隐患：唯一约束冲突后的「回查赢家」现在跑在
     * <b>一个全新的只读事务</b>里（而不是一个快照早于赢家提交的事务里），
     * 所以在 PostgreSQL 把隔离级别调到 {@code REPEATABLE READ} 时也仍能查到赢家。
     *
     * @param request 入队内容（患者主索引 / 影像引用 / 承接节点 / 发起人 / 来源）
     * @return 任务 UUID；命中幂等键时返回<b>既有</b>任务的 id
     */
    public UUID enqueue(FetchTaskEnqueueRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("FetchTaskEnqueueRequest 不能为 null");
        }
        requireText(request.patientMpi(), "patient_mpi");
        requireText(request.studyUid(), "study_uid");
        requireText(request.peerAet(), "peer_aet");
        requireText(request.initiatedBy(), "initiated_by");
        requireText(request.initiatedSrc(), "initiated_src");

        // 第一层幂等：未终结期内已有同 (patient_mpi, study_uid) 的任务 → 直接复用
        Optional<FetchTask> existing =
                idempotencyGuard.tryAcquire(request.patientMpi(), request.studyUid());
        if (existing.isPresent()) {
            UUID taskId = existing.get().getTaskId();
            LOG.info("调阅任务命中幂等键，复用既有任务：taskId={} patient_mpi={} study_uid={} status={}",
                    taskId, request.patientMpi(), request.studyUid(), existing.get().getStatus());
            return taskId;
        }

        FetchTask task = new FetchTask();
        task.setTaskId(UUID.randomUUID());
        task.setPatientMpi(request.patientMpi());
        task.setStudyUid(request.studyUid());
        task.setPeerAet(request.peerAet());
        task.setAccessionNo(request.accessionNo());
        task.setSopClassUid(request.sopClassUid());
        task.setStatus(TaskStatus.QUEUED);
        task.setRetryCount(0);
        task.setInitiatedBy(request.initiatedBy());
        task.setInitiatedSrc(request.initiatedSrc());
        UUID taskId = task.getTaskId();

        // 进程内预留：同一 JVM 内同键的并发入队，只让一个线程去碰数据库
        if (!idempotencyGuard.reserve(request.patientMpi(), request.studyUid(), taskId)) {
            Optional<FetchTask> winner =
                    Optional.ofNullable(idempotencyGuard.awaitBlocking(request.patientMpi(), request.studyUid()));
            if (winner.isPresent()) {
                UUID winnerId = winner.get().getTaskId();
                LOG.info("同一幂等键正在入队，等待后复用先落库的任务：taskId={} patient_mpi={} study_uid={}",
                        winnerId, request.patientMpi(), request.studyUid());
                return winnerId;
            }
        }

        try {
            FetchTask saved = insertIsolated(task);
            UUID savedId = saved.getTaskId();
            if (metrics != null) {
                metrics.fetchAccepted();
            }
            LOG.info("调阅任务已入队：taskId={} patient_mpi={} study_uid={} peer_aet={} src={}",
                    savedId, request.patientMpi(), request.studyUid(), request.peerAet(), request.initiatedSrc());

            scheduleExecution(savedId);
            return savedId;
        } catch (DataIntegrityViolationException ex) {
            // 第二层幂等：数据库唯一索引挡住了并发插入 → 回查先落库的那条并复用它的 id
            UUID winnerId = resolveConcurrentWinner(request, ex);
            if (winnerId != null) {
                return winnerId;
            }
            // 回查不到 = 不是幂等键冲突（可能是外键 / 非空约束）→ 原样抛出，别把真故障吞了
            LOG.error("调阅任务入队失败（回查不到并发先落库者，按真实约束冲突处理）：patient_mpi={} study_uid={} msg={}",
                    request.patientMpi(), request.studyUid(), ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            // Spring 视版本而定，唯一约束冲突有时以原始 Hibernate 异常冒出来（而不是
            // DataIntegrityViolationException）—— 一并按「可能是幂等冲突」处理，
            // 但只要回查不到先落库者，就原样抛出，绝不把真故障当成幂等吞掉。
            if (!isUniqueConstraintViolation(ex)) {
                throw ex;
            }
            UUID winnerId = resolveConcurrentWinner(request, ex);
            if (winnerId != null) {
                return winnerId;
            }
            throw ex;
        } finally {
            idempotencyGuard.release(taskId);
        }
    }

    /**
     * 回查「谁先落库了」——并发入队时的赢家。
     *
     * @return 赢家的 task_id；库里没有占着这个幂等键的任务时返回 {@code null}
     */
    private UUID resolveConcurrentWinner(FetchTaskEnqueueRequest request, RuntimeException cause) {
        Optional<FetchTask> winner =
                idempotencyGuard.findBlocking(request.patientMpi(), request.studyUid());
        if (winner.isEmpty()) {
            return null;
        }
        UUID winnerId = winner.get().getTaskId();
        LOG.info("并发入队撞数据库幂等唯一索引，复用先落库的任务：taskId={} patient_mpi={} study_uid={} cause={}",
                winnerId, request.patientMpi(), request.studyUid(), cause.getClass().getSimpleName());
        return winnerId;
    }

    /**
     * 判断异常链里是否有<b>唯一约束</b>冲突。
     *
     * <p>识别三种形态：Spring 的 {@link DataIntegrityViolationException}、
     * Hibernate 的 {@code ConstraintViolationException}、
     * 以及 JDBC 层 SQLState {@code 23505}（unique_violation）。
     * 只认「唯一约束」，不把 NOT NULL / 外键违约当成幂等冲突 ——
     * 那两类是真实故障，必须原样抛出去。
     */
    private static boolean isUniqueConstraintViolation(Throwable ex) {
        for (Throwable t = ex; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            if (t instanceof DataIntegrityViolationException) {
                // Spring 已归一化的完整性异常：再往链下看有没有更精确的 unique 语义
                if (containsUniqueMarker(t)) {
                    return true;
                }
                // 没有 unique 语义时按「可能是幂等冲突」处理 —— 回查查不到就会原样抛出
                return true;
            }
            if (t instanceof org.hibernate.exception.ConstraintViolationException) {
                return true;
            }
            if (t instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if ("23505".equals(state)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 异常链里是否出现「唯一 / unique」字样（Hibernate 与部分驱动的报错文案）。 */
    private static boolean containsUniqueMarker(Throwable ex) {
        for (Throwable t = ex; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            String message = t.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("unique") || lower.contains("duplicate")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 插入任务行 —— 有事务管理器时走<b>独立事务</b>。
     *
     * <p>独立事务的意义有两个：
     * <ol>
     *   <li>唯一约束冲突只回滚内层事务，{@link #enqueue} 才能在同一个请求里
     *       回查先落库的那条任务（PostgreSQL 上冲突后当前事务已中止，同事务查不到）；</li>
     *   <li>内层事务<b>提交即归还连接</b> —— 入队全程只占一条连接，
     *       不会因为外层还挂着一个事务而把连接池抽干（见 {@link #enqueue} 的说明）。</li>
     * </ol>
     *
     * <p>插入后立即 {@code flush()}：不 flush 的话 INSERT 要等到事务提交才发出去，
     * 唯一约束冲突会发生在方法<b>返回之后</b>（提交时），
     * 上面那段「捕获冲突 → 回查复用」的逻辑就永远走不到。
     */
    private FetchTask insertIsolated(FetchTask task) {
        PlatformTransactionManager tm = transactionManager;
        if (tm == null) {
            repository.save(task);
            repository.flush();
            return task;
        }
        TransactionTemplate template = new TransactionTemplate(tm);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        FetchTask saved = template.execute(status -> {
            repository.save(task);
            repository.flush();
            return task;
        });
        return saved != null ? saved : task;
    }

    /**
     * 把任务投进执行队列。
     *
     * <p><b>任务行此刻已经提交</b>（{@link #insertIsolated} 的独立事务已提交并归还连接），
     * 所以「投队列」不存在「执行侧拿到还没提交、查不到的 task_id」的问题。
     *
     * <p>调用方自己带着事务时（本方法不再另开事务，见 {@link #enqueue}），
     * 仍按调用方事务的提交时机投队列 —— 不改变调用方对事务边界的控制权。
     */
    private void scheduleExecution(UUID taskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    queue.submit(taskId);
                }
            });
            return;
        }
        queue.submit(taskId);
    }

    // ==================================================================
    // 五态机推进（task 20）
    // ==================================================================

    /**
     * 把任务推进到下一个状态（跃迁校验 + 持久化 + 触发对应协议子步）。
     *
     * <p><b>幂等</b>：目标状态与当前状态相同时直接返回，不报错、不重复落审计 ——
     * 网络重投与重复回调是常态。
     *
     * <p><b>非法跃迁</b>：抛 {@link IllegalTaskTransitionException}，
     * 状态<b>不变</b>（库里不会留下一行「说不清是哪一步」的状态）。
     *
     * <p><b>子步失败</b>：任务转 {@code failed} 并落失败码与死信 —— 不会停在
     * 「状态是 pulling、其实没拉到东西」的中间态。
     *
     * @param taskId    任务 UUID
     * @param newStatus 目标状态
     * @return 推进后的任务行
     */
    @Transactional
    public FetchTask advance(UUID taskId, TaskStatus newStatus) {
        return advance(taskId, newStatus, "system");
    }

    /**
     * 把任务推进到下一个状态（带操作人）。
     *
     * @param taskId    任务 UUID
     * @param newStatus 目标状态
     * @param who       操作人（工号 / 系统标识）；审计行的 who 列
     */
    @Transactional
    public FetchTask advance(UUID taskId, TaskStatus newStatus, String who) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不能为 null");
        }
        if (newStatus == null) {
            throw new IllegalArgumentException("目标状态不能为 null");
        }
        Timer.Sample sample = metrics == null ? null : metrics.startFetch();
        try (TaskContext ignored = TaskContext.open(taskId, who)) {
            FetchTask advanced = advanceInternal(taskId, newStatus, who);
            if (sample != null && newStatus == TaskStatus.NOTIFIED
                    && advanced.getStatus() == TaskStatus.NOTIFIED) {
                metrics.fetchCompleted(sample);
            }
            return advanced;
        }
    }

    /** 状态推进本体；由 {@link #advance(UUID, TaskStatus, String)} 包上 MDC 与计时。 */
    private FetchTask advanceInternal(UUID taskId, TaskStatus newStatus, String who) {
        FetchTask task = requireTask(taskId);
        TaskStatus from = task.getStatus();

        if (TaskStateMachine.isNoOp(from, newStatus)) {
            LOG.debug("任务已在目标状态，幂等跳过：taskId={} status={}", taskId, from);
            return task;
        }
        TaskStateMachine.assertTransition(from, newStatus);

        task.setStatus(newStatus);
        FetchTask saved = repository.save(task);

        StepRun run = runStep(taskId, saved, newStatus, who);

        if (run.retryScheduled()) {
            // 子步失败但已安排任务级重试（task 21）：任务已被退回 queued，
            // 这里既不落「推进成功」审计，也不把任务推失败终态。
            FetchTask requeued = repository.findByTaskId(taskId).orElse(saved);
            LOG.info("调阅任务失败后已安排重试，回到 queued 等待重跑：taskId={} retry_count={}",
                    taskId, requeued.getRetryCount());
            return requeued;
        }

        FetchTaskStepOutcome outcome = run.outcome();
        if (outcome != null && !outcome.isEmpty()) {
            applyOutcome(saved, outcome);
            saved = repository.save(saved);
        }
        if (saved.getStatus() == TaskStatus.FAILED) {
            // 子步失败已由 markFailed 落过失败审计与死信，这里不再补一行「推进成功」
            LOG.warn("调阅任务状态推进中止（子步失败，任务已转 failed）：taskId={} 目标={}", taskId, newStatus);
            return saved;
        }
        // AI 增强（task 27）：只在 notified 这一步做。AI 不可达**不阻断** notified 推进 ——
        // 影像已经在本院 PACS 里，AI 摘要只是给医生的辅助材料，没有它也该让 HIS 知道调阅完成了。
        saved = applyAiEnrichment(saved, newStatus);
        recordAudit(saved, AUDIT_PREFIX_TASK + newStatus.toDbValue(), null, who);
        notifyHis(saved, newStatus);
        LOG.info("调阅任务状态推进：taskId={} {} → {} peer={} study={}",
                taskId, from, newStatus, saved.getPeerAet(), saved.getStudyUid());
        return saved;
    }

    /**
     * notified 之后回推 HIS 工单（task 32）。
     *
     * <p><b>失败不回退状态</b>：通知端口自己负责重试、落 {@code dlq_notifications} 与告警；
     * 这里把所有异常收住，保证「影像已写入 PACS、任务已 notified」这个事实不被一次
     * 回调抖动改写。端口未接入时行为与没有通知层完全一致。
     */
    private void notifyHis(FetchTask task, TaskStatus targetStatus) {
        NotificationPort port = notificationPort;
        if (port == null || targetStatus != TaskStatus.NOTIFIED) {
            return;
        }
        try {
            port.onNotified(task.getTaskId(), task.getStudyUid(), TaskStatus.NOTIFIED.toDbValue());
        } catch (RuntimeException ex) {
            LOG.error("HIS 通知端口异常（已吞掉，任务保持 notified）：taskId={} msg={}",
                    task.getTaskId(), ex.getMessage());
        }
    }

    /**
     * notified 之前的 AI 增强（task 27）。
     *
     * <p><b>四条硬约束</b>：
     * <ul>
     *   <li><b>不阻断 notified 推进</b> —— 端口抛异常、返回空、模型不可达，
     *       都只让本次 AI 介入「没有产出」，任务照常推进到 notified；</li>
     *   <li><b>AI 失败不算路由失败</b> —— 不把任务推 failed、不打 {@code failure_code}：
     *       调阅任务到哪一步了由 DICOM / HL7 协议步骤说了算，AI 只是增强层，
     *       模型服务挂了不该让一次已经成功的调阅变成失败记录；</li>
     *   <li><b>没有产出时 ai_summary_id 保持为空</b> —— 不写脏数据、不指向不存在的摘要；</li>
     *   <li><b>幂等</b> —— 已有 {@code ai_summary_id} 就不重复产出（HIS 重投通知是常态）。</li>
     * </ul>
     *
     * <p><b>来源标记落在哪</b>：产物是模型写的还是规则兜底的，记在
     * {@code ai_summaries.source}（{@code model} / {@code rule_fallback}，V4 有 CHECK 约束），
     * 模型为什么失败记在 {@code ai_call_audit.status}（五态）。这两张表按 {@code task_id}
     * 就能关联查清「这条 notified 的 AI 草稿是怎么来的」。
     * 审计链 {@code audit_imaging_fetch} 的字段集合（task 24 定的链式校验和口径）
     * <b>不因此改动</b> —— 给 append-only 链加列会牵动校验和定义，
     * 而这个信息本来就不该塞进「调阅审计」里。
     *
     * <p><b>关于「在事务内调模型」</b>：本方法运行在 {@link #advance} 的事务里，
     * 调用期间会占住一条数据库连接。AI 调用有 {@code model.timeout-ms} 上限（默认 5s），
     * 增强层又是可拔的（有端口才有这一步），这个代价可以接受；
     * 真要在大并发下压到零，应当把 AI 步骤挪到事务外（先落 stored、再异步补摘要）——
     * 那是 task 28 接入报告归一时的取舍点。
     */
    private FetchTask applyAiEnrichment(FetchTask task, TaskStatus targetStatus) {
        AiEnrichmentPort port = aiEnrichmentPort;
        if (port == null) {
            return task;
        }
        if (targetStatus != TaskStatus.NOTIFIED) {
            return task;
        }
        if (task.getAiSummaryId() != null) {
            LOG.debug("任务已有 AI 摘要，幂等跳过增强：taskId={} ai_summary_id={}",
                    task.getTaskId(), task.getAiSummaryId());
            return task;
        }
        try {
            AiEnrichmentOutcome outcome = port.enrich(task);
            if (outcome == null || !outcome.hasSummary()) {
                LOG.warn("AI 增强未产出摘要（模型不可达且规则兜底也没有），ai_summary_id 保持为空：taskId={} note={}",
                        task.getTaskId(), outcome == null ? "端口未返回结果" : outcome.note());
                return task;
            }
            task.setAiSummaryId(outcome.summaryId());
            LOG.info("AI 增强已产出摘要：taskId={} ai_summary_id={} source={}",
                    task.getTaskId(), outcome.summaryId(), outcome.source());
            return repository.save(task);
        } catch (RuntimeException ex) {
            LOG.error("AI 增强异常（已吞掉，不阻断 notified 推进）：taskId={} msg={}",
                    task.getTaskId(), ex.getMessage());
            return task;
        }
    }

    /**
     * 推进失败终态（{@link FetchTaskFailurePort} 实现 —— task 16 的失败出口调用）。
     *
     * <p><b>只做两件事</b>：推进终态 + 落一行失败审计。
     * <b>不</b>进死信 —— 调用方（{@code RoutingFailureHandler}）已经进过了，
     * 两边都进就是同一次失败落两行死信。
     *
     * <p><b>返回值（task 21）</b>：告诉调用方这次失败是不是终局失败。
     * 失败码可重试时本方法把任务退回 {@code queued} 等退避后重跑，
     * 返回 {@link TaskFailureOutcome#RETRY_SCHEDULED} —— 调用方据此
     * <b>不</b>把这次失败进死信（它还在重跑，不是人工处置待办）。
     *
     * @param taskId      任务 UUID（字符串形式）
     * @param failureCode 失败码（{@link FailureCodeRegistry#code()}）
     * @return 这次失败的收场方式
     */
    @Override
    public TaskFailureOutcome markFailed(String taskId, String failureCode) {
        if (taskId == null || taskId.isBlank()) {
            LOG.warn("推失败终态时 taskId 为空，跳过");
            return TaskFailureOutcome.TERMINAL_FAILURE;
        }
        UUID id;
        try {
            id = UUID.fromString(taskId);
        } catch (IllegalArgumentException ex) {
            LOG.warn("推失败终态时 taskId 不是合法 UUID：{}", taskId);
            return TaskFailureOutcome.TERMINAL_FAILURE;
        }
        FetchTask after = markFailed(id, failureCode, null, false);
        // 任务被退回 queued = 已安排重试，不是终局失败
        return after != null && after.getStatus() == TaskStatus.QUEUED
                ? TaskFailureOutcome.RETRY_SCHEDULED
                : TaskFailureOutcome.TERMINAL_FAILURE;
    }

    /**
     * 推进失败终态（不落死信）。
     *
     * @param taskId      任务 UUID
     * @param failureCode 失败码
     * @return 失败后的任务行；任务不存在时返回 {@code null}
     */
    @Transactional
    public FetchTask markFailed(UUID taskId, String failureCode) {
        return markFailed(taskId, failureCode, null, false);
    }

    /**
     * 推进失败终态并进死信 —— 本服务自己发现失败时走这条（死信只落一次）。
     *
     * @param taskId      任务 UUID
     * @param failureCode 失败码
     * @param context     失败定位上下文（可空；用于死信行的定位字段）
     */
    @Transactional
    public FetchTask markFailedAndDeadLetter(UUID taskId,
                                             String failureCode,
                                             RoutingFailureContext context) {
        return markFailed(taskId, failureCode, context, true, false);
    }

    private FetchTask markFailed(UUID taskId,
                                 String failureCode,
                                 RoutingFailureContext context,
                                 boolean deadLetter) {
        return markFailed(taskId, failureCode, context, deadLetter, false);
    }

    /**
     * 推进失败终态（并可进死信）。
     *
     * @param deadLetter          是否进死信
     * @param retryAlreadyChecked 调用方是否已问过重试端口（问过就不再问一次）
     */
    private FetchTask markFailed(UUID taskId,
                                 String failureCode,
                                 RoutingFailureContext context,
                                 boolean deadLetter,
                                 boolean retryAlreadyChecked) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不能为 null");
        }
        Optional<FetchTask> found = repository.findByTaskId(taskId);
        if (found.isEmpty()) {
            LOG.warn("推失败终态时任务不存在：taskId={} code={}", taskId, failureCode);
            return null;
        }
        FetchTask task = found.get();
        TaskStatus from = task.getStatus();

        if (from == TaskStatus.FAILED) {
            // 幂等：已经失败了，重复回报不重复落审计、不重复进死信
            LOG.debug("任务已是失败终态，幂等跳过：taskId={}", taskId);
            return task;
        }
        if (from == TaskStatus.NOTIFIED) {
            // 已通知 HIS 的任务不能再翻成失败 —— 那会让 HIS 侧与库里对不上
            LOG.warn("任务已通知，拒绝翻成失败：taskId={} code={}", taskId, failureCode);
            return task;
        }
        if (!TaskStateMachine.isLegal(from, TaskStatus.FAILED)) {
            LOG.warn("推失败终态的跃迁非法，保持原状态：taskId={} {} → failed", taskId, from);
            return task;
        }

        String who = context == null ? "system" : context.auditWho();

        // task 21：失败但还该再试 → 任务退回 queued 走重试，不推失败终态
        if (!retryAlreadyChecked) {
            RetryScheduleResult retry = tryScheduleRetry(taskId, failureCode, who);
            if (retry != null && retry.retried()) {
                LOG.info("失败已安排重试，不推失败终态：taskId={} code={} 第 {} 次",
                        taskId, failureCode, retry.retryCount());
                return repository.findByTaskId(taskId).orElse(task);
            }
        }

        task.setStatus(TaskStatus.FAILED);
        task.setFailureCode(failureCode);
        if (metrics != null) {
            metrics.fetchFailed();
        }
        FetchTask saved = repository.save(task);
        recordAudit(saved, AUDIT_TASK_FAILED, failureCode, who);
        if (deadLetter) {
            enqueueDeadLetter(saved, failureCode, context);
        }
        LOG.warn("调阅任务转失败终态：taskId={} code={} peer={} study={}",
                taskId, failureCode, saved.getPeerAet(), saved.getStudyUid());
        return saved;
    }

    /**
     * 重试计数 +1（task 21 的重试调度器调用）。
     *
     * @return 累加后的重试次数；任务不存在返回 {@code null}
     */
    @Transactional
    public Integer incrementRetry(UUID taskId) {
        Optional<FetchTask> found = repository.findByTaskId(taskId);
        if (found.isEmpty()) {
            return null;
        }
        FetchTask task = found.get();
        int next = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        task.setRetryCount(next);
        repository.save(task);
        return next;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /** 按 task_id 查任务（终值可查）。 */
    @Transactional(readOnly = true)
    public Optional<FetchTask> findByTaskId(UUID taskId) {
        return repository.findByTaskId(taskId);
    }

    /** 按幂等键查"未终结"的任务。 */
    @Transactional(readOnly = true)
    public Optional<FetchTask> findActiveByPatientMpiAndStudyUid(String patientMpi, String studyUid) {
        return repository.findActiveByPatientMpiAndStudyUid(patientMpi, studyUid);
    }

    /** 按幂等键查<b>全部</b>历史任务（含已终结，按创建时间倒序）。 */
    @Transactional(readOnly = true)
    public List<FetchTask> findHistoryByPatientMpiAndStudyUid(String patientMpi, String studyUid) {
        return repository.findByPatientMpiAndStudyUidOrderByCreatedAtDesc(patientMpi, studyUid);
    }

    /** 按状态查任务（运维看板 / 卡死扫描用）。 */
    @Transactional(readOnly = true)
    public List<FetchTask> findByStatus(TaskStatus status) {
        return repository.findByStatus(status);
    }

    /** 卡死任务：状态非终态且更新时间早于给定时刻。 */
    @Transactional(readOnly = true)
    public List<FetchTask> findStuckTasks(Instant before) {
        return repository.findStuckNonTerminalTasks(before);
    }

    /** 任务是否已到终态（终值可查的第一问）。 */
    public boolean isTerminal(UUID taskId) {
        return repository.findByTaskId(taskId)
                .map(task -> TaskStateMachine.isTerminal(task.getStatus()))
                .orElse(false);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private FetchTask requireTask(UUID taskId) {
        return repository.findByTaskId(taskId)
                .orElseThrow(() -> new NotFoundException("调阅任务不存在: " + taskId));
    }

    /**
     * 跑目标状态对应的协议子步；失败则把任务推向失败终态。
     *
     * <p>子步与状态推进在<b>同一个事务</b>里：子步失败时任务落 {@code failed}，
     * 不会留下「状态停在 pulling、其实没拉到东西」的中间态。
     *
     * <p><b>task 21 的任务级重试</b>：子步失败时先问一次「该不该再试」——
     * 该再试就把任务退回 queued 走重试（本方法返回 {@link StepRun#retryScheduled()}），
     * 不该再试才推失败终态。这道询问放在推终态<b>之前</b>，
     * 是为了不出现「任务已经判死、又要重跑一遍」的矛盾状态。
     */
    private StepRun runStep(UUID taskId, FetchTask task, TaskStatus targetStatus, String who) {
        FetchTaskStepExecutor executor = stepExecutor;
        if (executor == null || !executor.supports(targetStatus)) {
            return StepRun.none();
        }
        try {
            return StepRun.withOutcome(executor.execute(taskId, task, targetStatus));
        } catch (RuntimeException ex) {
            FailureCodeRegistry code = FailureCodeRegistry.classify(ex);
            LOG.error("调阅子步失败：taskId={} status={} code={} msg={}",
                    taskId, targetStatus, code.code(), ex.getMessage());
            RoutingFailureContext ctx = new RoutingFailureContext(
                    stepOf(targetStatus),
                    task.getPeerAet(),
                    task.getPatientMpi(),
                    task.getStudyUid(),
                    taskId.toString(),
                    who);

            // ① 先问重试端口：失败但还该再试 → 任务退回 queued，不推失败终态
            RetryScheduleResult retry = tryScheduleRetry(taskId, code.code(), who);
            if (retry != null && retry.retried()) {
                return StepRun.retry();
            }

            // ② 不重试 → 推失败终态 + 落失败码 + 进死信
            markFailed(taskId, code.code(), ctx, true, true);
            return StepRun.failure();
        }
    }

    /**
     * 子步执行结果 —— 三种互斥的结局。
     *
     * @param outcome        子步产出（可空）
     * @param retryScheduled 是否已安排任务级重试（任务被退回 queued）
     * @param failed         是否已推失败终态
     */
    private record StepRun(FetchTaskStepOutcome outcome, boolean retryScheduled, boolean failed) {

        static StepRun none() {
            return new StepRun(null, false, false);
        }

        static StepRun withOutcome(FetchTaskStepOutcome outcome) {
            return new StepRun(outcome, false, false);
        }

        static StepRun retry() {
            return new StepRun(null, true, false);
        }

        static StepRun failure() {
            return new StepRun(null, false, true);
        }
    }

    /**
     * 问一次「这次失败要不要再来一次」。
     *
     * <p>端口未接入（未启用任务级重试）时返回 {@code null} —— 调用方按终局失败处理。
     * 端口自己抛异常时也返回 {@code null}：重试判定失败不该把调阅流程炸掉，
     * 按终局失败处理最保守（任务会进死信，信息科看得见）。
     */
    private RetryScheduleResult tryScheduleRetry(UUID taskId, String failureCode, String who) {
        TaskRetryPort port = taskRetryPort;
        if (port == null) {
            return null;
        }
        try {
            return port.scheduleRetry(taskId, failureCode, who);
        } catch (RuntimeException ex) {
            LOG.error("重试判定异常（按终局失败处理）: taskId={} {}", taskId, ex.getMessage());
            return null;
        }
    }

    /** 状态 → 失败处置里的步骤名（审计终态用）。 */
    private static String stepOf(TaskStatus status) {
        return switch (status) {
            case QUERYING -> RoutingFailureContext.STEP_QUERY;
            case PULLING -> RoutingFailureContext.STEP_RETRIEVE;
            case STORED -> RoutingFailureContext.STEP_STORE;
            default -> RoutingFailureContext.STEP_QUERY;
        };
    }

    /** 把子步产出回写到任务行。 */
    private void applyOutcome(FetchTask task, FetchTaskStepOutcome outcome) {
        if (outcome.sopClassUid() != null) {
            task.setSopClassUid(outcome.sopClassUid());
        }
        if (outcome.sopInstanceCount() != null) {
            task.setSopInstanceCount(outcome.sopInstanceCount());
        }
        if (outcome.bytesTotal() != null) {
            task.setBytesTotal(outcome.bytesTotal());
        }
        if (outcome.durationMs() != null) {
            task.setDurationMs(outcome.durationMs());
        }
    }

    /** 落一行审计（审计库故障不阻断主流程 —— 排障失败不该把调阅流程炸掉）。 */
    private void recordAudit(FetchTask task, String statusTerminal, String failureCode, String who) {
        if (auditService == null) {
            return;
        }
        try {
            AuditRow row = new AuditRow()
                    .withWho(who == null || who.isBlank() ? "system" : who)
                    .withFromAet("local")
                    .withToAet(task.getPeerAet())
                    .withStudyUid(task.getStudyUid())
                    .withSopClassUid(task.getSopClassUid())
                    .withRetryCount(task.getRetryCount() == null ? 0 : task.getRetryCount())
                    .withStatusTerminal(statusTerminal);
            if (failureCode != null) {
                row.withFailureCode(failureCode);
            }
            auditService.record(row);
        } catch (RuntimeException ex) {
            LOG.error("状态推进审计写入失败（已吞掉，不影响状态推进）: {}", ex.getMessage());
        }
    }

    /** 进死信（端口未接入时只记日志 —— task 22 落地后生效）。 */
    private void enqueueDeadLetter(FetchTask task, String failureCode, RoutingFailureContext context) {
        DlqEnqueuePort port = dlqEnqueuePort;
        if (port == null) {
            LOG.debug("死信端口未接入，跳过入队（task 22 落地后生效）: {}", failureCode);
            return;
        }
        RoutingFailureContext ctx = context != null ? context
                : new RoutingFailureContext(RoutingFailureContext.STEP_QUERY,
                        task.getPeerAet(), task.getPatientMpi(), task.getStudyUid(),
                        task.getTaskId() == null ? null : task.getTaskId().toString(), "system");
        try {
            FailureCodeRegistry code = FailureCodeRegistry.fromDbValue(failureCode);
            port.enqueue(DlqEnqueueRequest.of(ctx, code,
                    "任务 " + task.getTaskId() + " 失败：" + failureCode,
                    task.getRetryCount() == null ? 0 : task.getRetryCount()));
        } catch (RuntimeException ex) {
            LOG.warn("死信入队失败（已吞掉，保留原始失败原因）: {}", ex.getMessage());
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
