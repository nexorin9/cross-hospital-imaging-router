package com.crosshospital.imaging.service.routing;

import com.crosshospital.imaging.config.DicomConfigProps;
import com.crosshospital.imaging.config.RoutingConfigProps;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.domain.RetrieveResult;
import com.crosshospital.imaging.domain.StoreResult;
import com.crosshospital.imaging.domain.StudyRef;
import com.crosshospital.imaging.exception.HandshakeException;
import com.crosshospital.imaging.exception.RoutingFailureException;
import com.crosshospital.imaging.exception.RoutingFailureException.FailureCode;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.metrics.ImagingMetrics;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.SpecificCharacterSet;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomEncodingOptions;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.AssociationStateException;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.DimseRSP;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.IncompatibleConnectionException;
import org.dcm4che3.net.NoPresentationContextException;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 跨院影像调阅 **主路径第一步**：STUDY 级 C-FIND 询外院 PACS 的 StudyInstanceUID 列表。
 *
 * <p>本类把 dcm4che README 中给影像技师手工敲的 {@code findscu} 命令，
 * 改写为路由服务内部的一次方法调用 —— 输入 {@code (peer_aet, patient_mpi, accession_number)}，
 * 输出外院该患者全部检查的 {@link StudyRef} 列表（含 StudyInstanceUID，供后续
 * C-MOVE / C-STORE 定位同一份影像）。
 *
 * <h2>执行主路径（与源仓库一一对应）</h2>
 * <pre>
 *   findscu -v -aet IMAGER -aet REMOTE_PACS host 11112 -P PATIENT_ID \
 *           -m StudyInstanceUID -r StudyInstanceUID
 * </pre>
 * <ol>
 *   <li><b>查准入表</b> — {@link PeerRepository#findByPeerAet} 拿 host/port/协商参数；
 *       缺失即 PEER_NOT_FOUND（配置型失败，不重试）</li>
 *   <li><b>建本端 Device + ApplicationEntity</b> — 本院 AE Title 由
 *       {@code dicom.local-aet} 给出（源：{@code Device} + {@code ApplicationEntity} 组合）</li>
 *   <li><b>建远端 Connection</b> — host/port 来自准入表；{@code connectTimeout}
 *       取 {@code dicom.remote-connect-timeout-ms}</li>
 *   <li><b>兼容性预校验</b> — {@code localConn.isCompatible(remoteConn)}；
 *       协议族/TLS 协议/cipher 三层交集（源：{@code Connection.java:1197-1209}）</li>
 *   <li><b>传输语法 + SOP Class 求交</b> — 与 task 8 的
 *       {@code TransferSyntaxNegotiator} / {@code SopClassFilter} 同源语义</li>
 *   <li><b>发起关联</b> — {@code ae.connect(localConn, remoteConn, rq)} 发 A-ASSOCIATE-RQ；
 *       内部先 {@code findCompatibleConnection} 再走
 *       {@code Connection.connect(remoteConn)}（源：{@code Connection.java:1034-1091}）</li>
 *   <li><b>发 C-FIND-RQ</b> — STUDY 级查询键：PatientID（匹配）+ StudyInstanceUID 等
 *       （返回）；{@code as.cfind(cuid, priority, keys, tsuid, rspTimeout)}</li>
 *   <li><b>读 C-FIND-RSP</b> — {@code DimseRSP.next()} 循环：Pending 响应逐条投影为
 *       {@link StudyRef}；终态 Cancel 表示查询正常完成</li>
 *   <li><b>正常释放</b> — {@code as.release()} 发 A-RELEASE-RQ 并等 A-RELEASE-RP；
 *       任何异常路径走 {@code as.abort()} 硬关闭（源：{@code Association.release/abort}）</li>
 * </ol>
 *
 * <h2>失败语义（绝不静默改数据）</h2>
 * <table border="1">
 *   <tr><th>阶段</th><th>失败码</th><th>可重试</th></tr>
 *   <tr><td>准入表无此节点</td><td>{@code ASSOCIATION_REJECTED}（见 {@link #failure}）</td><td>false</td></tr>
 *   <tr><td>A-ASSOCIATE-RQ 被拒 / 无可接受 PC</td><td>ASSOCIATION_REJECTED</td><td>false</td></tr>
 *   <tr><td>C-FIND-RSP 超时</td><td>QUERY_TIMEOUT</td><td>true</td></tr>
 *   <tr><td>查询期链路异常</td><td>QUERY_FAILED</td><td>true</td></tr>
 *   <tr><td>查询正常但零命中</td><td>（**不抛异常**）返回空列表</td><td>—</td></tr>
 * </table>
 *
 * <p>「零命中」与「查询失败」在本服务里是**两个不同的结果**，绝不可混同：
 * 前者是外院的业务事实（该院确实没片），后者是链路故障；把后者伪装成前者，
 * 会让 HIS 工单被误判为已调阅完毕。
 *
 * <p>源主路径证据：
 * <ul>
 *   <li>{@code Connection.java:1034-1091} — {@code connect(remoteConn)}：
 *       checkCompatible → bind → connect → TLS → onConnectionEstablished</li>
 *   <li>{@code Connection.java:1197-1209} — {@code isCompatible()}：protocol/TLS/cipher 三层校验</li>
 *   <li>{@code Connection.java:973-976} — {@code checkCompatible()} 失败即
 *       {@code IncompatibleConnectionException}</li>
 *   <li>{@code CompatibleConnection.java:44-62} — 本地/远端 Connection 配对容器</li>
 *   <li>{@code ConnectionMonitor.java:47-60} — 五态回调；本服务复用 task 9 的
 *       {@code ImagingConnectionMonitor} 把关联建立/失败落审计</li>
 *   <li>README「Utilities」— {@code findscu}：本类即其服务化改写</li>
 * </ul>
 */
@Service
@Profile("!test")
public class DicomRoutingService {

    private static final Logger LOG = LoggerFactory.getLogger(DicomRoutingService.class);

    /** DICOM 标准应用上下文名（PS3.7 Annex A.1）。 */
    public static final String APPLICATION_CONTEXT_NAME = "1.2.840.10008.3.1.1.1";

    /** STUDY 级查询在查询键里显式声明的层级（PS3.4 C.2.2.1.1）。 */
    private static final String QUERY_LEVEL_STUDY = "STUDY";

    /** C-MOVE 失败行在 {@code status_terminal} 列的取值（task 14）。 */
    public static final String STATUS_RETRIEVE_FAILED = "retrieve_failed";

    private final PeerRepository peerRepository;
    private final DicomConfigProps dicomProps;
    private final RoutingConfigProps routingProps;
    /**
     * dcm4che {@code Device} 从这里取 PDU 处理线程（未设置会抛
     * {@code IllegalStateException: executor not initialized}）。
     * 生产用 Spring 自动配置的 {@code applicationTaskExecutor}；
     * 三参构造供单测自建 daemon 线程池。
     */
    private final Executor executor;

    /**
     * dcm4che 的 DIMSE 响应超时（{@code Association.cfind(..., rspTimeout)}）由
     * {@code Timeout} 在 **ScheduledExecutor** 上排程后触发 {@code abort()} 实现。
     * Device 未注入 scheduledExecutor 时超时不生效 —— 表现为对端不应答时
     * {@code DimseRSP.next()} 永久阻塞。
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dicom-routing-scheduler");
        t.setDaemon(true);
        return t;
    });

    /**
     * C-MOVE sub-operation 进度回传监听器（task 14 交付物）。
     *
     * <p>默认 {@link RetrieveProgressListener#noop()}；生产由 Spring 侧注入
     * {@link RetrieveAuditProgressListener}（每完成 10% 落一行审计），
     * 单测可注入收集器断言回调序列。用 setter 而非构造参数，是为了让
     * task 13 已有的三参单测构造与本类的其余调用方不必改动。
     */
    private volatile RetrieveProgressListener progressListener = RetrieveProgressListener.noop();

    /**
     * C-MOVE 失败时的审计落库通道（task 14 交付物）。
     *
     * <p>用可选 setter 而非构造参数：task 13 已有的三参单测构造与
     * {@code DicomRoutingServiceTest} 不必改动；未注入时 {@code recordRetrieveFailure}
     * 直接返回（协议行为完全不变，只是少了审计落库）。
     */
    private volatile AuditService auditService;

    /**
     * 失败统一处置出口（task 16 交付物）。
     *
     * <p>用可选 setter 而非构造参数：{@link RoutingFailureHandler} 与本类同为
     * {@code @Profile("!test")} bean，而 {@code DicomRoutingRetryDecorator} 又依赖本类，
     * 构造注入三者会成环。setter 注入打破这个环，且让 task 13/14/15 已有的
     * 单测构造完全不受影响。
     *
     * <p><b>单写者约定</b>：一旦注入，本类自带的
     * {@link #recordRetrieveFailure} / {@link #recordStoreFailure} 会让位 ——
     * 失败码分类、落审计、进死信全部由 {@code RoutingFailureHandler} 一处做。
     * 否则同一次 C-MOVE 失败会落两行审计（handler 一行、本类一行），
     * 信息科数重试次数会被误导。未注入时保持原行为（task 13/14/15 单测）。
     */
    private volatile RoutingFailureHandler failureHandler;

    /**
     * 「终局失败记账权」是否已被上层装饰器领走（task 16 的
     * {@link DicomRoutingRetryDecorator}）。
     *
     * <p>注意：领走记账权<b>不等于</b>摘掉 {@code failureHandler}。若同时摘掉，
     * 下面 {@link #recordRetrieveFailure} / {@link #recordStoreFailure} 的
     * 「单写者」守卫会失效（它正是靠 handler 非空来让位的），C-MOVE/C-STORE
     * 失败就会又落回内联审计那一行 —— 又变成两行。正确做法是：
     * handler 留着（供无装饰器的直接调用方使用），但本类<b>不再</b>落任何失败记录。
     */
    private volatile boolean terminalFailureClaimed;

    /** 运行指标（task 37，可空）。 */
    private volatile ImagingMetrics metrics;

    /** 容器按需注入指标门面；未注入时埋点全部旁路，旧单测行为不变。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMetrics(ImagingMetrics metrics) {
        this.metrics = metrics;
    }

    private void countHandshake() {
        if (metrics != null) {
            metrics.dicomHandshake();
        }
    }

    /** 注入失败处置出口；传 {@code null} 关闭（退回本类内联审计）。 */
    public void setFailureHandler(RoutingFailureHandler handler) {
        this.failureHandler = handler;
    }

    /**
     * 声明「终局失败由上层记账」，本类此后不再落任何失败审计行。
     *
     * <p>只有 {@link DicomRoutingRetryDecorator} 该调它：装饰器掌握真实重试次数，
     * 而本类只知道「这一步失败了」（retry_count 恒为 0）。两者同时记账，
     * 信息科按 retry_count 区分「一次就挂」与「重试三次还挂」时会被多出来的那行误导。
     *
     * @return 是否由本类继续记账（true = 本类保留记账权）
     */
    public boolean claimTerminalFailureRecording() {
        this.terminalFailureClaimed = true;
        return !failureRecordingOwnedExternally();
    }

    /** 失败记录是否由本类之外的组件写入（handler 已接入，或记账权已被领走）。 */
    private boolean failureRecordingOwnedExternally() {
        return failureHandler != null || terminalFailureClaimed;
    }

    /** 当前失败处置出口是否已接入（运维查看用）。 */
    public boolean hasFailureHandler() {
        return failureHandler != null;
    }

    /**
     * 把失败交给统一出口处置；未注入时只记日志。
     *
     * <p>处置侧的三条副作用（审计 / 死信 / 任务终态）各自兜异常，
     * 这里额外兜一层是为了防止 handler 实现抛错时**顶掉原始路由异常** ——
     * 调用方拿到的必须还是那条带失败码的 {@link RoutingFailureException}，
     * 而不是一条「写审计失败」的异常。
     */
    private void notifyFailureHandler(String step, String peerAet, String patientMpi,
                                      String studyUid, RoutingFailureException failure) {
        RoutingFailureHandler handler = failureHandler;
        if (handler == null || terminalFailureClaimed) {
            return;
        }
        try {
            handler.handle(new RoutingFailureContext(step, peerAet, patientMpi, studyUid, null, null),
                    failure, 0);
        } catch (RuntimeException e) {
            LOG.warn("失败处置出口异常（已吞掉，保留原始路由失败原因）: {}", e.getMessage());
        }
    }

    /** 注入审计通道；传 {@code null} 表示关闭 C-MOVE 失败落审计。 */
    public void setAuditService(AuditService auditService) {
        this.auditService = auditService;
    }

    /** 注入进度监听器；传 {@code null} 复位为 noop。 */
    public void setProgressListener(RetrieveProgressListener listener) {
        this.progressListener = listener == null ? RetrieveProgressListener.noop() : listener;
    }

    /** 当前进度监听器（测试与运维查看用）。 */
    public RetrieveProgressListener getProgressListener() {
        return progressListener;
    }

    @PreDestroy
    void shutdownScheduler() {
        scheduler.shutdownNow();
    }

    /**
     * 生产构造：由 Spring 注入容器内共享线程池（applicationTaskExecutor），避免每次建连新开线程。
     *
     * <p><b>必须标 {@code @Qualifier("applicationTaskExecutor")}</b>：应用启用了
     * {@code @EnableScheduling}，容器里同时存在 {@code applicationTaskExecutor} 与
     * {@code taskScheduler} 两个 {@link Executor} 类型的 bean；只写 {@code Executor}
     * 会触发 {@code NoUniqueBeanDefinitionException} —— <b>整个容器起不来</b>。
     */
    @Autowired
    public DicomRoutingService(PeerRepository peerRepository,
                               DicomConfigProps dicomProps,
                               RoutingConfigProps routingProps,
                               @Qualifier("applicationTaskExecutor") Executor executor) {
        this.peerRepository = peerRepository;
        this.dicomProps = dicomProps;
        this.routingProps = routingProps;
        this.executor = executor;
    }

    /** 单测用构造：自带一个 daemon 线程池。 */
    public DicomRoutingService(PeerRepository peerRepository,
                               DicomConfigProps dicomProps,
                               RoutingConfigProps routingProps) {
        this(peerRepository, dicomProps, routingProps, newDaemonExecutor());
    }

    private static Executor newDaemonExecutor() {
        ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "dicom-routing-scu");
            t.setDaemon(true);
            return t;
        });
        Runtime.getRuntime().addShutdownHook(new Thread(pool::shutdownNow));
        return pool;
    }

    // ==================================================================
    // 对外入口
    // ==================================================================

    /**
     * 按病人主索引 + 检查号查外院 STUDY 列表。
     *
     * @param peerAet         外院节点 AE Title（准入表主键）
     * @param patientMpi      病人主索引（跨院唯一锚，必填）
     * @param accessionNumber 检查号；{@code null}/空 = 不按检查号过滤
     * @return 命中的外院检查列表；<b>零命中返回空列表</b>（不是异常）
     * @throws HandshakeException           准入表无此节点（配置型失败，不重试）
     * @throws RoutingFailureException      关联被拒 / 超时 / 查询失败
     */
    public List<StudyRef> queryStudies(String peerAet, String patientMpi, String accessionNumber) {
        return queryStudies(peerAet, patientMpi, accessionNumber, null);
    }

    /**
     * 按病人主索引 + 检查号（可选）+ StudyInstanceUID（可选）查外院 STUDY 列表。
     *
     * <p>四参重载支持「已知外院 StudyInstanceUID，只想确认它在且核对元数据」的用法
     * （HIS 工单里已经带了外院片号时的路径）；{@code studyUid == null} 时
     * StudyInstanceUID 作为**返回键**而非匹配键，返回该病人的全部 study。
     *
     * @param studyUid 目标 StudyInstanceUID；{@code null}/空 = 通配，返回全部
     */
    public List<StudyRef> queryStudies(String peerAet, String patientMpi,
                                       String accessionNumber, String studyUid) {
        if (patientMpi == null || patientMpi.isBlank()) {
            // 参数缺失是调用方 bug，不是外院故障 —— 不消耗一次重试预算
            throw new IllegalArgumentException("patient_mpi 不能为空");
        }
        Peer peer = requirePeer(peerAet);

        Connection localConn = buildLocalConnection(routingProps.getQueryTimeoutMs());
        Connection remoteConn = buildRemoteConnection(peer);
        String localAet = dicomProps.getLocalAet();

        // ---- 1. 兼容性预校验（与 task 7 握手同一套语义） ----
        if (!localConn.isCompatible(remoteConn)) {
            throw failure(FailureCode.ASSOCIATION_REJECTED,
                    "本地与对端 Connection 不兼容（protocol/TLS/cipher 无交集）: peer=" + peerAet,
                    peerAet, patientMpi, null);
        }

        // ---- 2. 传输语法 + 查询 SOP Class 交集 ----
        List<String> peerTs = splitPeerList(peer.getTransferSyntaxes());
        List<String> agreedTs = intersect(routingProps.getTransferSyntaxes(),
                peerTs.isEmpty() ? routingProps.getTransferSyntaxes() : peerTs);
        if (agreedTs.isEmpty()) {
            throw failure(FailureCode.ASSOCIATION_REJECTED,
                    "传输语法无交集: peer=" + peerAet + " peerTs=" + peerTs,
                    peerAet, patientMpi, null);
        }
        List<String> peerSop = splitPeerList(peer.getPresentContexts());
        List<String> querySopClasses = selectQueryRetrieveSopClasses(peerSop);

        // ---- 3. 建 A-ASSOCIATE-RQ 并发起关联 ----
        AAssociateRQ rq = buildAssociateRq(peerAet, localAet, querySopClasses, agreedTs);
        Association as = null;
        boolean released = false;
        try {
            try {
                as = openAssociation(localConn, remoteConn, rq);
            } catch (IncompatibleConnectionException e) {
                throw failure(FailureCode.ASSOCIATION_REJECTED,
                        "外院 PACS 拒绝本院 AE Title 或协商失败: peer=" + peerAet
                                + " localAet=" + localAet + " (" + e.getMessage() + ")",
                        peerAet, patientMpi, e);
            } catch (AssociationStateException e) {
                throw failure(FailureCode.ASSOCIATION_REJECTED,
                        "关联被外院 PACS 拒绝(A-ASSOCIATE-RJ): peer=" + peerAet
                                + " localAet=" + localAet + " (" + e.getMessage() + ")",
                        peerAet, patientMpi, e);
            } catch (NoPresentationContextException e) {
                throw failure(FailureCode.ASSOCIATION_REJECTED,
                        "外院 PACS 未接受任何查询 presentation context: peer=" + peerAet
                                + " sopClasses=" + querySopClasses,
                        peerAet, patientMpi, e);
            } catch (java.security.GeneralSecurityException e) {
                // TLS 协商失败：两端 cipher/TLS 协议配错 —— 配置型错误，交给信息科改配置
                throw failure(FailureCode.ASSOCIATION_REJECTED,
                        "TLS 协商失败: peer=" + peerAet + " (" + e.getMessage() + ")",
                        peerAet, patientMpi, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure(FailureCode.QUERY_TIMEOUT,
                        "关联建立被中断: peer=" + peerAet, peerAet, patientMpi, e);
            } catch (IOException e) {
                // TCP 可达已在 task 7 握手阶段验证过；此处的 IOException 归为关联层拒绝
                // （AE Title 未授权 / 协商失败 / 链路在握手瞬间断开）。
                // 细分到 connection_refused 由 task 16 的 FailureCodeRegistry 接管。
                throw failure(FailureCode.ASSOCIATION_REJECTED,
                        "关联建立失败: peer=" + peerAet + " localAet=" + localAet
                                + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")",
                        peerAet, patientMpi, e);
            }

            // ---- 4. 发 C-FIND-RQ + 读 C-FIND-RSP ----
            List<StudyRef> results = invokeCFind(as, localAet, patientMpi, accessionNumber, studyUid);

            // ---- 5. 正常释放：A-RELEASE-RQ → A-RELEASE-RP ----
            release(as, peerAet);
            released = true;

            LOG.info("C-FIND 完成: peer={} patientMpi={} 命中 {} 个 study",
                    peerAet, patientMpi, results.size());
            return results;
        } catch (RoutingFailureException e) {
            // task 16：失败码统一分类 + 落审计 + 进死信。handler 接入时本步**不**再内联落审计，
            // 避免同一次失败落两行（见 failureHandler 字段的「单写者约定」）
            notifyFailureHandler(RoutingFailureContext.STEP_QUERY, peerAet, patientMpi, null, e);
            throw e;
        } finally {
            // 异常路径硬关闭：A-ABORT，不等 RP（源 Association.abort 语义）
            if (as != null && !released) {
                abort(as, peerAet);
            }
        }
    }

    // ==================================================================
    // 对外入口 · C-MOVE
    // ==================================================================

    /**
     * 把外院 PACS 上的 {@code studyUid} 单 study 拉回到本端缓存（{@link MoveDestinationStoreScp}）。
     *
     * <h2>主路径（与源仓库一一对应）</h2>
     * <ol>
     *   <li><b>查准入表</b> — {@link PeerRepository#findByPeerAet} 拿 host/port/协商参数；
     *       缺失即 {@link HandshakeException}({@code PEER_NOT_FOUND})</li>
     *   <li><b>起本端 C-STORE-SCP</b> —
     *       <i>(生产) 由 {@link MoveDestinationStoreScp} 单例持有；
     *       <i>(测试) 由调用方通过 {@link #retrieveStudy(Peer, StudyRef, MoveDestinationStoreScp.InstanceSink)}
     *       注入 sink</i></b></li>
     *   <li><b>建关联</b> — 与 {@link #queryStudies} 复用同一套握手流程，
     *       协商 C-MOVE SOP Class（{@code 1.2.840.10008.5.1.4.1.2.2.2}）</li>
     *   <li><b>发 C-MOVE-RQ</b> — {@code as.cmove(cuid, priority, keys, moveDest, tsuid)}；
     *       {@code moveDest} 由 {@code routing.localMoveDestAet} 给出，
     *       外院 PACS 据此解析本端 C-STORE-SCP 的位置</li>
     *   <li><b>外院 MOVE-SCP 主动 C-STORE 推过来</b> — 每条 C-STORE-RQ 由本端 SCP 缓冲
     *       到 {@link MoveDestinationStoreScp.InstanceSink}</li>
     *   <li><b>读 C-MOVE-RSP 终态</b> — {@code DimseRSP.next()} 循环：
     *       Pending 含 sub-op 计数（remaining/completed/failed/warning），
     *       终态 Success=0x0000 / Cancel=0xFE00 / 部分失败=0xB000 / 硬失败 0xA7xx。</li>
     *   <li><b>正常释放</b> — {@code as.release()} 发 A-RELEASE-RQ；异常路径走 {@code as.abort()}</li>
     * </ol>
     *
     * <h2>返回语义（绝不静默改数据）</h2>
     * <ul>
     *   <li>成功：返回 {@link RetrieveResult}（含 instance 字节流 + SOPInstanceUID 列表）</li>
     *   <li>零命中：返回 {@code RetrieveResult.empty(...)} —— 区分
     *       「外院有 study 但 0 个 instance」与「链路故障」</li>
     *   <li>链路或协议错误：抛 {@link RoutingFailureException}，
     *       失败码 ∈ {RETRIEVE_FAILED / MOVE_TIMEOUT / SUB_OP_FAILED / PARTIAL_FAILURE}</li>
     * </ul>
     *
     * <h2>源主路径证据</h2>
     * <ul>
     *   <li>dcm4che README Utilities —— {@code movescu}：本类是其服务化改写</li>
     *   <li>{@code Association.cmove(cuid, priority, keys, moveDest, tsuid)} 五参版本 —— 末位 tsuid</li>
     *   <li>{@code Status.OneOrMoreFailures}（0xB000）—— C-MOVE-RSP Warning 子操作集</li>
     *   <li>{@code Tag.NumberOfRemainingSuboperations / ...CompletedSuboperations / ...
     *       FailedSuboperations / ...WarningSuboperations} —— C-MOVE-RSP 命令字节组内（PS3.7 9.1.3.4）</li>
     * </ul>
     *
     * @param peerAet    外院节点 AE Title
     * @param studyUid   目标 StudyInstanceUID（来自 {@link StudyRef#getStudyUid()}）
     * @param sink       本端缓存（{@link MoveDestinationStoreScp.InstanceSink}）。
     *                   <b>必须**调用前已 start（接受了监听）</b></b>，C-MOVE-RQ 一发出去
     *                   外院 PACS 会立即主动开连接过来收 C-STORE，监听还没起就 0 个 instance。
     */
    public RetrieveResult retrieveStudy(String peerAet, String studyUid,
                                        MoveDestinationStoreScp.InstanceSink sink) {
        if (studyUid == null || studyUid.isBlank()) {
            throw new IllegalArgumentException("study_uid 不能为空");
        }
        if (sink == null) {
            throw new IllegalArgumentException("sink 不能为空（C-MOVE 必须有本端 C-STORE-SCP 接收）");
        }
        Peer peer = requirePeer(peerAet);

        Connection localConn = buildLocalConnection(routingProps.getRetrieveTimeoutMs());
        // dcm4che 5.x cmove 五参版的 sub-op 超时取自 Connection.getRetrieveTimeout()，
        // **不是** getResponseTimeout()。所以拉回主路径必须显式设 retrieveTimeout
        localConn.setRetrieveTimeout(routingProps.getRetrieveTimeoutMs());
        Connection remoteConn = buildRemoteConnection(peer);
        String localAet = dicomProps.getLocalAet();

        // ---- 1. 协商 preset context：MOVE SOP Class + 传输语法 ----
        if (!localConn.isCompatible(remoteConn)) {
            throw failure(FailureCode.ASSOCIATION_REJECTED,
                    "本地与对端 Connection 不兼容（protocol/TLS/cipher 无交集）: peer=" + peerAet,
                    peerAet, null, null);
        }
        List<String> peerTs = splitPeerList(peer.getTransferSyntaxes());
        List<String> agreedTs = intersect(routingProps.getTransferSyntaxes(),
                peerTs.isEmpty() ? routingProps.getTransferSyntaxes() : peerTs);
        if (agreedTs.isEmpty()) {
            throw failure(FailureCode.ASSOCIATION_REJECTED,
                    "传输语法无交集: peer=" + peerAet, peerAet, null, null);
        }
        String moveDestAet = routingProps.getLocalMoveDestAet();

        AAssociateRQ rq = buildAssociateRq(peerAet, localAet, routingProps.getMoveRetrieveSopClasses(), agreedTs);
        Association as = null;
        boolean released = false;
        sink.clear();
        long startNanos = System.nanoTime();
        try {
            // ---- 2. 建关联 ----
            try {
                as = openAssociation(localConn, remoteConn, rq);
            } catch (IncompatibleConnectionException | AssociationStateException
                    | NoPresentationContextException e) {
                throw failure(FailureCode.ASSOCIATION_REJECTED,
                        "C-MOVE 关联被外院 PACS 拒绝: peer=" + peerAet + " (" + e.getMessage() + ")",
                        peerAet, null, e);
            } catch (java.security.GeneralSecurityException e) {
                throw failure(FailureCode.ASSOCIATION_REJECTED,
                        "TLS 协商失败: peer=" + peerAet + " (" + e.getMessage() + ")",
                        peerAet, null, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure(FailureCode.MOVE_TIMEOUT,
                        "C-MOVE 关联建立被中断: peer=" + peerAet, peerAet, null, e);
            } catch (IOException e) {
                throw failure(FailureCode.RETRIEVE_FAILED,
                        "C-MOVE 关联建立失败: peer=" + peerAet + " (" + e.getClass().getSimpleName() + ")",
                        peerAet, null, e);
            }

            // ---- 3. 验协商：至少有一个 MOVE SOP Class 已被对端接受 ----
            String moveSopClass = resolveNegotiatedMoveSopClass(as);
            String transferSyntax = resolveNegotiatedTransferSyntax(as, moveSopClass);

            // ---- 4. 发 C-MOVE-RQ ----
            Attributes keys = buildRetrieveKeys(studyUid);
            DimseRSP rsp;
            int timeoutMs = routingProps.getRetrieveTimeoutMs();
            try {
                // dcm4che 5.x cmove 五参版 —— 实参顺序：(cuid, priority, keys, tsuid, moveDest)，
                // 与字节码 pcFor(cuid, tsuid) 的实参位置一致，**不能与 cfind 混读**
                rsp = as.cmove(moveSopClass, 0 /* MEDIUM priority */, keys,
                        transferSyntax, moveDestAet);
            } catch (NoPresentationContextException e) {
                throw failure(FailureCode.ASSOCIATION_REJECTED,
                        "C-MOVE 未协商到 presentation context: sopClass=" + moveSopClass,
                        peerAet, null, e);
            } catch (SocketTimeoutException e) {
                throw failure(FailureCode.MOVE_TIMEOUT,
                        "C-MOVE 响应超时(" + timeoutMs + "ms): peer=" + peerAet,
                        peerAet, null, e);
            } catch (AssociationStateException e) {
                throw failure(FailureCode.ASSOCIATION_REJECTED,
                        "C-MOVE 关联在发起阶段失效: " + e.getMessage(), peerAet, null, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure(FailureCode.MOVE_TIMEOUT,
                        "C-MOVE 被中断（等待响应超时）: peer=" + peerAet, peerAet, null, e);
            } catch (IOException e) {
                throw failure(FailureCode.RETRIEVE_FAILED,
                        "C-MOVE 发起失败: " + e.getMessage(), peerAet, null, e);
            }

            // ---- 5. 读 C-MOVE-RSP 终态 ----
            RetrieveResult result = invokeCMove(rsp, peerAet, studyUid, startNanos, timeoutMs,
                    transferSyntax, sink);

            // ---- 6. 正常释放 ----
            release(as, peerAet);
            released = true;

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            LOG.info("C-MOVE 完成: peer={} studyUid={} 拉回 {} 个 instance 耗时 {}ms",
                    peerAet, studyUid, result.getInstanceCount(), elapsedMs);
            return result;
        } catch (RoutingFailureException e) {
            notifyFailureHandler(RoutingFailureContext.STEP_RETRIEVE, peerAet, null, studyUid, e);
            recordRetrieveFailure(peerAet, studyUid, e);
            throw e;
        } finally {
            if (as != null && !released) {
                abort(as, peerAet);
            }
        }
    }

    /**
     * 把 C-MOVE 的失败码落进 {@code audit_imaging_fetch}。
     *
     * <p>{@code status_terminal} 取 {@code retrieve_failed}（统一终态名），
     * 失败码明细写在 {@code failure_code} 列 —— 取自
     * {@link RoutingFailureException#getDbFailureCode()}，即
     * {@code retrieve_failed / sub_op_failed / move_timeout / partial_failure /
     * association_rejected}，与 task 16 {@code FailureCodeRegistry} 同一套取值。
     *
     * <h2>为什么这里只落审计、不写 FetchTask</h2>
     * <p>{@code imaging_fetch_tasks} 的状态推进归 {@code FetchTaskService}（task 20）的
     * 五态机所有；路由服务若直接改该表就会出现第二个写入方，绕过
     * {@code advance()} 的跃迁校验与 {@code (patient_mpi, study_uid)} 幂等约束。
     * 因此本任务只保证「失败码 + studyUid + peer 上下文」结构化地落在审计里，
     * task 16 的 {@code RoutingFailureHandler} 与 task 20 的 {@code FetchTaskService}
     * 从这里取码去推进任务状态与进 DLQ，不重复实现。
     *
     * <p>审计写失败只降级为日志 —— 拉阅已失败，审计再失败不该掩盖原始失败原因。
     */
    private void recordRetrieveFailure(String peerAet, String studyUid,
                                      RoutingFailureException failure) {
        if (failureRecordingOwnedExternally()) {
            // 失败记录由统一处置出口 / 重试装饰器接管（task 16），此处让位避免落两行
            return;
        }
        AuditService audit = auditService;
        if (audit == null) {
            return;
        }
        try {
            audit.record(new AuditRow()
                    .withWho("system")
                    .withFromAet("local")
                    .withToAet(peerAet)
                    .withStudyUid(studyUid)
                    .withRetryCount(0)
                    .withStatusTerminal(STATUS_RETRIEVE_FAILED)
                    .withFailureCode(failure.getDbFailureCode()));
        } catch (RuntimeException e) {
            LOG.warn("retrieve 失败审计写入失败（已吞掉，保留原始失败原因）: {}",
                    e.getMessage());
        }
    }

    /**
     * 构建 C-MOVE-RQ 的查询键 —— STUDY 级 + 仅指定 StudyInstanceUID 匹配键，其他返回键零长。
     */
    Attributes buildRetrieveKeys(String studyUid) {
        Attributes keys = new Attributes();
        String[] charsetCodes = SpecificCharacterSet.ASCII.toCodes();
        keys.setString(Tag.SpecificCharacterSet, VR.CS,
                charsetCodes.length == 0 ? "" : String.join("\\", charsetCodes));
        keys.setString(Tag.QueryRetrieveLevel, VR.CS, QUERY_LEVEL_STUDY);
        keys.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
        // 返回键零长 —— 不是匹配键，C-MOVE 不会按它过滤
        keys.setNull(Tag.PatientID, VR.LO);
        return keys;
    }

    // ==================================================================
    // 对外入口 · C-STORE（写入本院 PACS）
    // ==================================================================

    /** C-STORE 成功在 {@code status_terminal} 列的取值。 */
    public static final String STATUS_STORED = "stored";

    /** C-STORE 失败在 {@code status_terminal} 列的取值（与 task 14 的 retrieve_failed 同构）。 */
    public static final String STATUS_STORE_FAILED = "store_failed";

    /** AE Title 长度上限（DICOM PS3.5 §9.1.1）。 */
    private static final int MAX_AET_LENGTH = 16;

    /**
     * 把一批 DICOM instance 写进本院 PACS —— 跨院影像互认主路径第三步。
     *
     * <h2>主路径（与源仓库一一对应）</h2>
     * <ol>
     *   <li><b>逐条解析</b> —— 每个 {@link ByteBuffer} 解成内存 dataset，取出
     *       {@code SOPClassUID(0008,0016)} 与 {@code SOPInstanceUID(0008,0018)}；
     *       SOP Class 不在 {@code routing.storageSopClasses} 白名单内的，<b>建关联前</b>就
     *       标为 skipped（避免连上本院 PACS 再被它逐条拒绝，浪费一次关联）</li>
     *   <li><b>建关联</b> — A-ASSOCIATE-RQ 的 presentation context 是
     *       {@code storageSopClasses × localStoreTransferSyntaxes}（PS3.4 Annex A.5）；
     *       目标 host/port/AET 取 {@code routing.local-pacs-*}（环境变量
     *       {@code LOCAL_PACS_HOST/PORT/AET}）</li>
     *   <li><b>发 C-STORE-RQ</b> — {@code as.cstore(sopClass, sopInstanceUid, 0, DataWriter, tsuid)}；
     *       DataWriter 把内存 dataset 按**协商到的**传输语法重新编码后写出，
     *       <b>不</b>带 file-meta（PS3.5 Annex A：C-STORE 数据集不含 File Meta，
     *       SOP Class 由 presentation context 承载）</li>
     *   <li><b>读 C-STORE-RSP</b> — Pending（0xFF00）继续等，终态 {@code Success(0x0000)}
     *       记一条成功；{@code OneOrMoreFailures / ProcessingFailure / ResourceCannotBeIdentified}
     *       等记一条失败（PS3.4 §C.4.3.1.2.2）</li>
     *   <li><b>正常释放</b> — {@code as.release()} 发 A-RELEASE-RQ；异常路径走 {@code as.abort()}</li>
     *   <li><b>落审计</b> — 全成功落 {@code status_terminal=stored}（含 {@code to_aet=本院 PACS AET}、
     *       {@code sop_class_uid}、{@code transfer_syntax}）；有失败落 {@code store_failed}
     *       + {@code failure_code}（PS3.4 §C.4.3.1.2.2 的状态码落到失败码列）</li>
     * </ol>
     *
     * <h2>UID 不变量（本步最硬的约束）</h2>
     * <p><b>SOPInstanceUID 与 StudyInstanceUID 逐字透传，一个字符都不改。</b>
     * 跨院互认的整个业务前提是「同一个影像实体在两院 PACS 里指向同一个 UID」——
     * 一旦我们在写入前重编 UID，本院 PACS 里就会多出一条「看着像但查不到对应外院报告」
     * 的孤儿影像，医生点开看到的是一份**无出处的片子**，比调阅失败危险得多。
     * 传输语法可以变（外院 JPEG 2000 → 本院 Explicit VR LE），UID 不可变。
     *
     * <h2>返回语义（绝不静默改数据）</h2>
     * <ul>
     *   <li>全部 instance 拿到 Success：返回 {@link StoreResult}</li>
     *   <li>部分失败：抛 {@code RoutingFailureException(PARTIAL_FAILURE)}（消息里带成功/失败计数）</li>
     *   <li>全部失败 / 无可写 instance：抛 {@code STORE_FAILED}</li>
     *   <li>本院 PACS 不应答：抛 {@code STORE_TIMEOUT}</li>
     * </ul>
     *
     * <h2>为什么「部分失败」也要抛而不是返回残缺结果</h2>
     * <p>{@code FetchTask} 推进到 {@code stored} 等于向 HIS 宣告「影像已在本院 PACS」。
     * 一半没进去却宣告成功，是错误的业务状态推进；本服务宁可让任务停在
     * {@code failed} + 进死信（task 16 / 20 处理），让人来看一眼，也不让它悄悄绿。
     *
     * <h2>源主路径证据</h2>
     * <ul>
     *   <li>dcm4che README Utilities —— {@code storescu}：本类是其服务化改写</li>
     *   <li>{@code Association.cstore(cuid, iuid, priority, DataWriter, tsuid)} —— 本类逐条调用链</li>
     *   <li>{@code Association.ccancel(cuid, iuid)} —— 本轮未用（部分失败按整调阅失败处理，
     *       不做「已发的让它发完、半路取消」的半吊子语义）</li>
     *   <li>PS3.4 Annex A.5 Storage SOP Classes —— presentation context 的来源</li>
     * </ul>
     *
     * @param studyUid  目标 study（只用于日志与审计；协议上 C-STORE 不带 study 级概念）
     * @param instances 各 instance 的 DICOM 字节（Part-10 或裸 dataset 均可）
     * @param fromAet   影像来源 AE（外院节点）；审计 {@code from_aet} 列
     */
    public StoreResult storeToLocal(String studyUid, List<ByteBuffer> instances, String fromAet) {
        if (studyUid == null || studyUid.isBlank()) {
            throw new IllegalArgumentException("study_uid 不能为空");
        }
        if (instances == null || instances.isEmpty()) {
            // 空列表不是「成功写入 0 条」—— 是调用方 bug。不建联、不落审计。
            throw new IllegalArgumentException("instances 不能为空（C-STORE 没有对象可写）");
        }
        // C-STORE 写本院 PACS 也是一次独立的 DICOM 建联
        countHandshake();
        String targetAet = requireLocalPacsAet();
        String targetHost = requireLocalPacsHost();

        // ---- 1. 逐条解析 + 白名单预检（建关联之前） ----
        List<ParsedInstance> parsed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (int i = 0; i < instances.size(); i++) {
            ParsedInstance pi = parseInstance(instances.get(i), i);
            if (pi == null) {
                skipped.add("#" + i + "(unreadable)");
                continue;
            }
            if (!routingProps.getStorageSopClasses().contains(pi.sopClassUid)) {
                LOG.warn("instance #{} SOPClass={} 不在本端 storageSopClasses 白名单，跳过不写: peer={} study={}",
                        i, pi.sopClassUid, targetAet, studyUid);
                skipped.add("#" + i + "(" + pi.sopClassUid + ")");
                continue;
            }
            parsed.add(pi);
        }

        // 白名单把整批都筛掉 → 明确失败，绝不「连都不连就当成功」
        if (parsed.isEmpty()) {
            RoutingFailureException e = failure(FailureCode.STORE_FAILED,
                    "无可写入本院 PACS 的 instance（" + instances.size() + " 条全部被跳过: "
                            + String.join("; ", skipped) + "）：本端 storageSopClasses="
                            + String.join(",", routingProps.getStorageSopClasses()),
                    targetAet, null);
            recordStoreFailure(targetAet, studyUid, skipped, e);
            throw e;
        }

        // ---- 2. 建关联 ----
        int timeoutMs = routingProps.getStoreTimeoutMs();
        Connection localConn = buildLocalConnection(timeoutMs);
        Connection remoteConn = buildStoreTargetConnection(targetHost, routingProps.getLocalPacsPort());
        String localAet = dicomProps.getLocalAet();

        AAssociateRQ rq = buildAssociateRq(targetAet, localAet,
                routingProps.getStorageSopClasses(), routingProps.getLocalStoreTransferSyntaxes());

        Association as = null;
        boolean released = false;
        long startNanos = System.nanoTime();
        StoreResult.Builder builder = StoreResult.builder()
                .targetPacsAet(targetAet)
                .targetPacsHost(targetHost)
                .requestedCount(instances.size())
                .skippedCount(skipped.size());
        List<String> storedUids = new ArrayList<>();
        List<String> storedSops = new ArrayList<>();
        List<String> storedTs = new ArrayList<>();
        long storedBytes = 0L;
        List<String> storeFailures = new ArrayList<>();
        Integer lastStatus = null;

        try {
            try {
                as = openAssociation(localConn, remoteConn, rq);
            } catch (IncompatibleConnectionException | AssociationStateException
                    | NoPresentationContextException e) {
                throw failure(FailureCode.ASSOCIATION_REJECTED,
                        "C-STORE 关联被本院 PACS 拒绝: target=" + targetAet + " (" + e.getMessage() + ")",
                        targetAet, null, e);
            } catch (java.security.GeneralSecurityException e) {
                throw failure(FailureCode.ASSOCIATION_REJECTED,
                        "与本院 PACS 的 TLS 协商失败: target=" + targetAet + " (" + e.getMessage() + ")",
                        targetAet, null, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure(FailureCode.STORE_TIMEOUT,
                        "C-STORE 关联建立被中断: target=" + targetAet, targetAet, null, e);
            } catch (IOException e) {
                throw storeConnectFailureOr(e, targetAet, targetHost, routingProps.getLocalPacsPort());
            }

            // ---- 3. 逐条 C-STORE ----
            for (ParsedInstance pi : parsed) {
                PresentationContext pc = acceptedStorageContext(as, pi.sopClassUid);
                if (pc == null) {
                    // 提议了但本院 PACS 没接受 —— 多半是它的存储能力表里没这一类
                    storeFailures.add(pi.sopInstanceUid + "(sop_class_not_accepted)");
                    continue;
                }
                String tsuid = pc.getTransferSyntax();
                int status = storeOne(as, pi, tsuid, startNanos, timeoutMs);
                lastStatus = status;
                if (status == Status.Success) {
                    storedUids.add(pi.sopInstanceUid);
                    storedSops.add(pi.sopClassUid);
                    storedTs.add(tsuid);
                    storedBytes += pi.byteLength;
                } else {
                    storeFailures.add(pi.sopInstanceUid + "(0x" + String.format("%04X", status) + ")");
                }
            }

            // ---- 4. 正常释放 ----
            release(as, targetAet);
            released = true;

            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            builder.sopInstanceUids(storedUids)
                    .sopClassUids(storedSops)
                    .transferSyntaxes(storedTs)
                    .totalBytes(storedBytes)
                    .durationMs(durationMs)
                    .terminalStatusHex(Status.Success);

            // ---- 5. 汇总：全成功才返回；否则抛，绝不返回残缺结果 ----
            if (storeFailures.isEmpty()) {
                recordStoreSuccess(targetAet, fromAet, studyUid, builder);
                StoreResult result = builder.build();
                LOG.info("C-STORE 完成: target={} studyUid={} 写入 {} 个 instance 耗时 {}ms",
                        targetAet, studyUid, result.getSopInstanceCount(), durationMs);
                return result;
            }

            String detail = String.format(
                    "C-STORE 部分失败 target=%s study=%s: 成功 %d / 跳过 %d / 失败 %d 明细=[%s]",
                    targetAet, studyUid, storedUids.size(), skipped.size(),
                    storeFailures.size(), String.join("; ", storeFailures));
            if (storedUids.isEmpty()) {
                RoutingFailureException e = failure(FailureCode.STORE_FAILED, detail, targetAet, null);
                recordStoreFailure(targetAet, studyUid, storeFailures, e);
                throw e;
            }
            RoutingFailureException e = failure(FailureCode.PARTIAL_FAILURE, detail, targetAet, null);
            recordStoreFailure(targetAet, studyUid, storeFailures, e);
            throw e;
        } catch (RoutingFailureException e) {
            notifyFailureHandler(RoutingFailureContext.STEP_STORE, targetAet, null, studyUid, e);
            if (!isStoreFailureAlreadyRecorded(e)) {
                recordStoreFailure(targetAet, studyUid, storeFailures, e);
            }
            throw e;
        } finally {
            if (as != null && !released) {
                abort(as, targetAet);
            }
        }
    }

    /** {@link #storeToLocal(String, List, String)} 的来源缺省版（审计 {@code from_aet=local}）。 */
    public StoreResult storeToLocal(String studyUid, List<ByteBuffer> instances) {
        return storeToLocal(studyUid, instances, "local");
    }

    /**
     * 省略 studyUid 的写入 —— 由第一条 instance 的 {@code (0020,000D)} 反推。
     *
     * <p>用于「手上只有一批字节、还没单独记住片号」的调用方（WADO-RS 拉回直接落 PACS、
     * 死信重放）。反推不到时落 {@code "(unknown)"} 而非编一个 UID，见
     * {@link #firstStudyUidOf}。
     */
    public StoreResult storeToLocal(List<ByteBuffer> instances, String fromAet) {
        if (instances == null || instances.isEmpty()) {
            throw new IllegalArgumentException("instances 不能为空（C-STORE 没有对象可写）");
        }
        String studyUid = firstStudyUidOf(instances);
        return storeToLocal(studyUid, instances, fromAet);
    }

    /** {@link #storeToLocal(List, String)} 的来源缺省版。 */
    public StoreResult storeToLocal(List<ByteBuffer> instances) {
        return storeToLocal(instances, "local");
    }

    /** 主路径三步闭环入口：{@link #retrieveStudy} 的产物直接写进本院 PACS。 */
    public StoreResult storeToLocal(RetrieveResult retrieved, String fromAet) {
        if (retrieved == null) {
            throw new IllegalArgumentException("retrieved 不能为空");
        }
        List<ByteBuffer> buffers = new ArrayList<>(retrieved.getInstanceCount());
        for (byte[] bytes : retrieved.getInstances()) {
            buffers.add(ByteBuffer.wrap(bytes));
        }
        String studyUid = firstStudyUidOf(retrieved.getInstances(), retrieved.getTransferSyntax());
        return storeToLocal(studyUid, buffers, fromAet);
    }

    /** {@link #storeToLocal(RetrieveResult, String)} 的来源缺省版。 */
    public StoreResult storeToLocal(RetrieveResult retrieved) {
        return storeToLocal(retrieved, "local");
    }

    /**
     * 从已拉回的字节里取第一条的 StudyInstanceUID（仅用于日志与审计）。
     *
     * <p>取不到就用 {@code "(unknown)"} —— <b>不</b>编一个 UID 出来：
     * 审计里出现一个看似合法却不存在的 study UID，比留空更难排查。
     */
    private static String firstStudyUidOf(List<byte[]> instances, String transferSyntax) {
        if (instances == null) {
            return "(unknown)";
        }
        for (byte[] bytes : instances) {
            String uid = studyUidOf(bytes, transferSyntax);
            if (uid != null) {
                return uid;
            }
        }
        return "(unknown)";
    }

    /** {@link ByteBuffer} 版的反推。 */
    private static String firstStudyUidOf(List<ByteBuffer> instances) {
        for (ByteBuffer buffer : instances) {
            String uid = studyUidOf(buffer == null ? null : toBytes(buffer),
                    UID.ExplicitVRLittleEndian);
            if (uid != null) {
                return uid;
            }
        }
        return "(unknown)";
    }

    /** 单条字节的 StudyInstanceUID；读不出/字段缺失返回 {@code null}（不编造）。 */
    private static String studyUidOf(byte[] bytes, String transferSyntax) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            Attributes ds = readDataset(bytes, transferSyntax);
            String uid = ds.getString(Tag.StudyInstanceUID);
            return uid == null || uid.isBlank() ? null : uid;
        } catch (IOException | RuntimeException e) {
            LOG.debug("取 study UID 失败，继续下一条: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 发一条 C-STORE-RQ 并读回终态状态码。
     *
     * <p>C-STORE 一次只有一条 instance，所以终态就一个：DICOM 允许先发若干
     * {@code Pending(0xFF00)} 再发一条终态（P-1 协议里常见），这里循环等终态。
     */
    private int storeOne(Association as, ParsedInstance pi, String tsuid,
                         long startNanos, int timeoutMs) {
        DimseRSP rsp;
        try {
            rsp = as.cstore(pi.sopClassUid, pi.sopInstanceUid, 0 /* MEDIUM priority */,
                    new InstanceDataWriter(pi.dataset), tsuid);
        } catch (NoPresentationContextException e) {
            throw failure(FailureCode.STORE_FAILED,
                    "C-STORE 未协商到 presentation context: sopClass=" + pi.sopClassUid,
                    as.getRemoteAET(), null, e);
        } catch (SocketTimeoutException e) {
            throw failure(FailureCode.STORE_TIMEOUT,
                    "C-STORE 响应超时(" + timeoutMs + "ms): " + pi.sopInstanceUid,
                    as.getRemoteAET(), null, e);
        } catch (AssociationStateException e) {
            throw failure(FailureCode.STORE_FAILED,
                    "C-STORE 关联在发起阶段失效: " + e.getMessage(), as.getRemoteAET(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(FailureCode.STORE_TIMEOUT,
                    "C-STORE 被中断（等待响应超时）: " + pi.sopInstanceUid, as.getRemoteAET(), null, e);
        } catch (IOException e) {
            throw failure(FailureCode.STORE_FAILED,
                    "C-STORE 发起失败: " + e.getMessage(), as.getRemoteAET(), null, e);
        }

        while (true) {
            boolean hasNext;
            try {
                hasNext = rsp.next();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure(FailureCode.STORE_TIMEOUT,
                        "C-STORE 被中断（等待响应超时）: " + pi.sopInstanceUid,
                        as.getRemoteAET(), null, e);
            } catch (IOException e) {
                throw storeFailureOr(e, startNanos, timeoutMs, pi.sopInstanceUid, as.getRemoteAET());
            }
            if (!hasNext) {
                throw storeFailureOr(new IOException("未收到终态响应即结束"),
                        startNanos, timeoutMs, pi.sopInstanceUid, as.getRemoteAET());
            }
            int status = rsp.getCommand().getInt(Tag.Status, -1);
            if (Status.isPending(status)) {
                continue;
            }
            return status;
        }
    }

    /**
     * 把「建关联阶段」的 {@link IOException} 分成两类。
     *
     * <p>dcm4che 把对端的 A-ASSOCIATE-RJ 也以 {@code IOException} 抛出（消息里带
     * {@code A-ASSOCIATE-RJ[...]}），与「TCP 连不上」是同一个类型。但两者对运维的
     * 含义完全不同：
     * <ul>
     *   <li><b>A-ASSOCIATE-RJ</b> —— 本院 PACS 收到了我们的 A-ASSOCIATE-RQ 但拒绝
     *       （AE Title 未授权 / 本节点未登记）。这是<b>配置问题</b>，重试一万次也没用，
     *       信息科要去 PACS 侧加本节点。</li>
     *   <li><b>其它 IOException</b> —— {@code ConnectException} 等，是网络/服务未起，
     *       重试有意义。</li>
     * </ul>
     * 混在一起报 {@code STORE_FAILED} 会让运维去查网络，而真实原因在 PACS 的
     * AE 白名单里 —— 归类必须分开。
     */
    private RoutingFailureException storeConnectFailureOr(IOException cause, String targetAet,
                                                          String targetHost, int targetPort) {
        String msg = cause == null ? "" : String.valueOf(cause.getMessage());
        boolean rejected = msg.contains("A-ASSOCIATE-RJ");
        String where = "target=" + targetAet + "@" + targetHost + ":" + targetPort;
        return failure(rejected ? FailureCode.ASSOCIATION_REJECTED : FailureCode.STORE_FAILED,
                (rejected
                        ? "本院 PACS 拒绝本节点（A-ASSOCIATE-RJ）: " + where
                          + " —— 请影像科在本院 PACS 的 AE 白名单里登记本服务节点 "
                          + dicomProps.getLocalAet()
                        : "连不上本院 PACS: " + where)
                        + " (" + msg + ")",
                targetAet, null, cause);
    }

    /** 把「读 C-STORE 响应阶段」的异常归类为 {@code STORE_TIMEOUT} 或 {@code STORE_FAILED}。 */
    private RoutingFailureException storeFailureOr(Throwable cause, long startNanos,
                                                  int timeoutMs, String sopInstanceUid, String targetAet) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        boolean timedOut = elapsedMs >= timeoutMs
                || cause instanceof SocketTimeoutException
                || (cause != null && cause.getMessage() != null
                    && cause.getMessage().toLowerCase(Locale.ROOT).contains("timeout"));
        return failure(timedOut ? FailureCode.STORE_TIMEOUT : FailureCode.STORE_FAILED,
                (timedOut ? "C-STORE 响应超时(" + timeoutMs + "ms)" : "C-STORE 读响应失败")
                        + ": " + sopInstanceUid + " 已耗时 " + elapsedMs + "ms"
                        + (cause == null ? "" : " cause=" + cause.getClass().getSimpleName()
                                + ": " + cause.getMessage()),
                targetAet, null, cause);
    }

    /** 取该存储 SOP Class 上真正被本院 PACS 接受的 presentation context；没接受返回 null。 */
    private PresentationContext acceptedStorageContext(Association as, String sopClass) {
        try {
            PresentationContext pc = as.pcFor(sopClass, null);
            return pc != null && pc.isAccepted() ? pc : null;
        } catch (NoPresentationContextException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // C-STORE：instance 解析 / 写流 / 审计
    // ------------------------------------------------------------------

    /** 解析后待写的一条 instance。UID 原样留存，绝不改写。 */
    private static final class ParsedInstance {
        final Attributes dataset;
        final String sopClassUid;
        final String sopInstanceUid;
        final int byteLength;

        ParsedInstance(Attributes dataset, String sopClassUid, String sopInstanceUid, int byteLength) {
            this.dataset = dataset;
            this.sopClassUid = sopClassUid;
            this.sopInstanceUid = sopInstanceUid;
            this.byteLength = byteLength;
        }
    }

    /**
     * 字节 → 内存 dataset + 必需 UID。
     *
     * <p>返回 {@code null} 表示这条读不出来（调用方计入 skipped，不静默丢弃）。
     * 关键约束：{@code SOPInstanceUID} 缺失时<b>不编一个</b>——
     * C-STORE 的 instance 标识就是它，编造等于往本院 PACS 塞一条无主影像。
     *
     * <p>SOP Class 取 dataset 里的 {@code (0008,0016)} 而非 file-meta 的
     * {@code MediaStorageSOPClassUID}：C-STORE 的 SOP Class 由 presentation context
     * 承载（PS3.5 Annex A），dataset 里那个是权威副本；两边不一致时 PS3.5 §C.7.1
     * 要求以 presentation context 为准，正好也是我们用 dataset 值去选 context 的理由。
     */
    private ParsedInstance parseInstance(ByteBuffer buffer, int index) {
        if (buffer == null) {
            return null;
        }
        byte[] bytes = toBytes(buffer);
        if (bytes == null || bytes.length == 0) {
            LOG.warn("instance #{} 字节为空，跳过不写", index);
            return null;
        }
        Attributes dataset;
        try {
            dataset = readDataset(bytes, UID.ExplicitVRLittleEndian);
        } catch (IOException | RuntimeException e) {
            LOG.warn("instance #{} 解析失败，跳过不写: {} / {}",
                    index, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
        String sopClassUid = dataset.getString(Tag.SOPClassUID);
        String sopInstanceUid = dataset.getString(Tag.SOPInstanceUID);
        if (sopClassUid == null || sopClassUid.isBlank()) {
            LOG.warn("instance #{} 缺 SOPClassUID(0008,0016)，跳过不写", index);
            return null;
        }
        if (sopInstanceUid == null || sopInstanceUid.isBlank()) {
            LOG.warn("instance #{} 缺 SOPInstanceUID(0008,0018)，跳过不写（不编造 UID）", index);
            return null;
        }
        return new ParsedInstance(dataset, sopClassUid, sopInstanceUid, bytes.length);
    }

    /**
     * DICOM 字节 → dataset。
     *
     * <p>兼容两种形态（本服务两条上游路径产出不同形态）：
     * <ul>
     *   <li><b>Part-10 文件</b>（128 字节前导 + {@code DICM} + file-meta）—— WADO-RS 拉回
     *       （task 11）与部分外院 PACS 的 C-MOVE 会给这种；用无参
     *       {@code DicomInputStream} 让 dcm4che 自动剥前导读 file-meta</li>
     *   <li><b>裸 dataset</b>（无前导无 file-meta）—— 本服务 {@code MoveDestinationStoreScp}
     *       收到的 C-STORE dataset 段就是这个形态；按给定传输语法直接读</li>
     * </ul>
     *
     * <p>无前导的 Part-10（老设备直接以 {@code (0002,0000)} 开头）也按文件读，
     * 靠 {@link #isPart10(byte[])} 的首元素判别覆盖。
     */
    static Attributes readDataset(byte[] bytes, String fallbackTransferSyntax) throws IOException {
        try (DicomInputStream dis = isPart10(bytes)
                ? new DicomInputStream(new java.io.ByteArrayInputStream(bytes))
                : new DicomInputStream(new java.io.ByteArrayInputStream(bytes),
                    fallbackTransferSyntax)) {
            Attributes ds = dis.readDataset();
            return ds == null ? new Attributes() : ds;
        }
    }

    /** 是否 DICOM Part-10（含 128 字节前导，或以 file-meta 首元素 {@code (0002,0000)} 开头）。 */
    static boolean isPart10(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            return false;
        }
        if (bytes.length >= 132
                && bytes[128] == 'D' && bytes[129] == 'I'
                && bytes[130] == 'C' && bytes[131] == 'M') {
            return true;
        }
        int group = (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8);
        int element = (bytes[2] & 0xFF) | ((bytes[3] & 0xFF) << 8);
        return group == 0x0002 && element == 0x0000;
    }

    /** 按 ByteBuffer 的 position→limit 取出字节（标准 Buffer 语义，不做任何隐式 rewind）。 */
    static byte[] toBytes(ByteBuffer buffer) {
        ByteBuffer dup = buffer.duplicate();
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }

    /**
     * C-STORE-SCU 的 DataWriter —— 把内存 dataset 按协商到的传输语法重新编码写出。
     *
     * <p>{@code writeDataset(null, dataset)} 的 fileMeta 传 null：C-STORE 数据集
     * 不含 File Meta（PS3.5 Annex A.4）。传空 {@code Attributes()} 会被 dcm4che
     * 误判为「有 file-meta 要写」而进 {@code Attributes.writeGroupTo(0)} 抛
     * {@code IllegalStateException("No attributes")}，表现为整条关联被 A-ABORT。
     *
     * <p>这里也正是「传输语法可换、UID 不可换」的落点：dataset 是解码后的内存态，
     * dcm4che 按 {@code tsuid} 重新编码，但 {@code (0008,0016)/(0008,0018)/(0020,000D)}
     * 三个 UID 原样留在 dataset 里。
     */
    private static final class InstanceDataWriter implements org.dcm4che3.net.DataWriter {

        private final Attributes dataset;

        InstanceDataWriter(Attributes dataset) {
            this.dataset = dataset;
        }

        @Override
        public void writeTo(org.dcm4che3.net.PDVOutputStream out, String tsuid) throws IOException {
            DicomOutputStream dos =
                    new DicomOutputStream((java.io.OutputStream) out, tsuid);
            dos.setEncodingOptions(DicomEncodingOptions.DEFAULT);
            dos.writeDataset(null, dataset);
            dos.flush();
        }
    }

    /**
     * C-STORE 全部成功的审计行。
     *
     * <p>{@code sop_class_uid} / {@code transfer_syntax} 两列是单值列，一个 study 里
     * CT/MR 走不同存储 SOP Class 是常态，这里用去重集合（逗号分隔）如实记录，
     * 便于信息科事后回答「这批片子是以什么形态进的本院 PACS」。
     */
    private void recordStoreSuccess(String targetAet, String fromAet, String studyUid,
                                    StoreResult.Builder builder) {
        AuditService audit = auditService;
        if (audit == null) {
            return;
        }
        StoreResult snapshot = builder.build();
        try {
            audit.record(new AuditRow()
                    .withWho("system")
                    .withFromAet(fromAet == null || fromAet.isBlank() ? "local" : fromAet)
                    .withToAet(targetAet)
                    .withStudyUid(studyUid)
                    .withSopClassUid(String.join(",", snapshot.distinctSopClassUids()))
                    .withTransferSyntax(String.join(",", snapshot.distinctTransferSyntaxes()))
                    .withRetryCount(0)
                    .withStatusTerminal(STATUS_STORED));
        } catch (RuntimeException e) {
            LOG.warn("store 成功审计写入失败（已吞掉，影像已在本院 PACS）: {}", e.getMessage());
        }
    }

    /**
     * C-STORE 失败的审计行。
     *
     * <p>与 task 14 同构：审计写失败只降级为日志 —— 写入已失败，审计再失败
     * 不该掩盖原始失败原因（那会让运维去查审计库而不是查 PACS）。
     */
    private void recordStoreFailure(String targetAet, String studyUid, List<String> details,
                                    RoutingFailureException failure) {
        if (failureRecordingOwnedExternally()) {
            // 失败记录由统一处置出口 / 重试装饰器接管（task 16），此处让位避免落两行
            return;
        }
        AuditService audit = auditService;
        if (audit == null) {
            return;
        }
        try {
            audit.record(new AuditRow()
                    .withWho("system")
                    .withFromAet("local")
                    .withToAet(targetAet)
                    .withStudyUid(studyUid)
                    .withRetryCount(0)
                    .withStatusTerminal(STATUS_STORE_FAILED)
                    .withFailureCode(failure.getDbFailureCode())
                    .withTransferSyntax(details == null || details.isEmpty()
                            ? null : String.join(",", details)));
        } catch (RuntimeException e) {
            LOG.warn("store 失败审计写入失败（已吞掉，保留原始失败原因）: {}", e.getMessage());
        }
    }

    /**
     * 判断异常是否已经在 {@link #recordStoreFailure} 里记过，避免同一次失败落两行。
     *
     * <p>失败明细以 {@link #STATUS_STORE_FAILED} 审计行的存在为准：外层 catch
     * 只在自己没走过内层记录时才补记。
     */
    private boolean isStoreFailureAlreadyRecorded(RoutingFailureException e) {
        return e.getFailureCode() == FailureCode.STORE_FAILED
                || e.getFailureCode() == FailureCode.PARTIAL_FAILURE;
    }

    private String requireLocalPacsAet() {
        String aet = routingProps.getLocalPacsAet();
        if (aet == null || aet.isBlank()) {
            throw failure(FailureCode.STORE_FAILED,
                    "本院 PACS AE Title 未配置（routing.local-pacs-aet / 环境变量 LOCAL_PACS_AET）",
                    "(unconfigured)", null);
        }
        // DICOM PS3.5 §9.1.1：AE Title 最长 16 字符。不校验的话 dcm4che 会在
        // AAssociateRQ.setCalledAET 抛裸 IllegalArgumentException("calledAET: ..."),
        // 信息科看到的是一个协议栈内部异常，而不是「你的 LOCAL_PACS_AET 配错了」。
        if (aet.length() > MAX_AET_LENGTH) {
            throw failure(FailureCode.STORE_FAILED,
                    "本院 PACS AE Title 超长（" + aet.length() + " > " + MAX_AET_LENGTH
                            + " 字符）: '" + aet + "'（LOCAL_PACS_AET）。"
                            + "DICOM PS3.5 限 AE Title ≤ 16 字符；"
                            + "若影像科登记的是中文名，请与 PACS 厂商约定一个 ASCII 的 AET 后再填这里",
                    aet, null);
        }
        return aet;
    }

    private String requireLocalPacsHost() {
        String host = routingProps.getLocalPacsHost();
        if (host == null || host.isBlank()) {
            throw failure(FailureCode.STORE_FAILED,
                    "本院 PACS 主机未配置（routing.local-pacs-host / 环境变量 LOCAL_PACS_HOST）",
                    routingProps.getLocalPacsAet(), null);
        }
        return host;
    }

    /** C-STORE 的远端 Connection —— 不走准入表（本院 PACS 是固定节点，不是外院 peer）。 */
    private Connection buildStoreTargetConnection(String host, int port) {
        Connection remote = new Connection();
        remote.setHostname(host);
        remote.setPort(port);
        remote.setProtocol(Connection.Protocol.DICOM);
        remote.setConnectTimeout(dicomProps.getRemoteConnectTimeoutMs());
        // 本院 PACS 与本服务同机房，默认不走 TLS；显式声明空 cipher/protocol，
        // 避免 dcm4che 5.x 默认 TLSv1.2 在无证书环境下把正常链路打成握手异常
        remote.setTlsCipherSuites(new String[0]);
        remote.setTlsProtocols(new String[0]);
        return remote;
    }

    /**
     * 取关联里实际被接受的 C-MOVE SOP Class（按偏好顺序：Study Root → Patient Root）。
     */
    String resolveNegotiatedMoveSopClass(Association as) {
        for (String sopClass : routingProps.getMoveRetrieveSopClasses()) {
            try {
                org.dcm4che3.net.pdu.PresentationContext pc = as.pcFor(sopClass, null);
                if (pc != null && pc.isAccepted()) {
                    return sopClass;
                }
            } catch (NoPresentationContextException ignored) {
                // 继续试下一个
            }
        }
        throw failure(FailureCode.ASSOCIATION_REJECTED,
                "关联内没有任何被接受的 C-MOVE SOP Class: 已协商=" + describeAccepted(as),
                as.getRemoteAET(), null, null);
    }

    /**
     * 把 C-MOVE-RSP 流抽干 —— Pending 只读 sub-op 计数（不读 dataset），
     * 终态汇总到 {@link RetrieveResult.Builder}。
     */
    private RetrieveResult invokeCMove(DimseRSP rsp, String peerAet, String studyUid,
                                        long startNanos, int timeoutMs,
                                        String transferSyntax,
                                        MoveDestinationStoreScp.InstanceSink sink) {
        // Builder 仅先收 transferSyntax；instances/sopInstanceUids 等终态后**统一从 sink 取**
        // —— 必须在 sub-op 推完才取，否则 sink 还在增长，instances 与 sopUids 时间点错位
        RetrieveResult.Builder builder = RetrieveResult.builder()
                .transferSyntax(transferSyntax);

        int remainingAtTerminal = 0;
        int completedAtTerminal = 0;
        int failedAtTerminal = 0;
        int warningAtTerminal = 0;
        boolean seenSubOpCounts = false;

        while (true) {
            boolean hasNext;
            try {
                hasNext = rsp.next();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure(FailureCode.MOVE_TIMEOUT,
                        "C-MOVE 被中断（等待响应超时）: peer=" + peerAet, peerAet, null, e);
            } catch (IOException e) {
                throw moveFailureOr(e, startNanos, timeoutMs,
                        "读 C-MOVE 响应失败", peerAet);
            }

            if (!hasNext) {
                throw moveFailureOr(asExceptionOr(new IOException("未收到终态响应即结束")),
                        startNanos, timeoutMs, "C-MOVE 流异常结束", peerAet);
            }

            Attributes cmd = rsp.getCommand();
            int status = cmd.getInt(Tag.Status, -1);

            if (Status.isPending(status)) {
                // Pending：C-MOVE 通常不带 dataset。仅取 sub-op 计数（dcm4che 也会放在 cmd 字段里）
                remainingAtTerminal = cmd.getInt(Tag.NumberOfRemainingSuboperations, remainingAtTerminal);
                completedAtTerminal = cmd.getInt(Tag.NumberOfCompletedSuboperations, completedAtTerminal);
                failedAtTerminal = cmd.getInt(Tag.NumberOfFailedSuboperations, failedAtTerminal);
                warningAtTerminal = cmd.getInt(Tag.NumberOfWarningSuboperations, warningAtTerminal);
                seenSubOpCounts = true;
                emitProgress(RetrieveProgress.pending(peerAet, studyUid,
                        remainingAtTerminal, completedAtTerminal,
                        failedAtTerminal, warningAtTerminal, sink.size()));
                continue;
            }

            // 终态响应：再读一次 sub-op 计数（外院通常在最后一帧 Pending → 下一帧 Success 的 cmd 一并带）
            remainingAtTerminal = cmd.getInt(Tag.NumberOfRemainingSuboperations, remainingAtTerminal);
            completedAtTerminal = cmd.getInt(Tag.NumberOfCompletedSuboperations, completedAtTerminal);
            failedAtTerminal = cmd.getInt(Tag.NumberOfFailedSuboperations, failedAtTerminal);
            warningAtTerminal = cmd.getInt(Tag.NumberOfWarningSuboperations, warningAtTerminal);
            seenSubOpCounts = true;
            // 终态帧必发：即使分桶没前进，监听方也要知道这次拉阅的结局
            emitProgress(RetrieveProgress.terminal(peerAet, studyUid,
                    remainingAtTerminal, completedAtTerminal,
                    failedAtTerminal, warningAtTerminal, sink.size(), status));

            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;

            if (status == Status.Success) {
                builder.instances(sink.getInstances())
                        .sopInstanceUids(sink.getSopInstanceUids())
                        .completedSubOps(completedAtTerminal)
                        .remainingSubOps(remainingAtTerminal)
                        .failedSubOps(failedAtTerminal)
                        .warningSubOps(warningAtTerminal)
                        .terminalSuccess(true)
                        .terminalStatusHex(status)
                        .durationMs(durationMs);
                return builder.build();
            }
            if (status == Status.Cancel) {
                // 外院 PACS 主动 Cancel —— 视为部分完成（一般发生在实例数 > 配置上限）
                builder.instances(sink.getInstances())
                        .sopInstanceUids(sink.getSopInstanceUids())
                        .completedSubOps(completedAtTerminal)
                        .remainingSubOps(remainingAtTerminal)
                        .failedSubOps(failedAtTerminal)
                        .warningSubOps(warningAtTerminal)
                        .terminalSuccess(false)
                        .terminalStatusHex(status)
                        .durationMs(durationMs);
                throw failure(FailureCode.PARTIAL_FAILURE,
                        String.format("C-MOVE 被对端 Cancel(0x%04X) peer=%s: 拉回 %d / 失败 %d / 警告 %d",
                                status, peerAet,
                                sink.size(), failedAtTerminal, warningAtTerminal),
                        peerAet, null, null);
            }
            if (status == Status.OneOrMoreFailures
                    || status == Status.PendingWarning) {
                // PS3.4 §C.4.1.1.1.6 — Warning 与 sub-op failure 同语义；不让「0 个 instance / 0 个失败」伪装成 Success
                builder.instances(sink.getInstances())
                        .sopInstanceUids(sink.getSopInstanceUids())
                        .completedSubOps(completedAtTerminal)
                        .remainingSubOps(remainingAtTerminal)
                        .failedSubOps(failedAtTerminal)
                        .warningSubOps(warningAtTerminal)
                        .terminalSuccess(false)
                        .terminalStatusHex(status)
                        .durationMs(durationMs);
                int failureGap = Math.max(0,
                        (seenSubOpCounts
                                ? (remainingAtTerminal + failedAtTerminal + warningAtTerminal)
                                : 0));
                throw failure(FailureCode.PARTIAL_FAILURE,
                        String.format("C-MOVE 部分失败 status=0x%04X peer=%s: 拉回 %d 失败 %d 警告 %d 剩余 %d",
                                status, peerAet, sink.size(), failedAtTerminal, warningAtTerminal,
                                failureGap),
                        peerAet, null, null);
            }
            if (status == Status.MoveDestinationUnknown) {
                throw failure(FailureCode.RETRIEVE_FAILED,
                        "MoveDestination 未被外院 PACS 识别: moveDest=" + routingProps.getLocalMoveDestAet(),
                        peerAet, null, null);
            }
            if (status == Status.UnableToPerformSubOperations
                    || status == Status.UnableToProcess) {
                throw failure(FailureCode.SUB_OP_FAILED,
                        String.format("C-MOVE sub-op 失败 status=0x%04X", status),
                        peerAet, null, null);
            }
            // 其他硬失败（ProcessingFailure / Refused 等）
            throw failure(FailureCode.RETRIEVE_FAILED,
                    String.format("C-MOVE 终态失败 status=0x%04X", status),
                    peerAet, null, null);
        }
    }

    /**
     * 回传一帧进度 —— <b>绝不冒泡</b>。
     *
     * <p>进度回传是旁证：监听方（默认落审计）短暂失败不能把一次已成功的拉回
     * 判成 {@code MOVE_TIMEOUT}，否则运维会追一个根本不存在的网络问题。
     * 这里同时吞 {@link RuntimeException} 与 {@link Error}，保证 C-MOVE 主流程
     * 的返回值与状态推进只由协议本身决定。
     */
    private void emitProgress(RetrieveProgress progress) {
        RetrieveProgressListener listener = progressListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onProgress(progress);
        } catch (RuntimeException | Error e) {
            LOG.warn("C-MOVE 进度监听器异常（已吞掉，不影响拉回结果）: {}",
                    e.getMessage());
        }
    }

    /** 把 C-MOVE 流上的异常归类为 {@code MOVE_TIMEOUT} 或 {@code RETRIEVE_FAILED}。 */
    private RoutingFailureException moveFailureOr(Throwable cause, long startNanos,
                                                 int timeoutMs, String what, String peerAet) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        boolean timedOut = elapsedMs >= timeoutMs
                || cause instanceof SocketTimeoutException
                || (cause != null && cause.getMessage() != null
                    && cause.getMessage().toLowerCase(Locale.ROOT).contains("timeout"));
        String detail = (timedOut ? "C-MOVE 响应超时(" + timeoutMs + "ms)" : "C-MOVE " + what)
                + ": 已收到 " + (cause == null ? -1 : 0) + " 个 instance 耗时 " + elapsedMs + "ms"
                + (cause == null ? "" : " cause=" + cause.getClass().getSimpleName()
                        + ": " + cause.getMessage());
        return failure(timedOut ? FailureCode.MOVE_TIMEOUT : FailureCode.RETRIEVE_FAILED,
                detail, peerAet, null, cause);
    }

    /** dcm4che 5.x 没有 void cmove 重载的 rspTimeout，超时把原因挂在 Association.exception。 */
    private Throwable asExceptionOr(Throwable fallback) {
        return fallback;
    }

    // ==================================================================
    // 关联建立 / 释放
    // ==================================================================

    /**
     * 发起关联。
     *
     * <p>源主路径：{@code ApplicationEntity.connect(conn, rq)} 内部先
     * {@code findCompatibleConnection(remoteConn)}（即
     * {@code Connection.isCompatible()} 的兼容配对），再委派给
     * {@code connect(localConn, remoteConn, rq)} —— 后者走
     * {@code Connection.connect(remoteConn)} 的 bind → connect → TLS 流程。
     * 这里显式用三参重载传入已配对好的 local/remote，让兼容判定结果可控可测。
     */
    Association openAssociation(Connection localConn, Connection remoteConn, AAssociateRQ rq)
            throws IOException, InterruptedException, IncompatibleConnectionException,
            java.security.GeneralSecurityException {
        Device device = buildLocalDevice(localConn, rq.getCallingAET());
        ApplicationEntity ae = device.getApplicationEntity(rq.getCallingAET());
        return ae.connect(localConn, remoteConn, rq);
    }

    /**
     * 构建本端 {@link Device}：一次查询一个 Device。
     *
     * <p>不用全局单例 Device 的理由：{@code Device} 持有开放关联表与关联数上限，
     * 事件驱动的低并发场景下按查询隔离最简单，也避免跨查询的状态串扰；
     * 真正的连接复用留给 task 16 的重试装饰器统一考虑。
     */
    private Device buildLocalDevice(Connection localConn, String localAet) {
        Device device = new Device("cross-hospital-router-scu");
        device.setExecutor(executor);
        device.setScheduledExecutor(scheduler);
        device.addConnection(localConn);
        ApplicationEntity ae = new ApplicationEntity(localAet);
        device.addApplicationEntity(ae);
        return device;
    }

    /** 构建 A-ASSOCIATE-RQ：calling/called AE + 每个 (SOP Class × 传输语法) 一个 presentation context。 */
    AAssociateRQ buildAssociateRq(String peerAet, String localAet,
                                  List<String> sopClasses, List<String> transferSyntaxes) {
        AAssociateRQ rq = new AAssociateRQ();
        rq.setCalledAET(peerAet);
        rq.setCallingAET(localAet);
        rq.setApplicationContext(APPLICATION_CONTEXT_NAME);
        rq.setImplClassUID(routingProps.getImplementationClassUid());
        rq.setProtocolVersion(1);
        rq.setMaxOpsInvoked(0);     // 作为 SCU 不invoke 关联级操作
        rq.setMaxOpsPerformed(1);
        if (routingProps.getMaxPduLength() > 0) {
            rq.setMaxPDULength(routingProps.getMaxPduLength());
        }
        for (String sopClass : sopClasses) {
            for (String ts : transferSyntaxes) {
                rq.addPresentationContextFor(sopClass, ts);
            }
        }
        return rq;
    }

    /** 正常释放：发 A-RELEASE-RQ 并等 A-RELEASE-RP（源 {@code Association.release}）。 */
    void release(Association as, String peerAet) {
        try {
            if (as.isReadyForDataTransfer()) {
                as.release();
            }
        } catch (IOException e) {
            // 释放失败只影响连接整洁度，不影响已取回的结果 —— 记日志不抛
            LOG.warn("A-RELEASE 失败(不影响查询结果): peer={} {}", peerAet, e.getMessage());
        }
    }

    /** 硬关闭：发 A-ABORT 后断链（源 {@code Association.abort}），不再等任何响应。 */
    void abort(Association as, String peerAet) {
        try {
            as.abort();
        } catch (RuntimeException e) {
            LOG.warn("A-ABORT 发送异常: peer={} {}", peerAet, e.getMessage());
        }
    }

    // ==================================================================
    // C-FIND 调用与响应解析
    // ==================================================================

    /**
     * 发 C-FIND-RQ 并逐条消费 C-FIND-RSP。
     *
     * <p>DICOM 语义：每命中一条发一个 {@code Pending}（0xFF00）响应且**带**数据集；
     * 查询正常结束时发终态 {@code Cancel}（0xFE00）且**不带**数据集。
     * 所以「收到 Cancel」= 查询完成，不是失败；「收到 Failure/PendingWarning 终态」才是失败。
     */
    private List<StudyRef> invokeCFind(Association as, String localAet, String patientMpi,
                                       String accessionNumber, String studyUid) {
        String sopClass = resolveNegotiatedQuerySopClass(as);
        String transferSyntax = resolveNegotiatedTransferSyntax(as, sopClass);
        Attributes keys = buildStudyQueryKeys(patientMpi, accessionNumber, studyUid);
        int timeoutMs = routingProps.getQueryTimeoutMs();

        long startNanos = System.nanoTime();
        List<StudyRef> results = new ArrayList<>();
        DimseRSP rsp;
        try {
            rsp = as.cfind(sopClass, 0 /* MEDIUM priority */, keys, transferSyntax, timeoutMs);
        } catch (NoPresentationContextException e) {
            throw failure(FailureCode.ASSOCIATION_REJECTED,
                    "关联建立后未协商到查询 presentation context: sopClass=" + sopClass, localAet, patientMpi, e);
        } catch (SocketTimeoutException e) {
            throw failure(FailureCode.QUERY_TIMEOUT,
                    "C-FIND 响应超时(" + timeoutMs + "ms): peer=" + localAet
                            + " patientMpi=" + patientMpi,
                    localAet, patientMpi, e);
        } catch (AssociationStateException e) {
            throw failure(FailureCode.ASSOCIATION_REJECTED,
                    "关联在查询发起阶段失效: " + e.getMessage(), localAet, patientMpi, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(FailureCode.QUERY_TIMEOUT,
                    "C-FIND 被中断（等待响应超时）: patientMpi=" + patientMpi, localAet, patientMpi, e);
        } catch (IOException e) {
            throw failure(FailureCode.QUERY_FAILED,
                    "C-FIND 发起失败: " + e.getMessage(), localAet, patientMpi, e);
        }

        // 逐条读响应：next() 阻塞至有响应，或到 Connection.responseTimeout 被 dcm4che 主动 abort
        try {
            while (true) {
                boolean hasNext;
                try {
                    hasNext = rsp.next();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw failure(FailureCode.QUERY_TIMEOUT,
                            "C-FIND 被中断（等待响应超时）: patientMpi=" + patientMpi,
                            localAet, patientMpi, e);
                } catch (IOException e) {
                    throw queryFailureOr(e, as, startNanos, timeoutMs, results.size(),
                            "读取 C-FIND 响应失败", localAet, patientMpi);
                }

                if (!hasNext) {
                    // 流结束但没拿到终态。dcm4che 的 DIMSE 超时不抛异常，只让 next() 返回 false
                    // 并把原因挂在 Association.exception 上。
                    throw queryFailureOr(as.getException(), as, startNanos, timeoutMs,
                            results.size(), "未收到终态响应即结束", localAet, patientMpi);
                }

                Attributes cmd = rsp.getCommand();
                int status = cmd.getInt(Tag.Status, -1);

                if (Status.isPending(status)) {
                    Attributes dataset = rsp.getDataset();
                    if (dataset == null) {
                        // Pending 但无数据集 = 协议违规；跳过而不是让 NPE 冒泡
                        LOG.warn("C-FIND Pending 响应缺少数据集，跳过: status={}", status);
                        continue;
                    }
                    StudyRef ref = toStudyRef(dataset, sopClass, transferSyntax);
                    if (ref.hasRetrievableUid()) {
                        results.add(ref);
                    } else {
                        LOG.warn("C-FIND 命中缺 StudyInstanceUID，丢弃该条: patientId={}",
                                ref.getPatientId());
                    }
                    continue;
                }

                // 终态响应（无数据集）
                if (status == Status.Cancel) {
                    LOG.debug("C-FIND 终态 Cancel（查询正常完成）: 命中 {} 条", results.size());
                    return results;
                }
                throw failure(FailureCode.QUERY_FAILED,
                        String.format("C-FIND 终态异常 status=0x%04X: patientMpi=%s",
                                status, patientMpi),
                        localAet, patientMpi, null);
            }
        } finally {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            LOG.debug("C-FIND 耗时 {}ms（预算 {}ms）", elapsedMs, timeoutMs);
        }
    }

    /**
     * 构建 STUDY 级查询键。
     *
     * <p>区分两类键（DICOM PS3.4 C.2.2.1）：
     * <ul>
     *   <li><b>匹配键</b> — 带值，约束服务端查谁：PatientID（必填）、AccessionNumber（有值才过滤）</li>
     *   <li><b>返回键</b> — 零长（{@code setNull}），告诉服务端「这一项要回给我」：
     *       StudyInstanceUID / Modality / StudyDate / StudyID / StudyDescription /
     *       NumberOfStudyRelatedInstances / AccessionNumber / PatientName</li>
     * </ul>
     * 零长度元素的 VR 必须显式给出，否则对端无法解码键对象。
     */
    Attributes buildStudyQueryKeys(String patientMpi, String accessionNumber, String studyUid) {
        Attributes keys = new Attributes();
        // dcm4che 的 SpecificCharacterSet.ASCII 对应 DICOM「默认字符集」，
        // 其 dicomCodes 为空 —— 线值就是 (0008,0005) 的零长串，不是某个 ISO 编码名。
        String[] charsetCodes = SpecificCharacterSet.ASCII.toCodes();
        keys.setString(Tag.SpecificCharacterSet, VR.CS,
                charsetCodes.length == 0 ? "" : String.join("\\", charsetCodes));
        keys.setString(Tag.QueryRetrieveLevel, VR.CS, QUERY_LEVEL_STUDY);

        // ---- 匹配键 ----
        keys.setNull(Tag.PatientName, VR.PN);                 // 不按姓名过滤（跨院姓名写法差异大）
        keys.setString(Tag.PatientID, VR.LO, patientMpi);
        if (accessionNumber != null && !accessionNumber.isBlank()) {
            keys.setString(Tag.AccessionNumber, VR.SH, accessionNumber);
        } else {
            keys.setNull(Tag.AccessionNumber, VR.SH);         // 零长 = 匹配任意，但要回给我
        }
        if (studyUid != null && !studyUid.isBlank()) {
            // 指定了外院片号 → StudyInstanceUID 由返回键升格为匹配键
            keys.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
        } else {
            keys.setNull(Tag.StudyInstanceUID, VR.UI);        // 通配 + 返回
        }

        // ---- 返回键 ----
        keys.setNull(Tag.StudyDate, VR.DA);
        keys.setNull(Tag.StudyID, VR.SH);
        keys.setNull(Tag.StudyDescription, VR.LO);
        keys.setNull(Tag.Modality, VR.CS);
        keys.setNull(Tag.SeriesDescription, VR.LO);
        keys.setNull(Tag.NumberOfStudyRelatedInstances, VR.IS);
        return keys;
    }

    /** 把一条 Pending 响应数据集投影为 {@link StudyRef}；缺失字段一律留 null，不臆造。 */
    StudyRef toStudyRef(Attributes dataset, String querySopClass, String transferSyntax) {
        String studyUid = dataset.getString(Tag.StudyInstanceUID);
        String patientId = dataset.getString(Tag.PatientID);
        if (patientId == null || patientId.isBlank()) {
            // 部分 PACS 只回 IssuerOfPatientID 主索引；退回 StudyInstanceUID 主锚
            patientId = dataset.contains(Tag.PatientID) ? dataset.getString(Tag.PatientID) : null;
        }
        return new StudyRef(
                studyUid,
                querySopClass,
                transferSyntax,
                patientId,
                dataset.getString(Tag.PatientName),
                dataset.getString(Tag.AccessionNumber),
                dataset.getString(Tag.Modality),
                dataset.getString(Tag.StudyDate),
                parseIS(dataset.getString(Tag.NumberOfStudyRelatedInstances)));
    }

    /** IS 值解析；空/非法返回 {@code null}（外院没报 instance 数不等于 0）。 */
    private static Integer parseIS(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            LOG.warn("NumberOfStudyRelatedInstances 非数字，置 null: {}", raw);
            return null;
        }
    }

    // ==================================================================
    // 协商结果读取
    // ==================================================================

    /**
     * 把「读响应阶段」的异常归类成 {@code QUERY_TIMEOUT} 或 {@code QUERY_FAILED}。
     *
     * <p>dcm4che 的 DIMSE 超时不是以 {@code TimeoutException} 抛出的：到
     * {@code Connection.responseTimeout} 后它主动发 A-ABORT，随后 {@code rsp.next()}
     * 以 {@link IOException}（关联已关闭）或 {@code false} 收场。因此**耗时是否已到预算**
     * 才是判定「超时 vs 链路故障」最可靠的信号 —— 这也是唯一不会把真实故障误判成
     * 超时的判据（真故障会在预算内迅速抛出，耗时远小于 timeoutMs）。
     */
    private RoutingFailureException queryFailureOr(Throwable cause, Association as,
                                                   long startNanos, int timeoutMs,
                                                   int consumedSoFar, String what,
                                                   String peerAet, String patientMpi) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        boolean timedOut = elapsedMs >= timeoutMs
                || cause instanceof SocketTimeoutException
                || (cause != null && cause.getMessage() != null
                    && cause.getMessage().toLowerCase(Locale.ROOT).contains("timeout"));
        String detail = (timedOut
                ? "C-FIND 响应超时(" + timeoutMs + "ms)"
                : "C-FIND " + what)
                + ": patientMpi=" + patientMpi
                + " 已读 " + consumedSoFar + " 条 耗时 " + elapsedMs + "ms"
                + (cause == null ? "" : " cause=" + cause.getClass().getSimpleName()
                        + ": " + cause.getMessage());
        return failure(timedOut ? FailureCode.QUERY_TIMEOUT : FailureCode.QUERY_FAILED,
                detail, peerAet, patientMpi, cause);
    }

    /** 取关联里实际被接受的查询 SOP Class（按偏好顺序取第一个被接受的）。 */
    private String resolveNegotiatedQuerySopClass(Association as) {
        for (String sopClass : routingProps.getQueryRetrieveSopClasses()) {
            try {
                PresentationContext pc = as.pcFor(sopClass, null);
                if (pc != null && pc.isAccepted()) {
                    return sopClass;
                }
            } catch (NoPresentationContextException ignored) {
                // 继续试下一个偏好模型
            }
        }
        throw failure(FailureCode.ASSOCIATION_REJECTED,
                "关联内没有任何被接受的查询 SOP Class: 已协商=" + describeAccepted(as),
                as.getRemoteAET(), null, null);
    }

    /** 取该查询 SOP Class 上实际协商成功的传输语法。 */
    private String resolveNegotiatedTransferSyntax(Association as, String sopClass) {
        try {
            PresentationContext pc = as.pcFor(sopClass, null);
            if (pc != null && pc.isAccepted() && pc.getTransferSyntax() != null) {
                return pc.getTransferSyntax();
            }
        } catch (NoPresentationContextException ignored) {
            // 落到下方默认
        }
        return routingProps.getTransferSyntaxes().isEmpty()
                ? UID.ExplicitVRLittleEndian
                : routingProps.getTransferSyntaxes().get(0);
    }

    /** 诊断用：把关联里被接受的 (SOP Class, 传输语法) 列出来，写进失败消息。 */
    private static String describeAccepted(Association as) {
        List<String> accepted = new ArrayList<>();
        if (as.getAAssociateAC() != null) {
            for (PresentationContext pc : as.getAAssociateAC().getPresentationContexts()) {
                if (pc.isAccepted()) {
                    accepted.add(pc.getAbstractSyntax() + "/" + pc.getTransferSyntax());
                }
            }
        }
        return accepted.isEmpty() ? "(空)" : String.join(", ", accepted);
    }

    /**
     * 在本端配置的查询 SOP Class 里，挑与对端准入表声明的 present_contexts 有交集者。
     *
     * <p>外院 {@code present_contexts} 通常登记的是**存储** SOP Class（CT/MR Image Storage…），
     * 不一定含 Query/Retrieve 模型；交集为空时不做任何过滤，按本端偏好全量协商 ——
     * 让 A-ASSOCIATE-RQ / A-ASSOCIATE-AC 的真实协商结果说话，而不是本地猜。
     */
    private List<String> selectQueryRetrieveSopClasses(List<String> peerSopClasses) {
        List<String> preferred = routingProps.getQueryRetrieveSopClasses();
        if (peerSopClasses == null || peerSopClasses.isEmpty()) {
            return preferred;
        }
        List<String> overlap = new ArrayList<>();
        for (String sop : preferred) {
            if (peerSopClasses.contains(sop)) {
                overlap.add(sop);
            }
        }
        return overlap.isEmpty() ? preferred : overlap;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private Peer requirePeer(String peerAet) {
        if (peerAet == null || peerAet.isBlank()) {
            throw new IllegalArgumentException("peer_aet 不能为空");
        }
        // C-FIND / C-MOVE 都从这里取节点，等价于一次对外的建联尝试
        countHandshake();
        Optional<Peer> peer = peerRepository.findByPeerAet(peerAet);
        if (peer.isEmpty()) {
            throw new HandshakeException(HandshakeException.FailureCode.PEER_NOT_FOUND,
                    "Peer 不存在: " + peerAet);
        }
        return peer.get();
    }

    private Connection buildLocalConnection(int responseTimeoutMs) {
        Connection local = new Connection();
        local.setHostname(dicomProps.getLocalHost());
        local.setPort(-1);                      // 仅作客户端，不监听
        local.setProtocol(Connection.Protocol.DICOM);
        local.setConnectTimeout(dicomProps.getRemoteConnectTimeoutMs());
        local.setMaxOpsInvoked(dicomProps.getMaxOpsInvoked());
        local.setMaxOpsPerformed(dicomProps.getMaxOpsPerformed());
        // 关键：dcm4che 的 DIMSE 响应超时来自 Connection.getResponseTimeout()，
        // 而不是 Association.cfind(..., rspTimeout) —— 5 参 cfind 的末位 int 是
        // maxNumberOfResults（内部传 Integer.MAX_VALUE），真正的 rspTimeout 取自本字段。
        // 不设置它，对端不应答时 DimseRSP.next() 会无限期阻塞。
        local.setResponseTimeout(responseTimeoutMs);
        return local;
    }

    private Connection buildRemoteConnection(Peer peer) {
        Connection remote = new Connection();
        remote.setHostname(peer.getHost());
        remote.setPort(peer.getPort() == null ? 11112 : peer.getPort());
        remote.setProtocol(Connection.Protocol.DICOM);
        remote.setConnectTimeout(dicomProps.getRemoteConnectTimeoutMs());
        if ("start_tls".equalsIgnoreCase(peer.getTlsMode())) {
            remote.setTlsCipherSuites(dicomProps.getTlsCipherSuites().toArray(new String[0]));
            remote.setTlsProtocols(dicomProps.getTlsProtocols().toArray(new String[0]));
        }
        return remote;
    }

    /** 解析准入表 JSONB 数组列（复用 task 7 的 DeviceFactory 解析器）。 */
    private static List<String> splitPeerList(String json) {
        return com.crosshospital.imaging.service.dicom.DeviceFactory.parseJsonStringArray(json);
    }

    /** 列表交集（保序、去重）。 */
    static List<String> intersect(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        if (a == null || b == null) {
            return out;
        }
        for (String s : a) {
            if (b.contains(s) && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static RoutingFailureException failure(FailureCode code, String message,
                                                  String peerAet, String patientMpi, Throwable cause) {
        return new RoutingFailureException(code, message, peerAet, patientMpi, cause);
    }

    /** 无 cause 的简化构造。 */
    private static RoutingFailureException failure(FailureCode code, String message,
                                                  String peerAet, String patientMpi) {
        return new RoutingFailureException(code, message, peerAet, patientMpi);
    }
}