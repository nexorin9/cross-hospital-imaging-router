package com.crosshospital.imaging.integration;

import com.crosshospital.imaging.config.DicomConfigProps;
import com.crosshospital.imaging.config.RoutingConfigProps;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.domain.RetrieveResult;
import com.crosshospital.imaging.domain.StoreResult;
import com.crosshospital.imaging.domain.StudyRef;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.exception.HandshakeException;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.dicom.BlacklistEnforcer;
import com.crosshospital.imaging.service.dicom.CompatibleHandshakeService;
import com.crosshospital.imaging.service.dicom.DeviceFactory;
import com.crosshospital.imaging.service.dicom.HandshakeResult;
import com.crosshospital.imaging.service.dicom.SopClassFilter;
import com.crosshospital.imaging.service.dicom.TransferSyntaxNegotiator;
import com.crosshospital.imaging.service.routing.DicomRoutingService;
import com.crosshospital.imaging.service.routing.MoveDestinationStoreScp;
import com.crosshospital.imaging.service.task.FetchTaskQueuePort;
import com.crosshospital.imaging.service.task.FetchTaskService;
import com.crosshospital.imaging.support.FakeLocalPacs;
import com.crosshospital.imaging.support.FakeMoveScp;
import com.crosshospital.imaging.support.FakePacsServer;
import com.crosshospital.imaging.support.TestDicomFixtures;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 35 — 端到端集成：真实 DICOM over TCP 的 query → retrieve → store 三步闭环。
 *
 * <p><b>关于「起 storescp/storescu 进程」</b>：本仓库构建环境离线，本地 Maven 仓库里没有
 * {@code dcm4che-tool-storescp / storescu} 可执行 jar；因此用项目自带的
 * {@link FakePacsServer} / {@link FakeMoveScp} / {@link FakeLocalPacs} 三个
 * <b>真 dcm4che SCP</b> 顶替（同样是 {@code Device + Connection + ApplicationEntity + DicomServiceRegistry}，
 * 同样监听 loopback TCP、走 A-ASSOCIATE-RQ/AC 与 C-FIND / C-MOVE / C-STORE PDU），
 * 只是跑在测试 JVM 内而不是外部进程。协议栈一点没 mock：被测的
 * {@link DicomRoutingService} 用的是真实 {@code Association.cfind / cmove / cstore}。
 * fixture 落在 {@code src/test/fixtures/samples-dicom/}，全是自造示例 UID，无患者可回溯信息。
 */
@DisplayName("Task 35 — DICOM query→retrieve→store 集成")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class PacsIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final String PEER_AET = "TEST_PEER";
    private static final String LOCAL_SCU_AET = "ROUTER_SCU";
    private static final String LOCAL_MOVE_DEST_AET = "IMAGER";
    private static final String LOCAL_PACS_AET = "LOCAL_PACS";
    private static final String PATIENT_MPI = "PAT_MPI_EXAMPLE";
    private static final String UID_ROOT = TestDicomFixtures.EXAMPLE_UID_ROOT;
    private static final String STUDY_UID = UID_ROOT + ".1.20260101";
    private static final String CT_STORAGE = "1.2.840.10008.5.1.4.1.1.2";
    private static final String STUDY_ROOT_FIND = "1.2.840.10008.5.1.4.1.2.2.1";
    private static final String STUDY_ROOT_MOVE = "1.2.840.10008.5.1.4.1.2.2.2";

    private static FakePacsServer queryPacs;
    private static FakeMoveScp moveScp;
    private static FakeLocalPacs localPacs;
    private static MoveDestinationStoreScp localStoreScp;
    private static MoveDestinationStoreScp.InstanceSink moveSink;
    private static int localMoveDestPort;

    private PeerRepository peerRepository;
    private DicomConfigProps dicomProps;
    private RoutingConfigProps routingProps;
    private DicomRoutingService routing;
    private CompatibleHandshakeService handshake;

    @BeforeAll
    static void startTopology() throws IOException, GeneralSecurityException {
        queryPacs = new FakePacsServer(PEER_AET, 0,
                List.of(FakePacsServer.studyOf(PATIENT_MPI, "ACC_EXAMPLE",
                        STUDY_UID, "CT", "20260101", 3)),
                FakePacsServer.NORMAL, 0L).start();

        moveScp = new FakeMoveScp(PEER_AET, 0, LOCAL_MOVE_DEST_AET, HOST, 0,
                FakeMoveScp.Mode.NORMAL).start();
        moveScp.registerStudy(STUDY_UID, threeInstances());

        localPacs = FakeLocalPacs.normal(LOCAL_PACS_AET, 0).start();

        RoutingConfigProps moveProps = new RoutingConfigProps();
        moveProps.setMaxInstancesPerStudy(5000);
        moveProps.setLocalMoveDestAet(LOCAL_MOVE_DEST_AET);
        moveProps.setLocalMoveDestHost(HOST);
        moveProps.setLocalMoveDestPort(0);
        moveSink = new MoveDestinationStoreScp.InstanceSink(
                moveProps.getMaxInstancesPerStudy(), moveProps.getStorageSopClasses());
        localStoreScp = new MoveDestinationStoreScp(moveProps, moveSink);
        localMoveDestPort = localStoreScp.startWithSink(moveSink);

        // 关键：不注入 sink 快路径，让外院 MOVE-SCP 真开第二条关联推 C-STORE 到本端 SCP
        moveScp.setMoveDestination(LOCAL_MOVE_DEST_AET, HOST, localMoveDestPort);
        moveScp.setInstanceSink(null);
    }

    @AfterAll
    static void stopTopology() {
        if (localStoreScp != null) localStoreScp.close();
        if (queryPacs != null) queryPacs.close();
        if (moveScp != null) moveScp.close();
        if (localPacs != null) localPacs.close();
    }

    @BeforeEach
    void setUp() {
        peerRepository = mock(PeerRepository.class);

        dicomProps = new DicomConfigProps();
        dicomProps.setLocalAet(LOCAL_SCU_AET);
        dicomProps.setLocalHost(HOST);
        dicomProps.setRemoteConnectTimeoutMs(2000);
        // 握手健康检查的 SOP 白名单按对接实际登记：查询/提取 + CT 存储
        dicomProps.setSupportedSopClasses(List.of(STUDY_ROOT_FIND, STUDY_ROOT_MOVE, CT_STORAGE));

        routingProps = new RoutingConfigProps();
        routingProps.setQueryTimeoutMs(8000);
        routingProps.setRetrieveTimeoutMs(12000);
        routingProps.setStoreTimeoutMs(8000);
        routingProps.setMaxInstancesPerStudy(5000);
        routingProps.setLocalMoveDestAet(LOCAL_MOVE_DEST_AET);
        routingProps.setLocalMoveDestHost(HOST);
        routingProps.setLocalMoveDestPort(localMoveDestPort);
        routingProps.setLocalPacsAet(LOCAL_PACS_AET);
        routingProps.setLocalPacsHost(HOST);
        routingProps.setLocalPacsPort(localPacs.getPort());

        routing = new DicomRoutingService(peerRepository, dicomProps, routingProps);
        handshake = new CompatibleHandshakeService(peerRepository,
                new DeviceFactory(dicomProps), dicomProps,
                new TransferSyntaxNegotiator(), new SopClassFilter(), new BlacklistEnforcer());
        moveSink.clear();
        localPacs.reset();
    }

    @AfterEach
    void resetSharedFakes() {
        // 替身全类共享，计数器按用例复位；监听端口在 @AfterAll 统一回收
        moveSink.clear();
        localPacs.reset();
    }

    private static List<FakeMoveScp.DicomInstance> threeInstances() {
        List<FakeMoveScp.DicomInstance> list = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            list.add(new FakeMoveScp.DicomInstance(STUDY_UID,
                    UID_ROOT + ".3." + i, CT_STORAGE, PATIENT_MPI));
        }
        return list;
    }

    private Peer peer(String aet, int port, String sopClasses, String transferSyntaxes,
                      String blacklist) {
        Peer p = new Peer();
        p.setPeerAet(aet);
        p.setHost(HOST);
        p.setPort(port);
        p.setTlsMode("none");
        p.setPresentContexts(sopClasses);
        p.setTransferSyntaxes(transferSyntaxes);
        p.setBlacklistAets(blacklist);
        p.setEnabled(true);
        return p;
    }

    private void givenPeer(String aet, int port) {
        when(peerRepository.findByPeerAet(aet)).thenReturn(Optional.of(
                peer(aet, port,
                        "[\"" + STUDY_ROOT_FIND + "\",\"" + STUDY_ROOT_MOVE + "\"]",
                        "[\"1.2.840.10008.1.2.1\",\"1.2.840.10008.1.2\"]", "[]")));
    }

    // ─────────────────────────── 握手健康检查 ───────────────────────────

    @Test
    @DisplayName("握手健康检查：handshake('TEST_PEER') → compatible=true（真 TCP 探活）")
    void handshakeHealthOnExternalPeer() {
        givenPeer(PEER_AET, queryPacs.getPort());
        HandshakeResult result = handshake.handshake(PEER_AET);
        assertAll(
                () -> assertTrue(result.compatible, "外院 PACS 在监听且协商有交集，应 compatible"),
                () -> assertEquals(PEER_AET, result.peerAet),
                () -> assertFalse(result.commonSopClasses.isEmpty()),
                () -> assertFalse(result.agreedTransferSyntaxes.isEmpty()),
                () -> assertNull(result.lastErrorCode, "成功握手不应带 lastErrorCode")
        );
    }

    // ─────────────────────────── 三步闭环 ───────────────────────────

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("端到端：fetch → C-FIND → C-MOVE → C-STORE，本院 PACS 真收到 3 个 DICOM 对象")
    void threeStepPathWritesThreeObjectsIntoLocalPacs() {
        givenPeer(PEER_AET, queryPacs.getPort());
        List<StudyRef> studies = routing.queryStudies(PEER_AET, PATIENT_MPI, null);
        assertEquals(1, studies.size());
        assertEquals(STUDY_UID, studies.get(0).getStudyUid(), "外院 UID 不重命名");

        givenPeer(PEER_AET, moveScp.getPort());
        RetrieveResult retrieved = routing.retrieveStudy(PEER_AET, STUDY_UID, moveSink);
        assertEquals(3, retrieved.getInstanceCount());

        StoreResult stored = routing.storeToLocal(retrieved, PEER_AET);

        assertAll(
                () -> assertTrue(stored.isTerminalSuccess()),
                () -> assertEquals(3, stored.getSopInstanceCount()),
                () -> assertEquals(0, stored.getFailedCount()),
                () -> assertEquals(3, localPacs.getCstoreRequests(), "本院 SCP 真收到 3 条 C-STORE-RQ"),
                () -> assertEquals(3, localPacs.getCstoreSucceeded()),
                () -> assertEquals(3, localPacs.storedDatasets().size()),
                () -> assertEquals(List.of(STUDY_UID, STUDY_UID, STUDY_UID),
                        localPacs.storedStudyInstanceUids(), "StudyInstanceUID 逐字透传"),
                () -> assertEquals(List.of(UID_ROOT + ".3.1", UID_ROOT + ".3.2", UID_ROOT + ".3.3"),
                        localPacs.storedSopInstanceUids())
        );
    }

    @Test
    @DisplayName("任务五态推进落 4 行审计：querying / pulling / stored / notified")
    void fourAuditRowsForOneCompletedFetch() {
        RecordingAuditService audit = new RecordingAuditService();
        FetchTaskRepository repo = mock(FetchTaskRepository.class);
        FetchTaskQueuePort queue = mock(FetchTaskQueuePort.class);
        Map<UUID, FetchTask> store = new HashMap<>();

        FetchTask task = new FetchTask();
        UUID taskId = UUID.randomUUID();
        task.setTaskId(taskId);
        task.setPatientMpi(PATIENT_MPI);
        task.setStudyUid(STUDY_UID);
        task.setPeerAet(PEER_AET);
        task.setStatus(TaskStatus.QUEUED);
        store.put(taskId, task);

        when(repo.save(any(FetchTask.class))).thenAnswer(inv -> {
            FetchTask t = inv.getArgument(0);
            store.put(t.getTaskId(), t);
            return t;
        });
        when(repo.findByTaskId(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));

        FetchTaskService tasks = new FetchTaskService(repo, queue, audit);
        for (TaskStatus next : List.of(TaskStatus.QUERYING, TaskStatus.PULLING,
                TaskStatus.STORED, TaskStatus.NOTIFIED)) {
            tasks.advance(taskId, next);
        }

        Set<String> terminals = new TreeSet<>();
        for (AuditRow row : audit.rows) {
            terminals.add(row.getStatusTerminal());
        }
        assertEquals(Set.of("task_querying", "task_pulling", "task_stored", "task_notified"),
                terminals, "一次完整调阅应留下 4 行可核对的状态审计");
        assertEquals(4, audit.rows.size(), "每次推进恰好 1 行，重复推进不补写");
        assertEquals(TaskStatus.NOTIFIED, store.get(taskId).getStatus());
    }

    // ─────────────────────────── 三种失败路径 ───────────────────────────

    @Test
    @DisplayName("失败 1：外院 storescp 关闭 → CONNECTION_REFUSED（不是零命中）")
    void externalPacsDownIsClassifiedAsConnectionRefused() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        when(peerRepository.findByPeerAet("DOWN_PEER")).thenReturn(Optional.of(
                peer("DOWN_PEER", closedPort,
                        "[\"" + STUDY_ROOT_FIND + "\"]",
                        "[\"1.2.840.10008.1.2.1\"]", "[]")));
        HandshakeResult result = handshake.handshake("DOWN_PEER");
        assertAll(
                () -> assertFalse(result.compatible),
                () -> assertEquals(HandshakeException.FailureCode.CONNECTION_REFUSED,
                        result.lastErrorCode)
        );
        assertThrows(com.crosshospital.imaging.exception.RoutingFailureException.class,
                () -> routing.queryStudies("DOWN_PEER", PATIENT_MPI, null),
                "对端挂掉必须抛错，绝不能返回空列表伪装成「外院没片」");
    }

    @Test
    @DisplayName("失败 2：传输语法无交集 → TRANSFER_SYNTAX_EMPTY，探活之前就拦下")
    void noCommonTransferSyntaxIsRejected() {
        when(peerRepository.findByPeerAet("TS_PEER")).thenReturn(Optional.of(
                peer("TS_PEER", queryPacs.getPort(),
                        "[\"" + STUDY_ROOT_FIND + "\"]",
                        "[\"1.2.999.999.999\"]", "[]")));
        HandshakeResult result = handshake.handshake("TS_PEER");
        assertFalse(result.compatible);
        assertEquals(HandshakeException.FailureCode.TRANSFER_SYNTAX_EMPTY, result.lastErrorCode);
    }

    @Test
    @DisplayName("失败 3：远端拒接黑名单 → BLACKLISTED，retryable=false")
    void blacklistedPeerIsRejected() {
        when(peerRepository.findByPeerAet(PEER_AET)).thenReturn(Optional.of(
                peer(PEER_AET, queryPacs.getPort(),
                        "[\"" + STUDY_ROOT_FIND + "\"]",
                        "[\"1.2.840.10008.1.2.1\"]",
                        "[\"" + PEER_AET + "\"]")));
        HandshakeException ex = assertThrows(HandshakeException.class,
                () -> handshake.handshake(PEER_AET));
        assertAll(
                () -> assertEquals(HandshakeException.FailureCode.BLACKLISTED, ex.getFailureCode()),
                () -> assertFalse(ex.isRetryable(), "黑名单是配置策略，重试无意义")
        );
    }

    // ─────────────────────────── fixture ───────────────────────────

    @Test
    @DisplayName("samples-dicom/ 下 3 个 fixture 是合法 DICOM Part-10，UID 逐字可读")
    void fixtureDicomFilesAreValidPart10() throws IOException {
        Path dir = Path.of("src/test/fixtures/samples-dicom");
        for (int i = 1; i <= 3; i++) {
            byte[] bytes = Files.readAllBytes(dir.resolve(String.format("instance-%02d.dcm", i)));
            assertTrue(bytes.length > 132, "Part-10 至少有 128 前导 + DICM");
            assertEquals("DICM", new String(bytes, 128, 4, StandardCharsets.US_ASCII));
            try (DicomInputStream in = new DicomInputStream(new ByteArrayInputStream(bytes))) {
                Attributes fmi = in.readFileMetaInformation();
                assertEquals(CT_STORAGE, fmi.getString(Tag.MediaStorageSOPClassUID));
                assertEquals(UID_ROOT + ".3." + i,
                        fmi.getString(Tag.MediaStorageSOPInstanceUID));
                Attributes ds = in.readDataset();
                assertEquals(STUDY_UID, ds.getString(Tag.StudyInstanceUID));
            }
        }
    }

    /** 只记录、不落库的审计替身；用 CopyOnWriteArrayList 保证状态推进线程安全读取。 */
    private static final class RecordingAuditService extends AuditService {
        private final List<AuditRow> rows = new CopyOnWriteArrayList<>();

        RecordingAuditService() {
            super(null);
        }

        @Override
        public AuditRow record(AuditRow row) {
            rows.add(row);
            return row;
        }
    }
}
