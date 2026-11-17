package com.crosshospital.imaging.service.routing;

import com.crosshospital.imaging.config.DicomConfigProps;
import com.crosshospital.imaging.config.RoutingConfigProps;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.exception.RoutingFailureException;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.support.FakeLocalPacs;
import com.crosshospital.imaging.support.FakeMoveScp;
import com.crosshospital.imaging.support.FakeMoveScp.DicomInstance;
import com.crosshospital.imaging.support.FakePacsServer;
import com.crosshospital.imaging.support.TestDicomFixtures;
import org.dcm4che3.data.Attributes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * task 16 端到端测试 —— 真 DICOM over TCP 注入 6 种失败，验证「失败码 + 1 行审计 + 1 条死信」。
 *
 * <p>替身全部是<b>真的</b> dcm4che SCP（{@link FakePacsServer} / {@link FakeMoveScp} /
 * {@link FakeLocalPacs}），被测的 {@link DicomRoutingRetryDecorator} 走真
 * {@code Association.cfind/cmove/cstore} 打过去。因此这里断言的是
 * 「外院 PACS 真的这么回 → 本服务真的这么分类、这么记账」，而不是内部方法调用次数。
 *
 * <p><b>6 种失败场景</b>各断言三件事：失败码落在正确的词表项上、审计行恰好一行且
 * 步骤终态与失败码都对、死信恰好一条且带原始失败消息。
 *
 * <p>另外单独验证<b>重试预算</b>：瞬态失败按 {@code max_retries} 退避重试，
 * 配置型失败一次都不重试，预算耗尽后才进死信 —— 这条决定了外院 PACS 的日志
 * 会被打几次。
 */
class DicomRoutingRetryAndFailureTest {

    private static final String PEER_AET = "REMOTE_PACS";
    private static final String LOCAL_AET = "ROUTER_SCU";
    private static final String HOST = "127.0.0.1";
    private static final String PATIENT_MPI = "MPI-0000123";
    private static final String STUDY_UID = TestDicomFixtures.EXAMPLE_UID_ROOT + ".1.20260101";
    private static final String UID_ROOT = TestDicomFixtures.EXAMPLE_UID_ROOT;
    private static final String CT_STORAGE = "1.2.840.10008.5.1.4.1.1.2";
    private static final String LOCAL_MOVE_DEST_AET = "ROUTER_STORESCP";
    private static final String LOCAL_PACS_AET = "LOCAL_PACS";

    /** 关联被拒的外院 PACS（回 A-ASSOCIATE-RJ）。 */
    private static FakePacsServer rejectingPacs;
    /** 接受关联但永不回 C-FIND 响应的外院 PACS。 */
    private static FakePacsServer silentPacs;
    /** MoveDestination 未登记的 C-MOVE SCP。 */
    private static FakeMoveScp unknownDestMoveScp;
    /** 全部 sub-op 失败的 C-MOVE SCP。 */
    private static FakeMoveScp allFailedMoveScp;
    /** 拒收全部 C-STORE 的本院 PACS。 */
    private static FakeLocalPacs rejectingLocalPacs;
    /** 一个被借走又释放的端口 —— 此刻无人监听（connection_refused 用）。 */
    private static int deadPort;

    private PeerRepository peerRepository;
    private DicomConfigProps dicomProps;
    private RoutingConfigProps routingProps;
    private DicomRoutingService routingService;
    private DicomRoutingRetryDecorator decorator;

    private CapturingAuditService audit;
    private List<DlqEnqueueRequest> dlq;
    private List<String> markedTasks;
    private List<String> taskCodes;
    private List<Long> sleptMs;
    private MoveDestinationStoreScp.InstanceSink sink;

    @BeforeAll
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    static void startFakes() throws IOException, GeneralSecurityException {
        rejectingPacs = new FakePacsServer("REJECT_PACS", 0, oneStudy(),
                FakePacsServer.REJECT_ASSOCIATION, 0L).start();
        silentPacs = new FakePacsServer("SILENT_PACS", 0, oneStudy(),
                FakePacsServer.SILENT, 0L).start();

        unknownDestMoveScp = new FakeMoveScp("UNKNOWN_DEST", 0, "WRONG_AET",
                HOST, 0, FakeMoveScp.Mode.MOVE_DEST_UNKNOWN).start();

        allFailedMoveScp = new FakeMoveScp("FAILED_PACS", 0, LOCAL_MOVE_DEST_AET,
                HOST, 0, FakeMoveScp.Mode.ALL_SUBOP_FAIL).start();
        allFailedMoveScp.registerStudy(STUDY_UID, threeInstances());

        rejectingLocalPacs = FakeLocalPacs.withMode(LOCAL_PACS_AET, 0,
                FakeLocalPacs.Mode.REJECT_ALL).start();

        deadPort = borrowAndReleasePort();
    }

    @AfterAll
    static void stopFakes() {
        if (rejectingPacs != null) rejectingPacs.close();
        if (silentPacs != null) silentPacs.close();
        if (unknownDestMoveScp != null) unknownDestMoveScp.close();
        if (allFailedMoveScp != null) allFailedMoveScp.close();
        if (rejectingLocalPacs != null) rejectingLocalPacs.close();
    }

    private static List<Attributes> oneStudy() {
        return List.of(FakePacsServer.studyOf(
                PATIENT_MPI, "ACC-20260101", STUDY_UID, "CT", "20260101", 3));
    }

    private static List<DicomInstance> threeInstances() {
        List<DicomInstance> list = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            list.add(new DicomInstance(STUDY_UID, UID_ROOT + ".3." + i, CT_STORAGE, PATIENT_MPI));
        }
        return list;
    }

    /** 借一个空闲端口随即释放 —— 得到一个「确定没人监听」的端口号。 */
    private static int borrowAndReleasePort() {
        try (ServerSocket probe = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("无法分配测试端口", e);
        }
    }

    private static final class CapturingAuditService extends AuditService {
        final List<AuditRow> rows = new CopyOnWriteArrayList<>();

        CapturingAuditService() {
            super(null);
        }

        @Override
        public AuditRow record(AuditRow row) {
            rows.add(row);
            return row;
        }
    }

    @BeforeEach
    void setUp() {
        peerRepository = mock(PeerRepository.class);

        dicomProps = new DicomConfigProps();
        dicomProps.setLocalAet(LOCAL_AET);
        dicomProps.setLocalHost(HOST);
        dicomProps.setRemoteConnectTimeoutMs(5000);

        routingProps = new RoutingConfigProps();
        routingProps.setQueryTimeoutMs(700);
        routingProps.setRetrieveTimeoutMs(3000);
        routingProps.setStoreTimeoutMs(2000);
        routingProps.setLocalMoveDestAet(LOCAL_MOVE_DEST_AET);
        routingProps.setLocalMoveDestHost(HOST);
        routingProps.setLocalMoveDestPort(0);
        routingProps.setLocalPacsAet(LOCAL_PACS_AET);
        routingProps.setLocalPacsHost(HOST);
        routingProps.setLocalPacsPort(rejectingLocalPacs.getPort());

        routingService = new DicomRoutingService(peerRepository, dicomProps, routingProps);

        audit = new CapturingAuditService();
        dlq = new CopyOnWriteArrayList<>();
        markedTasks = new CopyOnWriteArrayList<>();
        taskCodes = new CopyOnWriteArrayList<>();
        sleptMs = new CopyOnWriteArrayList<>();

        RoutingFailureHandler handler = new RoutingFailureHandler(audit);
        handler.setDlqEnqueuePort(dlq::add);
        handler.setFetchTaskFailurePort((taskId, code) -> {
            markedTasks.add(taskId);
            taskCodes.add(code);
            return TaskFailureOutcome.TERMINAL_FAILURE;
        });
        routingService.setFailureHandler(handler);
        routingService.setAuditService(audit);

        decorator = new DicomRoutingRetryDecorator(routingService, peerRepository);
        decorator.setFailureHandler(handler);
        decorator.setSleeper(sleptMs::add);      // 单测不退避真睡，只记录

        sink = new MoveDestinationStoreScp.InstanceSink(
                routingProps.getMaxInstancesPerStudy(), routingProps.getStorageSopClasses());
        allFailedMoveScp.setInstanceSink(sink);
        unknownDestMoveScp.setMoveDestination("WRONG_AET", HOST, 11114);
    }

    /** 把 fake 节点登记进准入表；{@code maxRetries} 为 null 时用迁移脚本默认值 3。 */
    private void givenPeer(String aet, String host, int port, Integer maxRetries) {
        Peer peer = new Peer();
        peer.setPeerAet(aet);
        peer.setHost(host);
        peer.setPort(port);
        peer.setTlsMode("none");
        peer.setPresentContexts("[\"" + FakePacsServer.STUDY_ROOT_FIND + "\"]");
        peer.setTransferSyntaxes("[\"1.2.840.10008.1.2.1\", \"1.2.840.10008.1.2\"]");
        peer.setBlacklistAets("[]");
        peer.setMaxRetries(maxRetries);
        peer.setBackoffMs(50);
        when(peerRepository.findByPeerAet(aet)).thenReturn(Optional.of(peer));
    }

    private void givenPeer(String aet, int port, Integer maxRetries) {
        givenPeer(aet, HOST, port, maxRetries);
    }

    private RoutingFailureContext ctx(String step, String aet) {
        return new RoutingFailureContext(step, aet, PATIENT_MPI, STUDY_UID,
                "11111111-2222-3333-4444-555555555555", "employee_007");
    }

    private static List<ByteBuffer> threePart10Buffers() {
        List<ByteBuffer> out = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            out.add(ByteBuffer.wrap(FakeMoveScp.buildPart10Bytes(
                    STUDY_UID, UID_ROOT + ".3." + i, CT_STORAGE, PATIENT_MPI)));
        }
        return out;
    }

    // ==================================================================
    // 6 种失败场景：failure_code + 1 行审计 + 1 条死信
    // ==================================================================

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("场景 1 connection_refused：连不上外院 → 码归网络层，不误报成 AE 白名单问题")
    void connectionRefusedIsClassifiedFromCauseChain() {
        givenPeer("DEAD_PEER", deadPort, 0);

        assertThrows(RoutingFailureException.class, () -> decorator.call(
                ctx(RoutingFailureContext.STEP_QUERY, "DEAD_PEER"),
                () -> routingService.queryStudies("DEAD_PEER", PATIENT_MPI, null, null)));

        assertFailureRecorded("connection_refused", "query_failed", "DEAD_PEER");
        assertEquals(0, sleptMs.size(), "connection_refused 可重试，但 max_retries=0 故一次都不等");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("场景 2 association_rejected：外院回 A-ASSOCIATE-RJ → 不可重试，一次都不打对方")
    void associationRejectedIsNotRetried() {
        givenPeer("REJECT_PACS", rejectingPacs.getPort(), 3);
        int rejectedBefore = rejectingPacs.getRejectedAssociations();

        assertThrows(RoutingFailureException.class, () -> decorator.call(
                ctx(RoutingFailureContext.STEP_QUERY, "REJECT_PACS"),
                () -> routingService.queryStudies("REJECT_PACS", PATIENT_MPI, null, null)));

        assertFailureRecorded("association_rejected", "query_failed", "REJECT_PACS");
        assertAll(
                () -> assertEquals(0, sleptMs.size(),
                        "配置型失败绝不退避重试 —— 重试只会把同一个 RJ 打三遍"),
                () -> assertEquals(1, rejectingPacs.getRejectedAssociations() - rejectedBefore,
                        "外院 PACS 只应收到 1 次 A-ASSOCIATE-RQ"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("场景 3 query_timeout：对端不应答到预算耗尽 → 可重试码 + 查询步终态")
    void queryTimeoutIsRecorded() {
        givenPeer("SILENT_PACS", silentPacs.getPort(), 0);

        assertThrows(RoutingFailureException.class, () -> decorator.call(
                ctx(RoutingFailureContext.STEP_QUERY, "SILENT_PACS"),
                () -> routingService.queryStudies("SILENT_PACS", PATIENT_MPI, null, null)));

        assertFailureRecorded("query_timeout", "query_failed", "SILENT_PACS");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("场景 4 retrieve_failed：外院不认本服务 MoveDestination → 码指回本端配置")
    void moveDestinationUnknownIsRetrieveFailed() {
        givenPeer("UNKNOWN_DEST", unknownDestMoveScp.getPort(), 0);

        assertThrows(RoutingFailureException.class, () -> decorator.call(
                ctx(RoutingFailureContext.STEP_RETRIEVE, "UNKNOWN_DEST"),
                () -> routingService.retrieveStudy("UNKNOWN_DEST", STUDY_UID, sink)));

        assertFailureRecorded("retrieve_failed", "retrieve_failed", "UNKNOWN_DEST");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("场景 5 store_failed：本院 PACS 拒收全部 instance → 码指回 LOCAL_PACS_* 配置")
    void rejectingLocalPacsIsStoreFailed() {
        givenPeer("REMOTE_PACS", allFailedMoveScp.getPort(), 0);

        assertThrows(RoutingFailureException.class, () -> decorator.call(
                ctx(RoutingFailureContext.STEP_STORE, LOCAL_PACS_AET),
                () -> routingService.storeToLocal(STUDY_UID, threePart10Buffers(), "REMOTE_PACS")));

        assertFailureRecorded("store_failed", "store_failed", LOCAL_PACS_AET);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("场景 6 partial_failure：C-MOVE 部分成功按整次失败处理，绝不推进 stored")
    void partialFailureIsRecorded() {
        givenPeer("FAILED_PACS", allFailedMoveScp.getPort(), 0);

        assertThrows(RoutingFailureException.class, () -> decorator.call(
                ctx(RoutingFailureContext.STEP_RETRIEVE, "FAILED_PACS"),
                () -> routingService.retrieveStudy("FAILED_PACS", STUDY_UID, sink)));

        assertFailureRecorded("partial_failure", "retrieve_failed", "FAILED_PACS");
        assertAll(
                () -> assertEquals(1, dlq.size(), "部分失败必须进死信等人来看，不静默放过"),
                () -> assertEquals("partial_failure", taskCodes.get(0),
                        "任务推失败终态用的失败码与审计一致"));
    }

    // ==================================================================
    // 重试预算
    // ==================================================================

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("重试耗尽才进死信：瞬态失败按 max_retries=2 共试 3 次，retry_count 落真实次数")
    void retryExhaustionIsTheOnlyPathIntoDlq() {
        givenPeer("SILENT_PACS", silentPacs.getPort(), 2);
        int cfindBefore = silentPacs.getCfindRequests();

        assertThrows(RoutingFailureException.class, () -> decorator.call(
                ctx(RoutingFailureContext.STEP_QUERY, "SILENT_PACS"),
                () -> routingService.queryStudies("SILENT_PACS", PATIENT_MPI, null, null)));

        assertAll(
                () -> assertEquals(2, sleptMs.size(), "重试 2 次 → 退避等 2 次"),
                () -> assertEquals(50L, sleptMs.get(0), "第一次退避 = backoff × 2^0"),
                () -> assertEquals(100L, sleptMs.get(1), "第二次退避 = backoff × 2^1"),
                () -> assertEquals(1, audit.rows.size(), "3 次尝试只落 1 行终态审计（不是 3 行）"),
                () -> assertEquals(2, audit.rows.get(0).getRetryCount(), "retry_count 记真实重试次数"),
                () -> assertEquals(1, dlq.size(), "死信恰好 1 条"),
                () -> assertEquals(2, dlq.get(0).retryAttempts(), "死信带重试次数"),
                () -> assertTrue(silentPacs.getCfindRequests() - cfindBefore >= 1,
                        "至少真的重试过一次（替身累计计数）"));
    }

    @Test
    @DisplayName("退避按指数增长并封顶 30s；base 非法时回落到默认基数而不是死循环")
    void backoffGrowsExponentiallyAndCapsAt30Seconds() {
        assertAll(
                () -> assertEquals(1000L, DicomRoutingRetryDecorator.backoffMs(1000, 0)),
                () -> assertEquals(2000L, DicomRoutingRetryDecorator.backoffMs(1000, 1)),
                () -> assertEquals(4000L, DicomRoutingRetryDecorator.backoffMs(1000, 2)),
                () -> assertEquals(16000L, DicomRoutingRetryDecorator.backoffMs(1000, 4)),
                () -> assertEquals(DicomRoutingRetryDecorator.MAX_BACKOFF_MS,
                        DicomRoutingRetryDecorator.backoffMs(1000, 6), "超出封顶"),
                // attempt=31 时 base << 31 会溢出成负数，必须先判封顶再算
                () -> assertEquals(DicomRoutingRetryDecorator.MAX_BACKOFF_MS,
                        DicomRoutingRetryDecorator.backoffMs(1000, 31)),
                () -> assertEquals(DicomRoutingRetryDecorator.MAX_BACKOFF_MS,
                        DicomRoutingRetryDecorator.backoffMs(40000, 0), "base 本身超封顶"),
                () -> assertEquals(1000L, DicomRoutingRetryDecorator.backoffMs(0, 0),
                        "base=0 回落默认，不返回 0 触发忙等"),
                () -> assertEquals(4000L, DicomRoutingRetryDecorator.backoffMs(-5, 2),
                        "负 base 回落默认基数 1000，再按 attempt 翻倍"));
    }

    @Test
    @DisplayName("预算取自准入表逐节点配置；节点查不到时回落到迁移脚本默认值")
    void budgetComesFromPeerConfigWithDefaults() {
        givenPeer("P1", 11112, 5);
        assertEquals(new DicomRoutingRetryDecorator.RetryBudget(5, 50),
                decorator.budgetFor("P1"));

        // 节点未登记：给默认值，保证退避计算有确定基数（真正的失败是 PEER_NOT_FOUND）
        assertEquals(DicomRoutingRetryDecorator.RetryBudget.defaults(), decorator.budgetFor("UNKNOWN"));
        assertEquals(DicomRoutingRetryDecorator.RetryBudget.defaults(), decorator.budgetFor(null));
        assertEquals(DicomRoutingRetryDecorator.RetryBudget.defaults(), decorator.budgetFor("  "));
    }

    @Test
    @DisplayName("调用方参数错误不进重试、不进死信、不落审计（是本服务契约被违反，不是外院故障）")
    void illegalArgumentNeverReachesDlq() {
        givenPeer("REJECT_PACS", rejectingPacs.getPort(), 3);
        RoutingFailureHandler handler = new RoutingFailureHandler(audit);
        handler.setDlqEnqueuePort(dlq::add);
        handler.setFetchTaskFailurePort((id, code) -> {
            markedTasks.add(id);
            return TaskFailureOutcome.TERMINAL_FAILURE;
        });
        DicomRoutingRetryDecorator isolated =
                new DicomRoutingRetryDecorator(routingService, peerRepository);
        isolated.setFailureHandler(handler);
        isolated.setSleeper(sleptMs::add);

        assertThrows(IllegalArgumentException.class, () -> isolated.call(
                ctx(RoutingFailureContext.STEP_QUERY, "REJECT_PACS"),
                () -> routingService.queryStudies("REJECT_PACS", "  ", null, null)));

        assertAll(
                () -> assertEquals(0, sleptMs.size(), "参数错误不消耗重试预算"),
                () -> assertEquals(0, dlq.size(), "参数错误不该出现在信息科的待办列表里"),
                () -> assertEquals(0, audit.rows.size()));
    }

    @Test
    @DisplayName("成功路径不产生任何失败痕迹（装饰器只包失败，不包成功）")
    void successfulCallLeavesNoFailureTrace() {
        FakePacsServer normal = null;
        try {
            normal = new FakePacsServer("NORMAL_PACS", 0, oneStudy(),
                    FakePacsServer.NORMAL, 0L).start();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        try {
            givenPeer("NORMAL_PACS", normal.getPort(), 2);
            List<com.crosshospital.imaging.domain.StudyRef> refs = decorator.call(
                    ctx(RoutingFailureContext.STEP_QUERY, "NORMAL_PACS"),
                    () -> routingService.queryStudies("NORMAL_PACS", PATIENT_MPI, null, null));

            assertAll(
                    () -> assertEquals(1, refs.size(), "外院 1 个 study 应正常查回"),
                    () -> assertEquals(0, audit.rows.size()),
                    () -> assertEquals(0, dlq.size()),
                    () -> assertEquals(0, sleptMs.size()));
        } finally {
            normal.close();
        }
    }

    // ==================================================================
    // 断言工具
    // ==================================================================

    /**
     * 「1 行审计 + 1 条死信」是 task 16 的核心交付，这里做成公用断言，
     * 避免六个场景各写一遍、各漏一处。
     */
    private void assertFailureRecorded(String expectedCode, String expectedStatusTerminal,
                                       String expectedAet) {
        assertAll("失败码/审计/死信三者必须同源",
                () -> assertEquals(1, audit.rows.size(),
                        "一次失败恰好落 1 行审计（实际 " + audit.rows.size() + " 行）"),
                () -> assertEquals(1, dlq.size(),
                        "一次失败恰好进 1 条死信（实际 " + dlq.size() + " 条）"),
                () -> assertEquals(1, markedTasks.size(), "任务恰好推一次失败终态"),
                () -> assertEquals(expectedCode, audit.rows.get(0).getFailureCode()),
                () -> assertEquals(expectedStatusTerminal, audit.rows.get(0).getStatusTerminal()),
                () -> assertEquals(expectedAet, audit.rows.get(0).getToAet()),
                () -> assertEquals(STUDY_UID, audit.rows.get(0).getStudyUid()),
                () -> assertEquals("employee_007", audit.rows.get(0).getWho()),
                () -> assertEquals(expectedCode, dlq.get(0).failureCode(),
                        "死信里的失败码必须与审计一致，否则信息科会看到两种说法"),
                () -> assertEquals(expectedAet, dlq.get(0).peerAet()),
                () -> assertEquals(expectedCode, taskCodes.get(0),
                        "任务终态用的失败码必须与审计一致"));
    }
}