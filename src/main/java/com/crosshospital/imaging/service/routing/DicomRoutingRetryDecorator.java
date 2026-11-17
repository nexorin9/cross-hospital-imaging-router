package com.crosshospital.imaging.service.routing;

import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.domain.RetrieveResult;
import com.crosshospital.imaging.domain.StudyRef;
import com.crosshospital.imaging.repository.PeerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * DICOM 路由主路径的**重试装饰器** —— 给 C-FIND / C-MOVE / C-STORE 三步套上
 * 「按节点配置退避重试 + 失败统一处置」的外壳。
 *
 * <p>装饰器模式而非改写 {@link DicomRoutingService} 三步的内部实现，原因有三：
 * <ol>
 *   <li><b>协议层不该管重试</b>：一次 C-FIND 就是一次关联、一条请求-响应，
 *       「要不要再来一次」是队列/策略层的决定，塞进协议代码会让 task 13/14/15
 *       的单测语义从「调阅一次」变成「调阅 N 次」；</li>
 *   <li><b>预算来自节点</b>：{@code max_retries} / {@code backoff_ms} 是
 *       {@code imaging_peers} 的逐节点配置（task 1/5 写入），同一时刻不同外院的
 *       承受能力不同 —— 全市三甲 PACS 与县医院 PACS 用同一个重试次数不合理；</li>
 *   <li><b>失败处置只有一个出口</b>：重试耗尽后统一交给
 *       {@link RoutingFailureHandler} 落审计 + 进死信，不给每个 catch 各留一条路。</li>
 * </ol>
 *
 * <h2>重试判定（关键不变量）</h2>
 * <pre>
 *   失败 → FailureCodeRegistry.classify()
 *            ├── retryable=false（配置型：AE 未登记 / TLS 配错 / 传输语法无交集）
 *            │       → 一次都不重试，直接处置 + 原样抛出
 *            └── retryable=true（瞬态：连不上 / 超时 / 链路中断）
 *                    └── attempt &lt; peer.max_retries
 *                            ├── 是 → sleep(backoff × 2^attempt) 后重试
 *                            └── 否 → 处置 + 原样抛出
 * </pre>
 * <p><b>配置型失败绝不重试</b>：外院 AE 白名单没登记，重试 3 次只是把同一个
 * A-ASSOCIATE-RJ 打三遍，把对方 PACS 日志刷满，还把本次调阅拖长到预算上限。
 * 这是本类最重要的一条纪律 —— 重试只对「时间会变好」的故障有意义。
 *
 * <h2>调用方 bug 不进重试、不进死信</h2>
 * <p>{@link IllegalArgumentException}（空 patient_mpi、空 sink、instances 为空…）
 * 是**本服务内部契约被违反**，重试一万次还是错，进死信还会污染信息科的待办列表。
 * 本类识别它并直接原样抛出，既不消耗重试预算，也不产生任何处置副作用。
 *
 * <h2>源主路径证据</h2>
 * <ul>
 *   <li>{@code github_ref/dcm4che-net/.../Connection.java:1080-1089} ——
 *       建连失败后 {@code monitor.onConnectionFailed(...)} 再 {@code throw e}：
 *       源产品把「通知」与「上抛」分成两件事，本类同样把「处置」与「抛出」分开，
 *       失败处置不会吞掉原始异常</li>
 *   <li>源产品**没有**重试装饰器：{@code Association} 层的失败一律上抛给调用方
 *       （{@code findscu}/{@code movescu} 是人工反复敲的命令，人就是重试器）。
 *       本类是把那份「人工反复敲」内化为队列策略 —— 属融合增量</li>
 * </ul>
 */
@Component
@Profile("!test")
public class DicomRoutingRetryDecorator {

    private static final Logger LOG = LoggerFactory.getLogger(DicomRoutingRetryDecorator.class);

    /** 退避封顶：与 WADO-RS 侧（WadoRsRetryPolicy）保持同一上限，避免两套重试节奏。 */
    static final int MAX_BACKOFF_MS = 30_000;

    /** 准入表缺 {@code max_retries} 时的兜底预算（与 V1 migration 的 DEFAULT 3 一致）。 */
    static final int DEFAULT_MAX_RETRIES = 3;

    /** 准入表缺 {@code backoff_ms} 时的兜底基数（与 V1 migration 的 DEFAULT 1000 一致）。 */
    static final int DEFAULT_BACKOFF_MS = 1000;

    private final DicomRoutingService routingService;
    private final PeerRepository peerRepository;

    /**
     * 失败统一处置出口。用 setter 注入：task 16 里 {@code RoutingFailureHandler}
     * 与本类同为 {@code @Profile("!test")} bean，构造注入会产生循环依赖
     * （handler 被 DicomRoutingService 引用，decorator 引用 DicomRoutingService）。
     */
    private volatile RoutingFailureHandler failureHandler;

    /** 退避等待的可替换实现（单测注入空实现，避免真实 sleep）。 */
    private volatile Sleeper sleeper = Thread::sleep;

    /** 一次退避等待；抽成接口是为了单测不真睡。 */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** 逐节点的重试预算。 */
    public record RetryBudget(int maxRetries, int baseBackoffMs) {

        public static RetryBudget defaults() {
            return new RetryBudget(DEFAULT_MAX_RETRIES, DEFAULT_BACKOFF_MS);
        }
    }

    public DicomRoutingRetryDecorator(DicomRoutingService routingService,
                                      PeerRepository peerRepository) {
        this.routingService = routingService;
        this.peerRepository = peerRepository;
        // 单写者声明：装饰器是「终局失败」的唯一记账人 —— 只有它知道真实重试次数，
        // 服务层只知道「这一步失败了」。两者同时记账会落两行审计，其中一行的
        // retry_count 永远是 0，信息科据此区分「一次就挂」与「重试三次还挂」时会判错。
        // 注意是「领走记账权」而非摘掉 handler：摘掉会让服务层的内联审计守卫失效，
        // C-MOVE/C-STORE 失败又落回第二行。无装饰器的直接调用方行为不变。
        routingService.claimTerminalFailureRecording();
    }

    /** 注入失败处置出口。 */
    public void setFailureHandler(RoutingFailureHandler handler) {
        this.failureHandler = handler;
    }

    /** 替换退避等待实现（单测用空实现；生产保持 {@link Thread#sleep(long)}）。 */
    public void setSleeper(Sleeper sleeper) {
        this.sleeper = sleeper == null ? Thread::sleep : sleeper;
    }

    // ==================================================================
    // 主路径三步（装饰后的对外入口）
    // ==================================================================

    /** 第 1 步 C-FIND（带重试与失败处置）。 */
    public List<StudyRef> queryStudies(String peerAet, String patientMpi,
                                       String accessionNumber, String studyUid,
                                       String taskId, String who) {
        RoutingFailureContext ctx = new RoutingFailureContext(
                RoutingFailureContext.STEP_QUERY, peerAet, patientMpi, studyUid, taskId, who);
        return call(ctx, () -> routingService.queryStudies(peerAet, patientMpi, accessionNumber, studyUid));
    }

    /** 第 2 步 C-MOVE（带重试与失败处置）。 */
    public RetrieveResult retrieveStudy(String peerAet, String studyUid,
                                        MoveDestinationStoreScp.InstanceSink sink,
                                        String patientMpi, String taskId, String who) {
        RoutingFailureContext ctx = new RoutingFailureContext(
                RoutingFailureContext.STEP_RETRIEVE, peerAet, patientMpi, studyUid, taskId, who);
        return call(ctx, () -> routingService.retrieveStudy(peerAet, studyUid, sink));
    }

    /** 第 3 步 C-STORE（带重试与失败处置）。对端是本院 PACS，重试预算不取外院节点配置。 */
    public com.crosshospital.imaging.domain.StoreResult storeToLocal(String peerAet, String studyUid,
                                                                     List<ByteBuffer> instances,
                                                                     String fromAet,
                                                                     String patientMpi, String taskId,
                                                                     String who) {
        RoutingFailureContext ctx = new RoutingFailureContext(
                RoutingFailureContext.STEP_STORE, peerAet, patientMpi, studyUid, taskId, who);
        return call(ctx, () -> routingService.storeToLocal(studyUid, instances, fromAet));
    }

    // ==================================================================
    // 装饰核心
    // ==================================================================

    /**
     * 执行一步主路径动作，按节点配置退避重试；终局失败时交 {@link RoutingFailureHandler} 处置。
     *
     * <p>异常**原样上抛**：装饰器不改变被装饰方法的失败契约，
     * 只在抛出去之前把排障动作做掉。调用方（task 20 五态机）拿到的仍是
     * {@code RoutingFailureException} / {@code HandshakeException}。
     */
    public <T> T call(RoutingFailureContext ctx, Callable<T> action) {
        RetryBudget budget = budgetFor(ctx.peerAet());
        int attempt = 0;
        while (true) {
            try {
                return action.call();
            } catch (RuntimeException e) {
                // 调用方 bug：既不重试也不进死信，直接原样抛
                if (e instanceof IllegalArgumentException) {
                    throw e;
                }
                FailureCodeRegistry code = FailureCodeRegistry.classify(e);
                boolean attemptsLeft = attempt < budget.maxRetries();
                if (!code.retryable() || !attemptsLeft) {
                    RoutingFailureHandler handler = failureHandler;
                    if (handler != null) {
                        handler.handle(ctx, e, attempt);
                    } else {
                        LOG.warn("失败处置出口未接入，仅记录日志: peer={} code={}",
                                ctx.peerAet(), code.code());
                    }
                    LOG.debug("重试结束（不可重试或预算耗尽）: step={} peer={} code={} attempt={}",
                            ctx.step(), ctx.peerAet(), code.code(), attempt);
                    throw e;
                }
                long backoffMs = backoffMs(budget.baseBackoffMs(), attempt);
                LOG.info("第 {} 次尝试失败，退避 {}ms 后重试: step={} peer={} code={}",
                        attempt + 1, backoffMs, ctx.step(), ctx.peerAet(), code.code());
                try {
                    sleeper.sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    // 退避等待被打断 = 有人要停服务，不该再重试，也不该改写业务状态
                    LOG.warn("退避等待被中断，放弃重试: step={} peer={}", ctx.step(), ctx.peerAet());
                    throw e;
                }
                attempt++;
            } catch (Exception e) {
                // Callable 声明了 checked exception，但本装饰器只接三步的方法引用，
                // 它们不抛 checked；真出现就原样包成 RuntimeException，不静默吞掉
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            }
        }
    }

    /**
     * 取该节点的重试预算；准入表查不到时用迁移脚本里的默认值。
     *
     * <p>查不到节点不代表「不重试」—— 此时真正的失败来自
     * {@code DicomRoutingService.requirePeer}（PEER_NOT_FOUND，非可重试），
     * 预算取什么都不影响结果；给默认值是为了让退避计算有确定的基数，不返回 0 触发忙等。
     */
    public RetryBudget budgetFor(String peerAet) {
        if (peerAet == null || peerAet.isBlank()) {
            return RetryBudget.defaults();
        }
        Optional<Peer> peer;
        try {
            peer = peerRepository.findByPeerAet(peerAet);
        } catch (RuntimeException e) {
            LOG.warn("读节点重试配置失败，回落到默认预算: peer={} {}", peerAet, e.getMessage());
            return RetryBudget.defaults();
        }
        if (peer.isEmpty()) {
            return RetryBudget.defaults();
        }
        Integer maxRetries = peer.get().getMaxRetries();
        Integer backoffMs = peer.get().getBackoffMs();
        return new RetryBudget(
                maxRetries == null || maxRetries < 0 ? DEFAULT_MAX_RETRIES : maxRetries,
                backoffMs == null || backoffMs <= 0 ? DEFAULT_BACKOFF_MS : backoffMs);
    }

    /**
     * 指数退避：{@code base × 2^attempt}，封顶 {@value #MAX_BACKOFF_MS} ms。
     *
     * <p>封顶而非无限翻倍：{@code attempt=31} 时 {@code base × 2^31} 会溢出成负数，
     * 负的 sleep 在某些平台直接抛 {@code IllegalArgumentException}。
     * 这里先按位判断越过封顶再算，连 {@code 1 << 62} 这种溢出中间态都不会碰到。
     *
     * <p><b>本层不加抖动</b>：抖动由 task 21 的 {@code RetryPolicy} 负责。
     * 这里若再加一次，多批任务同刻失败会被打散两轮，可观测性反而变差；
     * 两层各管一件事更清楚。
     */
    static long backoffMs(int baseMs, int attempt) {
        int base = baseMs <= 0 ? DEFAULT_BACKOFF_MS : baseMs;
        if (base >= MAX_BACKOFF_MS || attempt >= 31) {
            return MAX_BACKOFF_MS;
        }
        long scaled = (long) base << attempt;
        return Math.min(scaled, MAX_BACKOFF_MS);
    }
}