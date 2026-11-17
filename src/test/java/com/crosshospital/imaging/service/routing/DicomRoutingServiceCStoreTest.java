package com.crosshospital.imaging.service.routing;

import com.crosshospital.imaging.config.DicomConfigProps;
import com.crosshospital.imaging.config.RoutingConfigProps;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.domain.RetrieveResult;
import com.crosshospital.imaging.domain.StoreResult;
import com.crosshospital.imaging.domain.StudyRef;
import com.crosshospital.imaging.exception.RoutingFailureException;
import com.crosshospital.imaging.exception.RoutingFailureException.FailureCode;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.support.FakeLocalPacs;
import com.crosshospital.imaging.support.FakeMoveScp;
import com.crosshospital.imaging.support.FakeMoveScp.DicomInstance;
import com.crosshospital.imaging.support.FakePacsServer;
import com.crosshospital.imaging.support.TestDicomFixtures;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DicomRoutingService#storeToLocal} 的端到端测试 —— 真 DICOM over TCP，
 * 跑 dcm4che {@code Association.cstore} ↔ SCP 接收主路径。
 *
 * <p>替身拓扑（三个不同角色，一个都不能少）：
 * <ul>
 *   <li>{@link FakePacsServer} —— 外院 PACS 的 C-FIND 侧（task 13 主路径第一步）</li>
 *   <li>{@link FakeMoveScp} + {@link MoveDestinationStoreScp} —— 外院 C-MOVE 侧，
 *       把 3 个 instance 真的经第二条关联 C-STORE 推回我们的落点（task 14 第二步）</li>
 *   <li>{@link FakeLocalPacs} —— <b>本院 PACS</b>，我们以 C-STORE-SCU 身份写进去
 *       （本任务第三步）；它存下的 dataset 是「影像真进了 PACS」的证据</li>
 * </ul>
 *
 * <p>覆盖：
 * <ol>
 *   <li><b>三步闭环</b> —— query → retrieve → store 一气呵成，本院 PACS 收到 3 个 instance</li>
 *   <li><b>UID 不变量</b>（本步最硬的约束）—— SOPInstanceUID / StudyInstanceUID
 *       逐字透传，不重命名</li>
 *   <li><b>成功审计</b> —— {@code status_terminal=stored} + to_aet/sop_class/transfer_syntax</li>
 *   <li><b>失败 ×4</b> —— 整批拒 / 部分失败 / 静默超时 / 关联被拒</li>
 *   <li><b>边界</b> —— SOP Class 不在白名单（建联前拦下）/ 空列表 / 缺 UID 字段 /
 *       Part-10 与裸 dataset 两种字节形态</li>
 * </ol>
 *
 * <p>所有 UID 取自自造示例根 {@code 1.2.826.0.1.3680043.10.9999}
 * （见 {@link TestDicomFixtures}），无可回溯到真实患者的标识。
 */
public class DicomRoutingServiceCStoreTest {

    private static final String PEER_AET = "REMOTE_PACS";
    private static final String LOCAL_SCU_AET = "ROUTER_SCU";
    private static final String LOCAL_MOVE_DEST_AET = "IMAGER";
    private static final String LOCAL_PACS_AET = "LOCAL_PACS";
    private static final String HOST = "127.0.0.1";
    private static final String PATIENT_MPI = "MPI-0000123";
    /** 静态初始化按声明顺序执行 —— UID_ROOT 必须先于 STUDY_UID 声明。 */
    private static final String UID_ROOT = TestDicomFixtures.EXAMPLE_UID_ROOT;
    private static final String STUDY_UID = UID_ROOT + ".st.1";
    private static final String CT_STORAGE = "1.2.840.10008.5.1.4.1.1.2";
    private static final String MR_STORAGE = "1.2.840.10008.5.1.4.1.1.4";
    /** 不在 storageSopClasses 白名单里的 SOP Class（SR 文档）—— 用于「建联前拦下」。 */
    private static final String SR_STORAGE = "1.2.840.10008.5.1.4.1.1.88.11";

    private static FakePacsServer queryPacs;
    private static FakeMoveScp moveScp;
    private static FakeLocalPacs localPacs;
    private static FakeLocalPacs rejectingPacs;
    private static FakeLocalPacs partialPacs;
    private static FakeLocalPacs silentPacs;
    private static FakeLocalPacs rejectingAssocPacs;

    private PeerRepository peerRepository;
    private DicomConfigProps dicomProps;
    private RoutingConfigProps routingProps;
    private DicomRoutingService service;
    private MoveDestinationStoreScp.InstanceSink moveSink;

    @BeforeAll
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    static void startFakes() throws IOException, GeneralSecurityException {
        // 外院侧：C-FIND（query）+ C-MOVE（retrieve）
        queryPacs = new FakePacsServer(PEER_AET, 0,
                List.of(FakePacsServer.studyOf(PATIENT_MPI, "ACC-20260101",
                        STUDY_UID, "CT", "20260101", 3)),
                FakePacsServer.NORMAL, 0L).start();
        moveScp = new FakeMoveScp(PEER_AET, 0, LOCAL_MOVE_DEST_AET, HOST, 0,
                FakeMoveScp.Mode.NORMAL).start();
        moveScp.registerStudy(STUDY_UID, threeInstances());

        // 本院侧：5 种行为的 C-STORE-SCP 替身
        localPacs = FakeLocalPacs.normal(LOCAL_PACS_AET, 0).start();
        rejectingPacs = FakeLocalPacs.withMode("LOCAL_PACS_REJ", 0,
                FakeLocalPacs.Mode.REJECT_ALL).start();
        partialPacs = new FakeLocalPacs("LOCAL_PACS_PART", 0,
                FakeLocalPacs.Mode.REJECT_AFTER_N, 1, List.of(CT_STORAGE, MR_STORAGE)).start();
        silentPacs = FakeLocalPacs.withMode("LOCAL_PACS_SIL", 0,
                FakeLocalPacs.Mode.SILENT).start();
        rejectingAssocPacs = FakeLocalPacs.withMode("LOCAL_PACS_NAUTH", 0,
                FakeLocalPacs.Mode.REJECT_ASSOCIATION).start();
    }

    @AfterAll
    static void stopFakes() {
        for (FakeLocalPacs p : List.of(localPacs, rejectingPacs, partialPacs,
                silentPacs, rejectingAssocPacs)) {
            if (p != null) p.close();
        }
        if (moveScp != null) moveScp.close();
        if (queryPacs != null) queryPacs.close();
    }

    private static List<DicomInstance> threeInstances() {
        List<DicomInstance> list = new ArrayList<>();
        list.add(new DicomInstance(STUDY_UID, UID_ROOT + ".3.1", CT_STORAGE, PATIENT_MPI));
        list.add(new DicomInstance(STUDY_UID, UID_ROOT + ".3.2", CT_STORAGE, PATIENT_MPI));
        // 第 3 条故意用 MR：验证一个 study 里不同 SOP Class / 传输语法不会被归一成一种
        list.add(new DicomInstance(STUDY_UID, UID_ROOT + ".3.3", MR_STORAGE, PATIENT_MPI));
        return list;
    }

    @BeforeEach
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void setUp() throws IOException, GeneralSecurityException {
        peerRepository = mock(PeerRepository.class);

        dicomProps = new DicomConfigProps();
        dicomProps.setLocalAet(LOCAL_SCU_AET);
        dicomProps.setLocalHost(HOST);
        dicomProps.setRemoteConnectTimeoutMs(5000);

        routingProps = new RoutingConfigProps();
        routingProps.setQueryTimeoutMs(10000);
        routingProps.setRetrieveTimeoutMs(15000);
        routingProps.setStoreTimeoutMs(8000);
        routingProps.setMaxInstancesPerStudy(5000);
        routingProps.setLocalMoveDestAet(LOCAL_MOVE_DEST_AET);
        routingProps.setLocalMoveDestHost(HOST);
        routingProps.setLocalMoveDestPort(0);
        // 写入目标：本院 PACS（各用例在 pointLocalPacsAt 里切）
        pointLocalPacsAt(localPacs);

        service = new DicomRoutingService(peerRepository, dicomProps, routingProps);

        // 起本地 C-MOVE 落点（MoveDestinationStoreScp），把外院 C-MOVE 的推流接住
        moveSink = new MoveDestinationStoreScp.InstanceSink(
                routingProps.getMaxInstancesPerStudy(), routingProps.getStorageSopClasses());
        MoveDestinationStoreScp localStoreScp = new MoveDestinationStoreScp(routingProps, moveSink);
        routingProps.setLocalMoveDestPort(localStoreScp.startWithSink(moveSink));
        moveScp.setMoveDestination(LOCAL_MOVE_DEST_AET, HOST,
                routingProps.getLocalMoveDestPort());
        moveScp.setInstanceSink(null);   // 走真 C-STORE 子操作，不用注入 sink 的快路径

        givenPeer(PEER_AET, moveScp.getPort());
        localPacs.reset();
    }

    private void pointLocalPacsAt(FakeLocalPacs pacs) {
        routingProps.setLocalPacsAet(pacs.getAet());
        routingProps.setLocalPacsHost(HOST);
        routingProps.setLocalPacsPort(pacs.getPort());
    }

    private void givenPeer(String aet, int port) {
        Peer peer = new Peer();
        peer.setPeerAet(aet);
        peer.setHost(HOST);
        peer.setPort(port);
        peer.setTlsMode("none");
        peer.setPresentContexts("[\"" + STUDY_ROOT_FIND + "\", \"" + STUDY_ROOT_MOVE + "\"]");
        peer.setTransferSyntaxes("[\"1.2.840.10008.1.2.1\", \"1.2.840.10008.1.2\"]");
        peer.setBlacklistAets("[]");
        when(peerRepository.findByPeerAet(aet)).thenReturn(Optional.of(peer));
    }

    private static final String STUDY_ROOT_FIND = "1.2.840.10008.5.1.4.1.2.2.1";
    private static final String STUDY_ROOT_MOVE = "1.2.840.10008.5.1.4.1.2.2.2";

    /** 捕获审计行的 {@link AuditService} 替身（复用 task 14 测试里的同构写法）。 */
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
            return rows.stream().filter(r -> statusTerminal.equals(r.getStatusTerminal())).toList();
        }
    }

    // ==================================================================
    // step 5：三步闭环 query → retrieve → store
    // ==================================================================

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("三步闭环：C-FIND 命中 → C-MOVE 拉回 → C-STORE 写入，本院 PACS 真收到 3 个 instance")
    void threeStepPathEndsInLocalPacs() {
        // 真实外院 PACS 是一个监听端口同时提供 FIND 与 MOVE；替身拆成两个监听端口，
        // 所以这里分别把 peer 指向对应的那个（生产路径下二者是同一个 host/port）
        givenPeer(PEER_AET, queryPacs.getPort());
        List<StudyRef> studies = service.queryStudies(PEER_AET, PATIENT_MPI, null);
        assertEquals(1, studies.size(), "外院 C-FIND 应命中 1 个 study");
        assertEquals(STUDY_UID, studies.get(0).getStudyUid(), "StudyInstanceUID 逐字返回");

        givenPeer(PEER_AET, moveScp.getPort());
        RetrieveResult retrieved = service.retrieveStudy(PEER_AET, STUDY_UID, moveSink);
        assertEquals(3, retrieved.getInstanceCount(), "C-MOVE 拉回 3 个 instance");

        StoreResult stored = service.storeToLocal(retrieved, PEER_AET);

        assertAll(
                () -> assertEquals(3, stored.getSopInstanceCount(), "本院 PACS 回 Success 3 条"),
                () -> assertEquals(3, stored.getRequestedCount()),
                () -> assertEquals(0, stored.getSkippedCount()),
                () -> assertEquals(0, stored.getFailedCount()),
                () -> assertTrue(stored.isTerminalSuccess()),
                () -> assertEquals(3, localPacs.getCstoreRequests(), "本院 PACS 真收到 3 条 C-STORE-RQ"),
                () -> assertEquals(3, localPacs.getCstoreSucceeded()),
                () -> assertEquals(3, localPacs.storedDatasets().size(),
                        "「影像进了 PACS」的证据 = 替身存档的 dataset 数"),
                () -> assertTrue(stored.getTotalBytes() > 0),
                () -> assertTrue(stored.getDurationMs() >= 0)
        );
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("UID 不变量：SOPInstanceUID 与 StudyInstanceUID 逐字透传，一个字符都不改")
    void uidsArePassedThroughVerbatim() {
        RetrieveResult retrieved = service.retrieveStudy(PEER_AET, STUDY_UID, moveSink);
        StoreResult stored = service.storeToLocal(retrieved, PEER_AET);

        List<String> expectedSopUids = List.of(
                UID_ROOT + ".3.1", UID_ROOT + ".3.2", UID_ROOT + ".3.3");
        assertAll(
                () -> assertEquals(expectedSopUids, stored.getSopInstanceUids(),
                        "SCU 侧记录的 SOPInstanceUID 与外院一致"),
                () -> assertEquals(expectedSopUids, localPacs.storedSopInstanceUids(),
                        "本院 PACS 侧独立核对：落库 SOPInstanceUID 逐字一致"),
                () -> assertEquals(List.of(STUDY_UID, STUDY_UID, STUDY_UID),
                        localPacs.storedStudyInstanceUids(),
                        "StudyInstanceUID 同样不重命名"),
                () -> assertEquals(List.of(CT_STORAGE, CT_STORAGE, MR_STORAGE),
                        stored.getSopClassUids(),
                        "同一 study 内 CT/CT/MR 各自保留自己的存储 SOP Class")
        );
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("成功审计：落 1 行 status_terminal=stored，带 to_aet / sop_class_uid / transfer_syntax")
    void storeSuccessWritesAuditRow() {
        RecordingAuditService audit = new RecordingAuditService(false);
        service.setAuditService(audit);

        StoreResult stored = service.storeToLocal(part10Buffers(3), PEER_AET);

        List<AuditRow> storedRows = audit.rowsOf(DicomRoutingService.STATUS_STORED);
        assertAll(
                () -> assertEquals(1, storedRows.size(), "成功恰好落一行审计"),
                () -> assertEquals(3, stored.getSopInstanceCount()),
                () -> assertEquals(LOCAL_PACS_AET, storedRows.get(0).getToAet(),
                        "to_aet = 本院 PACS AE Title"),
                () -> assertEquals(PEER_AET, storedRows.get(0).getFromAet(),
                        "from_aet = 影像来源外院节点"),
                () -> assertEquals(STUDY_UID, storedRows.get(0).getStudyUid()),
                () -> assertEquals(0, storedRows.get(0).getRetryCount()),
                () -> assertNull(storedRows.get(0).getFailureCode(),
                        "成功行不落 failure_code"),
                () -> assertTrue(storedRows.get(0).getSopClassUid().contains(CT_STORAGE),
                        "sop_class_uid 列记去重集合: " + storedRows.get(0).getSopClassUid()),
                () -> assertNotNull(storedRows.get(0).getTransferSyntax())
        );
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("storeResult 报告写入目标与去重集合（运维看板要的三个数）")
    void storeResultReportsTargetAndCounts() {
        StoreResult stored = service.storeToLocal(part10Buffers(3), PEER_AET);

        assertAll(
                () -> assertEquals(LOCAL_PACS_AET, stored.getTargetPacsAet()),
                () -> assertEquals(HOST, stored.getTargetPacsHost()),
                () -> assertEquals(3, stored.getSopInstanceCount()),
                () -> assertEquals(List.of(CT_STORAGE), stored.distinctSopClassUids()),
                () -> assertEquals(1, stored.distinctTransferSyntaxes().size()),
                () -> assertTrue(stored.isNonEmpty()),
                () -> assertNotNull(stored.toString())
        );
    }

    // ==================================================================
    // 失败路径
    // ==================================================================

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("失败 store_failed：本院 PACS 全拒 → STORE_FAILED + 落一行失败审计")
    void storeFailedWhenPacsRejectsAll() {
        pointLocalPacsAt(rejectingPacs);
        RecordingAuditService audit = new RecordingAuditService(false);
        service.setAuditService(audit);
        rejectingPacs.reset();

        RoutingFailureException ex = assertThrows(RoutingFailureException.class,
                () -> service.storeToLocal(part10Buffers(3), PEER_AET));

        assertAll(
                () -> assertEquals(FailureCode.STORE_FAILED, ex.getFailureCode()),
                () -> assertEquals("store_failed", ex.getDbFailureCode()),
                () -> assertEquals(3, rejectingPacs.getCstoreRejected(), "三条全被拒"),
                () -> assertEquals(0, rejectingPacs.getCstoreSucceeded()),
                () -> assertEquals(1, audit.rowsOf(DicomRoutingService.STATUS_STORE_FAILED).size(),
                        "失败恰好落一行审计"),
                () -> assertEquals("store_failed",
                        audit.rowsOf(DicomRoutingService.STATUS_STORE_FAILED).get(0).getFailureCode())
        );
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("失败 partial_failure：3 条里进了 2 条 → 抛 PARTIAL_FAILURE，绝不返回残缺结果")
    void partialFailureWhenSomeStoresFail() {
        pointLocalPacsAt(partialPacs);
        RecordingAuditService audit = new RecordingAuditService(false);
        service.setAuditService(audit);
        partialPacs.reset();

        RoutingFailureException ex = assertThrows(RoutingFailureException.class,
                () -> service.storeToLocal(part10Buffers(3), PEER_AET));

        assertAll(
                () -> assertEquals(FailureCode.PARTIAL_FAILURE, ex.getFailureCode()),
                () -> assertEquals("partial_failure", ex.getDbFailureCode()),
                () -> assertTrue(ex.getMessage().contains("成功 1"), "消息带成功计数: " + ex.getMessage()),
                () -> assertTrue(ex.getMessage().contains("失败 2"), "消息带失败计数: " + ex.getMessage()),
                () -> assertEquals(1, partialPacs.getCstoreSucceeded(), "前 1 条进 PACS"),
                () -> assertEquals(2, partialPacs.getCstoreRejected(), "后 2 条被拒"),
                () -> assertEquals("partial_failure",
                        audit.rowsOf(DicomRoutingService.STATUS_STORE_FAILED).get(0).getFailureCode())
        );
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("失败 store_timeout：本院 PACS 不应答，到 storeTimeoutMs 放弃")
    void storeTimeoutWhenPacsStaysSilent() {
        pointLocalPacsAt(silentPacs);
        routingProps.setStoreTimeoutMs(700);   // 缩短预算让用例快跑
        silentPacs.reset();

        long start = System.currentTimeMillis();
        RoutingFailureException ex = assertThrows(RoutingFailureException.class,
                () -> service.storeToLocal(part10Buffers(3), PEER_AET));
        long elapsed = System.currentTimeMillis() - start;

        assertAll(
                () -> assertTrue(
                        ex.getFailureCode() == FailureCode.STORE_TIMEOUT
                                || ex.getFailureCode() == FailureCode.STORE_FAILED,
                        "归类为 STORE_TIMEOUT 或 STORE_FAILED: " + ex.getFailureCode()),
                () -> assertTrue(ex.isRetryable(), "网络瞬态可重试"),
                () -> assertTrue(elapsed < 30_000, "不该阻塞至默认预算，实际 " + elapsed + "ms"),
                () -> assertEquals(1, silentPacs.getAcceptedAssociations(), "关联本身应已建立")
        );
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("失败 association_rejected：本院 PACS 回 A-ASSOCIATE-RJ")
    void associationRejectedWhenPacsRefusesAet() {
        pointLocalPacsAt(rejectingAssocPacs);
        rejectingAssocPacs.reset();

        RoutingFailureException ex = assertThrows(RoutingFailureException.class,
                () -> service.storeToLocal(part10Buffers(1), PEER_AET));

        assertAll(
                () -> assertEquals(FailureCode.ASSOCIATION_REJECTED, ex.getFailureCode()),
                () -> assertEquals("association_rejected", ex.getDbFailureCode()),
                () -> assertEquals(1, rejectingAssocPacs.getRejectedAssociations()),
                () -> assertEquals(0, rejectingAssocPacs.getCstoreRequests(),
                        "关联都没立起来，不该有 C-STORE")
        );
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("失败 store_failed：本院 PACS 端口没人监听 → 明确失败，不静默")
    void storeFailedWhenNothingIsListening() {
        routingProps.setLocalPacsAet(LOCAL_PACS_AET);
        routingProps.setLocalPacsHost(HOST);
        routingProps.setLocalPacsPort(pickClosedPort());

        RoutingFailureException ex = assertThrows(RoutingFailureException.class,
                () -> service.storeToLocal(part10Buffers(1), PEER_AET));

        assertAll(
                () -> assertEquals(FailureCode.STORE_FAILED, ex.getFailureCode()),
                () -> assertTrue(ex.getMessage().contains("连不上本院 PACS"),
                        "错误消息指向本院 PACS 连通性: " + ex.getMessage())
        );
    }

    // ==================================================================
    // 边界：不建联就拦下 / 字节形态 / 缺字段
    // ==================================================================

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("边界：SOP Class 不在 storageSopClasses 白名单 → 建联前拦下，零外发调用")
    void unsupportedSopClassIsSkippedBeforeConnecting() {
        localPacs.reset();

        List<ByteBuffer> buffers = List.of(ByteBuffer.wrap(FakeMoveScp.buildPart10Bytes(
                STUDY_UID, UID_ROOT + ".9.1", SR_STORAGE, PATIENT_MPI)));
        RoutingFailureException ex = assertThrows(RoutingFailureException.class,
                () -> service.storeToLocal(STUDY_UID, buffers, PEER_AET));

        assertAll(
                () -> assertEquals(FailureCode.STORE_FAILED, ex.getFailureCode()),
                () -> assertTrue(ex.getMessage().contains(SR_STORAGE),
                        "消息点名被拦下的 SOP Class: " + ex.getMessage()),
                () -> assertEquals(0, localPacs.getAcceptedAssociations(),
                        "整批被白名单筛掉，不该建立关联"),
                () -> assertEquals(0, localPacs.getCstoreRequests(), "零外发调用")
        );
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("边界：空 instances / 缺 study_uid → 参数校验在建联之前，零外发调用")
    void invalidArgumentsRejectedBeforeConnecting() {
        localPacs.reset();

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.storeToLocal(STUDY_UID, null, PEER_AET)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.storeToLocal(STUDY_UID, List.of(), PEER_AET)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.storeToLocal("  ", part10Buffers(1), PEER_AET)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.storeToLocal((RetrieveResult) null))
        );
        assertEquals(0, localPacs.getAcceptedAssociations(), "参数非法不该建联");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("边界：缺 SOPInstanceUID 的 instance 不编造 UID，直接拒收并计入失败")
    void instanceWithoutSopInstanceUidIsRejected() {
        localPacs.reset();

        // 造一条没有 (0008,0018) 的 dataset（只有 SOPClassUID + StudyInstanceUID）
        Attributes ds = new Attributes();
        ds.setString(Tag.SOPClassUID, VR.UI, CT_STORAGE);
        ds.setString(Tag.StudyInstanceUID, VR.UI, STUDY_UID);
        byte[] bytes = bareDataset(ds);

        RoutingFailureException ex = assertThrows(RoutingFailureException.class,
                () -> service.storeToLocal(STUDY_UID,
                        List.of(ByteBuffer.wrap(bytes)), PEER_AET));

        assertAll(
                () -> assertEquals(FailureCode.STORE_FAILED, ex.getFailureCode()),
                () -> assertTrue(ex.getMessage().contains("unreadable")
                                || ex.getMessage().contains("跳过"),
                        "跳过原因要能看出来: " + ex.getMessage()),
                () -> assertEquals(0, localPacs.getCstoreRequests(), "零外发调用")
        );
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("字节形态：Part-10 文件与裸 dataset 两条上游路径都能写入")
    void bothPart10AndBareDatasetAreAccepted() {
        localPacs.reset();

        // 一条 Part-10（128 前导 + DICM + file-meta）
        byte[] part10 = FakeMoveScp.buildPart10Bytes(STUDY_UID, UID_ROOT + ".5.1",
                CT_STORAGE, PATIENT_MPI);
        // 一条裸 dataset（无前导无 file-meta，与 MoveDestinationStoreScp 产出同形态）
        Attributes ds = new Attributes();
        ds.setString(Tag.SOPClassUID, VR.UI, CT_STORAGE);
        ds.setString(Tag.SOPInstanceUID, VR.UI, UID_ROOT + ".5.2");
        ds.setString(Tag.StudyInstanceUID, VR.UI, STUDY_UID);
        ds.setString(Tag.SeriesInstanceUID, VR.UI, UID_ROOT + ".5.series");
        byte[] bare = bareDataset(ds);

        StoreResult stored = service.storeToLocal(STUDY_UID,
                List.of(ByteBuffer.wrap(part10), ByteBuffer.wrap(bare)), PEER_AET);

        assertAll(
                () -> assertEquals(2, stored.getSopInstanceCount()),
                () -> assertEquals(2, localPacs.getCstoreSucceeded()),
                () -> assertEquals(List.of(UID_ROOT + ".5.1", UID_ROOT + ".5.2"),
                        localPacs.storedSopInstanceUids(),
                        "两种形态的 UID 都原样进了本院 PACS")
        );
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("降级：审计库故障不阻断写入（影像已进 PACS，不能因审计失败改判业务状态）")
    void auditFailureDoesNotBreakStore() {
        service.setAuditService(new RecordingAuditService(true));
        localPacs.reset();

        StoreResult stored = service.storeToLocal(part10Buffers(2), PEER_AET);

        assertAll(
                () -> assertEquals(2, stored.getSopInstanceCount()),
                () -> assertTrue(stored.isTerminalSuccess()),
                () -> assertEquals(2, localPacs.getCstoreSucceeded())
        );
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("配置缺失：本院 PACS AET / host 未配置 → 明确失败，不尝试连空地址")
    void unconfiguredLocalPacsIsStoreFailed() {
        routingProps.setLocalPacsAet("  ");
        RoutingFailureException ex = assertThrows(RoutingFailureException.class,
                () -> service.storeToLocal(part10Buffers(1), PEER_AET));
        assertAll(
                () -> assertEquals(FailureCode.STORE_FAILED, ex.getFailureCode()),
                () -> assertTrue(ex.getMessage().contains("LOCAL_PACS_AET"),
                        "错误消息直接点名要配的环境变量: " + ex.getMessage())
        );

        routingProps.setLocalPacsAet(LOCAL_PACS_AET);
        routingProps.setLocalPacsHost(null);
        RoutingFailureException ex2 = assertThrows(RoutingFailureException.class,
                () -> service.storeToLocal(part10Buffers(1), PEER_AET));
        assertAll(
                () -> assertEquals(FailureCode.STORE_FAILED, ex2.getFailureCode()),
                () -> assertTrue(ex2.getMessage().contains("LOCAL_PACS_HOST"))
        );
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("配置型失败：AET 超过 16 字符 → 明确报「LOCAL_PACS_AET 配错」，不冒协议栈裸异常")
    void overlyLongAetIsConfigurationFailure() {
        routingProps.setLocalPacsAet("LOCAL_PACS_XMLONGNAME");   // 21 字符，超出 PS3.5 的 16
        localPacs.reset();

        RoutingFailureException ex = assertThrows(RoutingFailureException.class,
                () -> service.storeToLocal(part10Buffers(1), PEER_AET));

        assertAll(
                () -> assertEquals(FailureCode.STORE_FAILED, ex.getFailureCode()),
                () -> assertTrue(ex.getMessage().contains("16"),
                        "消息点明 AE Title 长度上限: " + ex.getMessage()),
                () -> assertTrue(ex.getMessage().contains("LOCAL_PACS_AET"),
                        "消息点名要改的环境变量: " + ex.getMessage()),
                () -> assertEquals(0, localPacs.getAcceptedAssociations(), "配置错不该建联")
        );
    }

    // ==================================================================
    // 工具方法与值对象
    // ==================================================================

    @Test
    @DisplayName("工具方法：isPart10 判别 Part-10 / 无前导 Part-10 / 裸 dataset / 垃圾字节")
    void part10Detection() {
        assertAll(
                () -> assertTrue(DicomRoutingService.isPart10(
                        FakeMoveScp.buildPart10Bytes(STUDY_UID, UID_ROOT + ".1", CT_STORAGE, PATIENT_MPI)),
                        "带 128 前导 + DICM"),
                () -> assertTrue(DicomRoutingService.isPart10(headerlessPart10()),
                        "无前导、以 (0002,0000) 开头的 Part-10"),
                () -> assertFalse(DicomRoutingService.isPart10(bareDataset(minimalDataset())),
                        "裸 dataset 不是 Part-10"),
                () -> assertFalse(DicomRoutingService.isPart10(TestDicomFixtures.notDicom(64)),
                        "垃圾字节不是 Part-10"),
                () -> assertFalse(DicomRoutingService.isPart10(new byte[]{1, 2})),
                () -> assertFalse(DicomRoutingService.isPart10(null))
        );
    }

    @Test
    @DisplayName("工具方法：ByteBuffer 按 position→limit 取字节，不做隐式 rewind")
    void toBytesRespectsPositionAndLimit() {
        assertAll(
                () -> assertArrayEquals(new byte[]{9, 1, 2, 3},
                        DicomRoutingService.toBytes(ByteBuffer.wrap(new byte[]{9, 1, 2, 3}))),
                () -> {
                    ByteBuffer window = ByteBuffer.wrap(new byte[]{9, 1, 2, 3});
                    window.position(2);
                    assertArrayEquals(new byte[]{2, 3},
                            DicomRoutingService.toBytes(window),
                            "已消费到 position=2 时只取剩下的 2 字节");
                }
        );
    }

    @Test
    @DisplayName("值对象：StoreResult 不可变 —— equals / hashCode / 计数自洽 / empty")
    void storeResultIsImmutableValueObject() {
        StoreResult a = StoreResult.builder()
                .targetPacsAet(LOCAL_PACS_AET).targetPacsHost(HOST)
                .requestedCount(2).sopInstanceUids(List.of("u1", "u2"))
                .sopClassUids(List.of(CT_STORAGE, CT_STORAGE))
                .transferSyntaxes(List.of(UID.ExplicitVRLittleEndian, UID.ExplicitVRLittleEndian))
                .totalBytes(100L).durationMs(42L).terminalStatusHex(0)
                .build();
        StoreResult b = StoreResult.builder()
                .targetPacsAet(LOCAL_PACS_AET).targetPacsHost(HOST)
                .requestedCount(2).sopInstanceUids(List.of("u1", "u2"))
                .sopClassUids(List.of(CT_STORAGE, CT_STORAGE))
                .transferSyntaxes(List.of(UID.ExplicitVRLittleEndian, UID.ExplicitVRLittleEndian))
                .totalBytes(100L).durationMs(42L).terminalStatusHex(0)
                .build();

        assertAll(
                () -> assertEquals(a, b),
                () -> assertEquals(a.hashCode(), b.hashCode()),
                () -> assertEquals(2, a.getSopInstanceCount()),
                () -> assertEquals(0, a.getSkippedCount()),
                () -> assertEquals(0, a.getFailedCount()),
                () -> assertTrue(a.isTerminalSuccess()),
                () -> assertEquals(List.of(CT_STORAGE), a.distinctSopClassUids()),
                () -> assertEquals(List.of(UID.ExplicitVRLittleEndian), a.distinctTransferSyntaxes()),
                () -> assertFalse(StoreResult.empty(LOCAL_PACS_AET, HOST).isNonEmpty()),
                () -> assertThrows(IllegalArgumentException.class, () -> StoreResult.builder()
                        .sopInstanceUids(List.of("u1"))
                        .sopClassUids(List.of(CT_STORAGE, MR_STORAGE))
                        .transferSyntaxes(List.of(UID.ExplicitVRLittleEndian))
                        .build())
        );
    }

    @Test
    @DisplayName("值对象：StoreResult 失败计数 = 请求 - 跳过 - 成功")
    void storeResultCountsFailureResidue() {
        StoreResult partial = StoreResult.builder()
                .targetPacsAet(LOCAL_PACS_AET).targetPacsHost(HOST)
                .requestedCount(3).skippedCount(0)
                .sopInstanceUids(List.of("u1"))
                .sopClassUids(List.of(CT_STORAGE))
                .transferSyntaxes(List.of(UID.ExplicitVRLittleEndian))
                .totalBytes(10L).durationMs(5L).terminalStatusHex(0xC000)
                .build();
        assertAll(
                () -> assertEquals(2, partial.getFailedCount(), "3 请求 - 0 跳过 - 1 成功"),
                () -> assertFalse(partial.isTerminalSuccess()),
                () -> assertTrue(partial.isNonEmpty())
        );
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** n 条 Part-10 instance 字节（SOPInstanceUID 递增）。 */
    private static List<ByteBuffer> part10Buffers(int n) {
        List<ByteBuffer> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(ByteBuffer.wrap(FakeMoveScp.buildPart10Bytes(
                    STUDY_UID, UID_ROOT + ".8." + i, CT_STORAGE, PATIENT_MPI)));
        }
        return out;
    }

    private static Attributes minimalDataset() {
        Attributes ds = new Attributes();
        ds.setString(Tag.SOPClassUID, VR.UI, CT_STORAGE);
        return ds;
    }

    /** 裸 dataset 字节：只有 dataset 段，无前导无 DICM 无 file-meta。 */
    private static byte[] bareDataset(Attributes dataset) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            org.dcm4che3.io.DicomOutputStream dos = new org.dcm4che3.io.DicomOutputStream(
                    (OutputStream) baos, UID.ExplicitVRLittleEndian);
            dos.setEncodingOptions(org.dcm4che3.io.DicomEncodingOptions.DEFAULT);
            dos.writeDataset(null, dataset);
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("构造裸 dataset 失败", e);
        }
    }

    /** 无前导的 Part-10：直接以 file-meta 首元素 (0002,0000) 开头（老设备形态）。 */
    private static byte[] headerlessPart10() {
        byte[] part10 = FakeMoveScp.buildPart10Bytes(STUDY_UID, UID_ROOT + ".6.1",
                CT_STORAGE, PATIENT_MPI);
        byte[] out = new byte[part10.length - 132];
        System.arraycopy(part10, 132, out, 0, out.length);
        return out;
    }

    private static int pickClosedPort() {
        try (java.net.ServerSocket probe =
                     new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("无法借端口", e);
        }
    }

}