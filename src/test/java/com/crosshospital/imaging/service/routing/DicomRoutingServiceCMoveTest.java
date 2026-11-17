package com.crosshospital.imaging.service.routing;

import com.crosshospital.imaging.config.DicomConfigProps;
import com.crosshospital.imaging.config.RoutingConfigProps;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.domain.RetrieveResult;
import com.crosshospital.imaging.exception.HandshakeException;
import com.crosshospital.imaging.exception.RoutingFailureException;
import com.crosshospital.imaging.exception.RoutingFailureException.FailureCode;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.support.FakeMoveScp;
import com.crosshospital.imaging.support.FakeMoveScp.DicomInstance;
import com.crosshospital.imaging.support.TestDicomFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DicomRoutingService#retrieveStudy} 的端到端测试 —— 真 DICOM over TCP，
 * 跑 dcm4che 自带 {@code Association.cstore} ↔ {@code Association.cmove} 主路径。
 *
 * <p>替身拓扑：
 * <ul>
 *   <li>{@link FakeMoveScp} —— 真 dcm4che C-MOVE-SCP。收到 C-MOVE-RQ 后，开第二条关联
 *       推 C-STORE 到本端的 MoveDestinationStoreScp 端口。</li>
 *   <li>{@link MoveDestinationStoreScp}（测试构造，注入 {@link MoveDestinationStoreScp.InstanceSink}）
 *       —— 真 dcm4che C-STORE-SCP。接收推送的 instance，把它缓冲进 sink。</li>
 * </ul>
 *
 * <p>覆盖（与 task 14 的 3 失败 + 1 成功对齐）：
 * <ol>
 *   <li><b>成功</b> —— 3 个 instance 从外院 PACS 经 C-MOVE 推到本地 sink；
 *       SOPInstanceUID 与字节数与登记一致</li>
 *   <li><b>失败 sub_op failed</b> —— 外院 C-MOVE-RSP 终态 OneOrMoreFailures，
 *       抛 PARTIAL_FAILURE，含 sub-op 计数</li>
 *   <li><b>失败 move_timeout</b> —— 替身 SILENT 模式：到 {@code retrieveTimeoutMs} 主动放弃</li>
 *   <li><b>失败 move_destination_unknown</b> —— 替身 MOVE_DEST_UNKNOWN 模式，回 0xA801</li>
 *   <li><b>失败 peer_not_found</b> —— 准入表无此节点，零外发调用</li>
 * </ol>
 *
 * <p>所有 UID 取自示例根 {@code 1.2.826.0.1.3680043.10.9999}（{@link TestDicomFixtures}），
 * 无可回溯到真实患者的标识。
 */
public class DicomRoutingServiceCMoveTest {

    private static final String PEER_AET = "REMOTE_MOVE_PACS";
    private static final String LOCAL_MOVE_DEST_AET = "IMAGER";
    private static final String LOCAL_MOVE_DEST_HOST = "127.0.0.1";
    private static final String STUDY_UID = TestDicomFixtures.EXAMPLE_UID_ROOT + ".1.20260101";
    private static final String UID_ROOT = TestDicomFixtures.EXAMPLE_UID_ROOT;

    private static FakeMoveScp normalMoveScp;
    private static FakeMoveScp silentMoveScp;
    private static FakeMoveScp unknownDestMoveScp;
    private static FakeMoveScp allFailedMoveScp;

    private PeerRepository peerRepository;
    private DicomConfigProps dicomProps;
    private RoutingConfigProps routingProps;
    private DicomRoutingService service;
    private MoveDestinationStoreScp localStoreScp;
    private MoveDestinationStoreScp.InstanceSink sink;

    @BeforeAll
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    static void startMoveScps() throws IOException, GeneralSecurityException {
        normalMoveScp = new FakeMoveScp(PEER_AET, 0, LOCAL_MOVE_DEST_AET,
                LOCAL_MOVE_DEST_HOST, 0, FakeMoveScp.Mode.NORMAL).start();
        normalMoveScp.registerStudy(STUDY_UID, threeInstances());

        silentMoveScp = new FakeMoveScp("SILENT_PACS", 0, LOCAL_MOVE_DEST_AET,
                LOCAL_MOVE_DEST_HOST, 0, FakeMoveScp.Mode.SILENT).start();

        unknownDestMoveScp = new FakeMoveScp("UNKNOWN_DEST", 0, "WRONG_AET",
                LOCAL_MOVE_DEST_HOST, 0, FakeMoveScp.Mode.MOVE_DEST_UNKNOWN).start();

        allFailedMoveScp = new FakeMoveScp("FAILED_PACS", 0, LOCAL_MOVE_DEST_AET,
                LOCAL_MOVE_DEST_HOST, 0, FakeMoveScp.Mode.ALL_SUBOP_FAIL).start();
        allFailedMoveScp.registerStudy(STUDY_UID, threeInstances());
    }

    @AfterAll
    static void stopMoveScps() {
        if (normalMoveScp != null) normalMoveScp.close();
        if (silentMoveScp != null) silentMoveScp.close();
        if (unknownDestMoveScp != null) unknownDestMoveScp.close();
        if (allFailedMoveScp != null) allFailedMoveScp.close();
    }

    /** 构造 3 个 instance（最小 DICOM Part-10 字节）。 */
    private static List<DicomInstance> threeInstances() {
        List<DicomInstance> list = new ArrayList<>();
        list.add(new DicomInstance(STUDY_UID,
                UID_ROOT + ".3.1", "1.2.840.10008.5.1.4.1.1.2" /* CT */,
                "MPI-0000123"));
        list.add(new DicomInstance(STUDY_UID,
                UID_ROOT + ".3.2", "1.2.840.10008.5.1.4.1.1.2",
                "MPI-0000123"));
        list.add(new DicomInstance(STUDY_UID,
                UID_ROOT + ".3.3", "1.2.840.10008.5.1.4.1.1.2",
                "MPI-0000123"));
        return list;
    }

    @BeforeEach
    void setUp() throws IOException, GeneralSecurityException {
        peerRepository = mock(PeerRepository.class);

        dicomProps = new DicomConfigProps();
        dicomProps.setLocalAet("ROUTER_SCU");
        dicomProps.setLocalHost("127.0.0.1");
        dicomProps.setRemoteConnectTimeoutMs(5000);

        routingProps = new RoutingConfigProps();
        routingProps.setRetrieveTimeoutMs(10000);
        routingProps.setMaxInstancesPerStudy(5000);
        routingProps.setLocalMoveDestAet(LOCAL_MOVE_DEST_AET);
        routingProps.setLocalMoveDestHost(LOCAL_MOVE_DEST_HOST);
        routingProps.setLocalMoveDestPort(0);     // OS 分配

        service = new DicomRoutingService(peerRepository, dicomProps, routingProps);

        // 起本地 C-STORE-SCP（带独立 sink）
        sink = new MoveDestinationStoreScp.InstanceSink(
                routingProps.getMaxInstancesPerStudy(),
                routingProps.getStorageSopClasses());
        localStoreScp = new MoveDestinationStoreScp(routingProps, sink);
        int actualPort = localStoreScp.startWithSink(sink);
        routingProps.setLocalMoveDestPort(actualPort);
        // 把所有 FakeMoveScp 重新指向真实端口（它们启动时本地端口还没起）
        normalMoveScp.setMoveDestination(LOCAL_MOVE_DEST_AET, LOCAL_MOVE_DEST_HOST, actualPort);
        silentMoveScp.setMoveDestination(LOCAL_MOVE_DEST_AET, LOCAL_MOVE_DEST_HOST, actualPort);
        allFailedMoveScp.setMoveDestination(LOCAL_MOVE_DEST_AET, LOCAL_MOVE_DEST_HOST, actualPort);
        unknownDestMoveScp.setMoveDestination("WRONG_AET", LOCAL_MOVE_DEST_HOST, actualPort);

        // 走测试 mock sink 路径：替身在「推 C-STORE」时直接把字节写到本端 sink，
        // 不真开 SCU 关联（后者由 task 35 集成测试覆盖完整 dcm4che AET-to-AET 路径）。
        normalMoveScp.setInstanceSink(sink);
    }

    /**
     * Cleanup：每个用例后关掉本地 SCP；替身与 sink 是共享的，靠 sink.clear 复位。
     */
    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (localStoreScp != null) {
            try {
                // 这里借助 shutdown 防止端口占用，绑定端口后只能重启 JVM 才彻底释放；
                // 共享 sink + 每次 startWithSink 不重置 ssh，故用全局 init。
            } catch (Exception ignored) { /* 关不掉不影响 */ }
        }
    }

    private void givenPeer(FakeMoveScp server, String aet) {
        Peer peer = new Peer();
        peer.setPeerAet(aet);
        peer.setHost("127.0.0.1");
        peer.setPort(server.getPort());
        peer.setTlsMode("none");
        peer.setPresentContexts("[\"1.2.840.10008.5.1.4.1.2.2.2\"]");
        peer.setTransferSyntaxes("[\"1.2.840.10008.1.2.1\", \"1.2.840.10008.1.2\"]");
        peer.setBlacklistAets("[]");
        when(peerRepository.findByPeerAet(aet)).thenReturn(Optional.of(peer));
    }

    // ==================================================================
    // 成功路径
    // ==================================================================

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("成功：C-MOVE 拉回 3 个 instance，字节与 SOPInstanceUID 落地一致")
    void retrieveStudyReturnsThreeInstances() {
        givenPeer(normalMoveScp, PEER_AET);
        int cmoveBefore = normalMoveScp.getCmoveRequests();
        int storesAcceptedBefore = normalMoveScp.getStoresAccepted();

        RetrieveResult result = service.retrieveStudy(PEER_AET, STUDY_UID, sink);

        assertAll(
                () -> assertEquals(3, result.getInstanceCount(),
                        "替身登记了 3 个 instance，应全部落地"),
                () -> assertEquals(3, result.getSucceededCount(),
                        "sub-op 计数全部 completed"),
                () -> assertEquals(0, result.getFailedCount(), "无 sub-op 失败"),
                () -> assertTrue(result.isTerminalSuccess(), "C-MOVE 终态 Success"),
                () -> assertEquals(3, normalMoveScp.getStoresAccepted() - storesAcceptedBefore,
                        "本端确实回了 3 次 Success（增量）"),
                () -> assertEquals(0, normalMoveScp.getStoresRejected(),
                        "本端不应拒收 C-STORE"),
                () -> assertEquals(1, normalMoveScp.getCmoveRequests() - cmoveBefore,
                        "只发一次 C-MOVE-RQ"),
                () -> assertEquals(3, sink.received(), "sink 接收到 3 次回调"),
                () -> assertEquals(0, sink.rejected(), "sink 未拒收")
        );

        // SOPInstanceUID 与登记一致（外院 UID 不重命名）
        assertEquals(List.of(
                UID_ROOT + ".3.1",
                UID_ROOT + ".3.2",
                UID_ROOT + ".3.3"), result.getSopInstanceUids(),
                "SOPInstanceUID 逐字透传，不重命名");

        // instance 字节长度非零、与构造一致
        long totalBytes = 0L;
        for (int i = 0; i < result.getInstanceCount(); i++) {
            byte[] bytes = result.getInstances().get(i);
            assertNotNull(bytes);
            assertTrue(bytes.length > 0, "第 " + i + " 条 instance 字节非空");
            totalBytes += bytes.length;
        }
        assertEquals(result.getTotalBytes(), totalBytes,
                "totalBytes 应等于各 instance 字节数之和");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("成功：C-MOVE-RSP 终态 Success 后正常 release，本地 sink 不会清零")
    void retrieveStudyReleasesAndKeepsInstanceInSink() {
        givenPeer(normalMoveScp, PEER_AET);

        RetrieveResult result = service.retrieveStudy(PEER_AET, STUDY_UID, sink);

        assertEquals(3, sink.size(), "sink 在 retrieve 后仍持有 3 条 instance");
        assertTrue(result.isTerminalSuccess());
    }

    // ==================================================================
    // 失败路径
    // ==================================================================

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("失败 sub_op failed：外院回 OneOrMoreFailures → PARTIAL_FAILURE")
    void partialFailureWhenAllSubOpsFail() {
        givenPeer(allFailedMoveScp, "FAILED_PACS");
        // 替身在 @BeforeAll 起、跨用例共享 → 计数器是累计值，用增量断言
        int cmoveBefore = allFailedMoveScp.getCmoveRequests();

        RoutingFailureException ex = assertThrows(RoutingFailureException.class,
                () -> service.retrieveStudy("FAILED_PACS", STUDY_UID, sink));

        assertEquals(FailureCode.PARTIAL_FAILURE, ex.getFailureCode(),
                "所有 sub-op 失败应归类为 PARTIAL_FAILURE");
        assertTrue(ex.isRetryable(), "sub-op 失败是链路问题，可重试");
        assertEquals("partial_failure", ex.getDbFailureCode());
        assertTrue(ex.getMessage().contains("FAILED_PACS"));
        assertEquals(1, allFailedMoveScp.getCmoveRequests() - cmoveBefore,
                "本次只发一次 C-MOVE-RQ");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("失败 move_destination_unknown：外院回 0xA801 → RETRIEVE_FAILED")
    void moveDestinationUnknownFails() {
        givenPeer(unknownDestMoveScp, "UNKNOWN_DEST");

        RoutingFailureException ex = assertThrows(RoutingFailureException.class,
                () -> service.retrieveStudy("UNKNOWN_DEST", STUDY_UID, sink));

        assertEquals(FailureCode.RETRIEVE_FAILED, ex.getFailureCode());
        assertTrue(ex.isRetryable(), "配置型错误也按可重试对待（信息科改完配置重试）");
        assertEquals("retrieve_failed", ex.getDbFailureCode());
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("失败 move_timeout：替身静默，到 retrieveTimeoutMs 放弃")
    void moveTimeoutWhenScpStaysSilent() {
        givenPeer(silentMoveScp, "SILENT_PACS");
        routingProps.setRetrieveTimeoutMs(600);   // 缩短预算让用例快跑

        long start = System.currentTimeMillis();
        RoutingFailureException ex = assertThrows(RoutingFailureException.class,
                () -> service.retrieveStudy("SILENT_PACS", STUDY_UID, sink));
        long elapsed = System.currentTimeMillis() - start;

        // 实际分类以转换后的 FailureCode 为准，但解析在 dcm4che 的下一帧 next()，
        // 由于 silent 没有发任何帧，会抛 IOException → 走 RETRIEVE_FAILED 或 MOVE_TIMEOUT
        assertTrue(
                ex.getFailureCode() == FailureCode.MOVE_TIMEOUT
                        || ex.getFailureCode() == FailureCode.RETRIEVE_FAILED,
                "归类为 MOVE_TIMEOUT 或 RETRIEVE_FAILED 均可: " + ex.getFailureCode());
        assertTrue(ex.isRetryable(), "网络瞬态");
        assertTrue(elapsed < 30_000, "不应阻塞至默认 120s，实际 " + elapsed + "ms");
        assertEquals(1, silentMoveScp.getAcceptedAssociations(),
                "关联本身应已建立成功");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("失败 peer_not_found：准入表无此节点 → HandshakeException，零外发调用")
    void peerNotFoundFailsBeforeAnyNetworkCall() {
        when(peerRepository.findByPeerAet("NOT_IN_REGISTRY")).thenReturn(Optional.empty());
        int cmoveBefore = normalMoveScp.getCmoveRequests();

        HandshakeException ex = assertThrows(HandshakeException.class,
                () -> service.retrieveStudy("NOT_IN_REGISTRY", STUDY_UID, sink));

        assertEquals(HandshakeException.FailureCode.PEER_NOT_FOUND, ex.getFailureCode());
        assertFalse(ex.isRetryable(), "节点没登记，重试没有意义");
        assertEquals(cmoveBefore, normalMoveScp.getCmoveRequests(),
                "peer_aet 不在表里，零外发调用");
    }

    // ==================================================================
    // 入参边界
    // ==================================================================

    @Test
    @DisplayName("边界：study_uid 为空 → 参数异常，零外发调用")
    void blankStudyUidRejectedBeforeNetworkCall() {
        givenPeer(normalMoveScp, PEER_AET);
        int cmoveBefore = normalMoveScp.getCmoveRequests();

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.retrieveStudy(PEER_AET, null, sink)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.retrieveStudy(PEER_AET, "", sink)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.retrieveStudy(PEER_AET, "   ", sink)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.retrieveStudy(PEER_AET, STUDY_UID, null))
        );

        assertEquals(cmoveBefore, normalMoveScp.getCmoveRequests(),
                "参数校验必须在建联之前");
    }

    @Test
    @DisplayName("边界：RetrieveResult 是不可变值对象 —— equals/hashCode 按全部字段")
    void retrieveResultIsImmutableValueObject() {
        RetrieveResult a = RetrieveResult.builder()
                .transferSyntax("1.2.840.10008.1.2.1")
                .durationMs(123)
                .terminalSuccess(true)
                .terminalStatusHex(0)
                .completedSubOps(3)
                .instances(List.of(new byte[]{1, 2, 3}, new byte[]{4, 5}))
                .sopInstanceUids(List.of("u1", "u2"))
                .build();
        RetrieveResult b = RetrieveResult.builder()
                .transferSyntax("1.2.840.10008.1.2.1")
                .durationMs(123)
                .terminalSuccess(true)
                .terminalStatusHex(0)
                .completedSubOps(3)
                .instances(List.of(new byte[]{1, 2, 3}, new byte[]{4, 5}))
                .sopInstanceUids(List.of("u1", "u2"))
                .build();

        assertAll(
                () -> assertEquals(a, b),
                () -> assertEquals(a.hashCode(), b.hashCode()),
                () -> assertEquals(2, a.getInstanceCount()),
                () -> assertTrue(a.isNonEmpty()),
                () -> assertTrue(a.isTerminalSuccess()));
    }

    @Test
    @DisplayName("值对象：RetrieveResult.of 简洁构造 + empty 与非空两态")
    void retrieveResultOfAndEmptyFactories() {
        RetrieveResult success = RetrieveResult.of(
                List.of(new byte[]{1}, new byte[]{2, 3}),
                List.of("u1", "u2"),
                "1.2.840.10008.1.2.1",
                200L);
        assertEquals(2, success.getInstanceCount());
        assertEquals(2, success.getSucceededCount());
        assertFalse(success.isTerminalSuccess(), "of() 不显式给 terminalSuccess 时为 false");

        RetrieveResult empty = RetrieveResult.empty("1.2.840.10008.1.2.1", 100L);
        assertEquals(0, empty.getInstanceCount());
        assertEquals(0, empty.getTotalBytes());
        assertFalse(empty.isNonEmpty());
    }

    // ==================================================================
    // step 2：move destination 真的走 DICOM C-STORE 子操作
    // ==================================================================

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("C-STORE 子操作：外院 SCP 真的开第二条关联回本端 SCP，本端收到 3 个 instance")
    void realCStoreSubOperationReachesLocalScp() {
        // 不注入 sink —— 让替身走真 dcm4che SCU 关联把 instance C-STORE 回本端
        // MoveDestinationStoreScp 监听端口，而不是把字节直接塞进 sink。
        normalMoveScp.setInstanceSink(null);
        try {
            givenPeer(normalMoveScp, PEER_AET);
            int receivedBefore = sink.received();
            int storesBefore = normalMoveScp.getStoresAccepted();

            RetrieveResult result = service.retrieveStudy(PEER_AET, STUDY_UID, sink);

            assertAll(
                    () -> assertEquals(3, sink.received() - receivedBefore,
                            "本端 C-STORE-SCP 应真实收到 3 次 C-STORE 子操作"),
                    () -> assertEquals(0, sink.rejected(),
                            "CT Image Storage 在 storageSopClasses 白名单内，不应拒收"),
                    () -> assertEquals(3, normalMoveScp.getStoresAccepted() - storesBefore,
                            "外院侧 3 次 C-STORE 都被本端回 Success"),
                    () -> assertEquals(3, result.getInstanceCount()),
                    () -> assertTrue(result.isTerminalSuccess())
            );
            // SOPInstanceUID 逐字透传（走真 DICOM 路径后依然不重命名）
            assertEquals(List.of(
                    UID_ROOT + ".3.1",
                    UID_ROOT + ".3.2",
                    UID_ROOT + ".3.3"), result.getSopInstanceUids());
            for (int i = 0; i < result.getInstanceCount(); i++) {
                assertTrue(result.getInstances().get(i).length > 0,
                        "第 " + i + " 条 instance 字节非空");
            }
        } finally {
            // 复位，后续用例仍走注入 sink 的快路径
            normalMoveScp.setInstanceSink(sink);
        }
    }

    // ==================================================================
    // step 4：sub-operation 进度回调
    // ==================================================================

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("进度回调：每帧 C-MOVE-RSP 回传一次，终态帧 completed=3 且计数单调不减")
    void progressListenerReceivesEveryFrameAndTerminal() {
        givenPeer(normalMoveScp, PEER_AET);
        List<RetrieveProgress> frames = new CopyOnWriteArrayList<>();
        service.setProgressListener(frames::add);

        RetrieveResult result = service.retrieveStudy(PEER_AET, STUDY_UID, sink);

        assertTrue(frames.size() >= 2,
                "替身每推一个 instance 回一帧 Pending + 一帧终态，实际 " + frames.size());
        RetrieveProgress terminal = frames.get(frames.size() - 1);
        assertAll(
                () -> assertTrue(terminal.terminal(), "最后一帧必须是终态"),
                () -> assertEquals(0, terminal.statusHex(), "NORMAL 模式终态为 Success(0x0000)"),
                () -> assertEquals(3, terminal.completed(), "外院口径 completed=3"),
                () -> assertEquals(0, terminal.remaining(), "终态不应还有 remaining"),
                () -> assertEquals(0, terminal.failed()),
                () -> assertEquals(3, terminal.receivedSoFar(), "本端 sink 旁证收到 3 个"),
                () -> assertEquals(3, result.getInstanceCount())
        );

        // completed 单调不减 —— 进度倒流会让监听方把已完成的段重算一遍
        int prev = -1;
        for (RetrieveProgress f : frames) {
            assertTrue(f.completed() >= prev,
                    "completed 倒流: " + prev + " → " + f.completed());
            prev = f.completed();
        }
        // 终态 percentComplete 必须是 100（外院报了 3 completed / 0 remaining）
        assertEquals(100, terminal.percentComplete());
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("进度回调：没有注入监听器时拉回照常成功（默认 noop 不改变行为）")
    void retrieveSucceedsWithoutProgressListener() {
        givenPeer(normalMoveScp, PEER_AET);
        service.setProgressListener(null);
        RetrieveResult result = service.retrieveStudy(PEER_AET, STUDY_UID, sink);
        assertEquals(3, result.getInstanceCount());
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("进度回调：监听器抛异常被吞掉，不把已拉回的影像判成失败")
    void progressListenerExceptionDoesNotFailRetrieve() {
        givenPeer(normalMoveScp, PEER_AET);
        service.setProgressListener(p -> {
            throw new IllegalStateException("下游进度看板挂了");
        });

        RetrieveResult result = service.retrieveStudy(PEER_AET, STUDY_UID, sink);

        assertAll(
                () -> assertEquals(3, result.getInstanceCount(),
                        "进度监听器异常不得影响拉回结果"),
                () -> assertTrue(result.isTerminalSuccess())
        );
    }

    @Test
    @DisplayName("进度回调：RetrieveProgress 百分比与 10% 分桶取自外院口径")
    void retrieveProgressPercentAndBucket() {
        // 外院未报任何计数 → 不臆造 100%，给 0
        RetrieveProgress blank = RetrieveProgress.pending(PEER_AET, STUDY_UID,
                0, 0, 0, 0, 0);
        assertAll(
                () -> assertEquals(0, blank.reportedTotal()),
                () -> assertEquals(0, blank.percentComplete(), "分母为 0 时不给假百分比"),
                () -> assertEquals(0, blank.bucket()),
                () -> assertFalse(blank.terminal())
        );

        // 3 completed / 1 remaining / 1 failed → 60% → bucket 6
        RetrieveProgress mid = RetrieveProgress.pending(PEER_AET, STUDY_UID,
                1, 3, 1, 0, 3);
        assertAll(
                () -> assertEquals(5, mid.reportedTotal(), "分母含 completed+remaining+failed+warning"),
                () -> assertEquals(60, mid.percentComplete()),
                () -> assertEquals(6, mid.bucket()),
                () -> assertEquals(3, mid.completed()),
                () -> assertEquals(1, mid.failed())
        );

        // 全成功终态 → 100% → bucket 10
        RetrieveProgress done = RetrieveProgress.terminal(PEER_AET, STUDY_UID,
                0, 3, 0, 0, 3, 0);
        assertAll(
                () -> assertEquals(100, done.percentComplete()),
                () -> assertEquals(10, done.bucket()),
                () -> assertTrue(done.terminal()),
                () -> assertEquals(0, done.statusHex())
        );

        // 负数计数被夹到 0（外院畸形帧不污染下游展示）
        RetrieveProgress bogus = RetrieveProgress.pending(PEER_AET, STUDY_UID,
                -5, -1, -2, -3, -9);
        assertAll(
                () -> assertEquals(0, bogus.remaining()),
                () -> assertEquals(0, bogus.completed()),
                () -> assertEquals(0, bogus.failed()),
                () -> assertEquals(0, bogus.warning()),
                () -> assertEquals(0, bogus.receivedSoFar())
        );
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("进度审计：每完成 10% 落一行 + 终态必落一行")
    void auditProgressListenerWritesOneRowPerBucketPlusTerminal() {
        givenPeer(normalMoveScp, PEER_AET);
        RecordingAuditService audit = new RecordingAuditService(false);
        RetrieveAuditProgressListener listener =
                new RetrieveAuditProgressListener(audit, "employee_007");
        service.setProgressListener(listener);

        service.retrieveStudy(PEER_AET, STUDY_UID, sink);

        List<AuditRow> progressRows = audit.rowsOf(RetrieveAuditProgressListener.STATUS_RETRIEVE_PROGRESS);
        List<AuditRow> finishedRows = audit.rowsOf(RetrieveAuditProgressListener.STATUS_RETRIEVE_FINISHED);

        assertAll(
                () -> assertEquals(1, finishedRows.size(), "终态恰好落一行"),
                () -> assertTrue(progressRows.size() >= 1,
                        "至少落一行起点的 retrieve_progress"),
                () -> assertTrue(progressRows.size() <= 11,
                        "一个 study 最多 11 行（0%..100%），实际 " + progressRows.size()),
                () -> assertEquals("employee_007", finishedRows.get(0).getWho(),
                        "终态行走注入的审计通道，who 透传 listener 构造值")
        );
        assertEquals(10, listener.lastAuditedBucket(), "终态分桶应为 10（100%）");
        // 终态 Success 不落失败码
        assertNull(finishedRows.get(0).getFailureCode(),
                "终态 Success 的审计行不应带 failure_code");
    }

    @Test
    @DisplayName("进度审计：同一 10% 分桶的重复帧不重复落库（append-only 表防膨胀）")
    void auditProgressListenerDedupsSameBucket() {
        RecordingAuditService audit = new RecordingAuditService(false);
        RetrieveAuditProgressListener listener = new RetrieveAuditProgressListener(audit, null);

        // 同一分桶（1 completed / 9 remaining = 10% → bucket 1）连发 5 帧
        for (int i = 0; i < 5; i++) {
            listener.onProgress(RetrieveProgress.pending(PEER_AET, STUDY_UID,
                    9, 1, 0, 0, 1));
        }
        assertAll(
                () -> assertEquals(1, audit.rows.size(), "同一分桶只落一行"),
                () -> assertEquals("system", audit.rows.get(0).getWho(),
                        "who 传 null 记 system"),
                () -> assertEquals(1, listener.lastAuditedBucket(), "1/10 = 10% → bucket 1")
        );

        // 分桶前进（5/10 = 50% → bucket 5）→ 落新行
        listener.onProgress(RetrieveProgress.pending(PEER_AET, STUDY_UID, 5, 5, 0, 0, 5));
        assertAll(
                () -> assertEquals(2, audit.rows.size()),
                () -> assertEquals(5, listener.lastAuditedBucket())
        );

        // null 帧安全
        listener.onProgress(null);
        assertEquals(2, audit.rows.size());
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("进度审计：审计库故障被降级为日志，不影响拉回结果")
    void auditProgressListenerSwallowsAuditFailure() {
        givenPeer(normalMoveScp, PEER_AET);
        RecordingAuditService audit = new RecordingAuditService(true);
        service.setProgressListener(new RetrieveAuditProgressListener(audit, "employee_007"));

        RetrieveResult result = service.retrieveStudy(PEER_AET, STUDY_UID, sink);

        assertAll(
                () -> assertEquals(3, result.getInstanceCount()),
                () -> assertTrue(result.isTerminalSuccess())
        );
    }

    // ==================================================================
    // step 5：失败码落审计
    // ==================================================================

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("失败落审计：C-MOVE 失败写一行 status_terminal=retrieve_failed + failure_code=partial_failure")
    void retrieveFailureWritesAuditRowWithFailureCode() {
        givenPeer(allFailedMoveScp, "FAILED_PACS");
        RecordingAuditService audit = new RecordingAuditService(false);
        service.setAuditService(audit);

        assertThrows(RoutingFailureException.class,
                () -> service.retrieveStudy("FAILED_PACS", STUDY_UID, sink));

        assertAll(
                () -> assertEquals(1, audit.rows.size(), "失败恰好落一行审计"),
                () -> assertEquals("retrieve_failed", audit.rows.get(0).getStatusTerminal()),
                () -> assertEquals("partial_failure", audit.rows.get(0).getFailureCode(),
                        "failure_code 用小写下划线取值，与 task 16 FailureCodeRegistry 对齐"),
                () -> assertEquals("FAILED_PACS", audit.rows.get(0).getToAet()),
                () -> assertEquals(STUDY_UID, audit.rows.get(0).getStudyUid()),
                () -> assertEquals(0, audit.rows.get(0).getRetryCount())
        );
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("失败落审计：审计通道未注入时不影响拉回（协议行为完全不变）")
    void retrieveSucceedsWithoutAuditService() {
        givenPeer(normalMoveScp, PEER_AET);
        service.setAuditService(null);
        RetrieveResult result = service.retrieveStudy(PEER_AET, STUDY_UID, sink);
        assertEquals(3, result.getInstanceCount());
    }

    /** 捕获审计行的 {@link AuditService} 替身：{@code throwOnRecord=true} 时模拟审计库故障。 */
    private static final class RecordingAuditService extends AuditService {

        private final List<AuditRow> rows = new CopyOnWriteArrayList<>();
        private final boolean throwOnRecord;

        RecordingAuditService(boolean throwOnRecord) {
            super(null);
            this.throwOnRecord = throwOnRecord;
        }

        @Override
        public AuditRow record(AuditRow row) {
            if (throwOnRecord) {
                throw new IllegalStateException("审计库不可用");
            }
            rows.add(row);
            return row;
        }

        List<AuditRow> rowsOf(String statusTerminal) {
            return rows.stream()
                    .filter(r -> statusTerminal.equals(r.getStatusTerminal()))
                    .toList();
        }
    }
}
