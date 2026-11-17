package com.crosshospital.imaging.service.routing;

import com.crosshospital.imaging.config.DicomConfigProps;
import com.crosshospital.imaging.config.RoutingConfigProps;
import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.domain.StudyRef;
import com.crosshospital.imaging.exception.HandshakeException;
import com.crosshospital.imaging.exception.RoutingFailureException;
import com.crosshospital.imaging.exception.RoutingFailureException.FailureCode;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.support.FakePacsServer;
import com.crosshospital.imaging.support.TestDicomFixtures;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DicomRoutingService} 端到端测试 —— 真 DICOM over TCP，不 mock 协议栈。
 *
 * <p>测试替身 {@link FakePacsServer} 是**真的** dcm4che SCP：它监听 loopback 端口、
 * 接受 A-ASSOCIATE-RQ、对 STUDY 级 C-FIND-RQ 逐条回 Pending 响应再回终态 Cancel。
 * 被测的 {@code DicomRoutingService} 用真实 {@code Association.cfind} 消费这些 PDU。
 * 因此这里断言的是完整 DICOM 上层链路的行为，而不是内部方法调用序列。
 *
 * <p>覆盖：
 * <ol>
 *   <li><b>成功路径</b> — 5 个 StudyInstanceUID 全部取回，字段逐个核对</li>
 *   <li><b>零命中</b> — 外院无该病人影像 → 返回空列表，**不抛异常**</li>
 *   <li><b>失败路径 ×3</b> — 关联被拒 / 查询超时 / 准入表无此节点</li>
 *   <li><b>过滤路径</b> — 按 accessionNumber / studyUid 精确命中</li>
 *   <li><b>幂等锚点</b> — 外院 StudyInstanceUID 原样透传，不重命名</li>
 * </ol>
 *
 * <p>所有 UID 取自自造示例根 {@code 1.2.826.0.1.3680043.10.9999}（见
 * {@link TestDicomFixtures}），不含任何可回溯到真实患者的标识。
 */
public class DicomRoutingServiceTest {

    private static final String PEER_AET = "REMOTE_PACS";
    private static final String LOCAL_AET = "IMAGER";
    private static final String PATIENT_MPI = "MPI-0000123";
    private static final String UID_ROOT = TestDicomFixtures.EXAMPLE_UID_ROOT;

    /** 外院侧 5 个 study 的 UID（与 fake PACS 数据一一对应）。 */
    private static final String[] STUDY_UIDS = {
            UID_ROOT + ".st.1", UID_ROOT + ".st.2", UID_ROOT + ".st.3",
            UID_ROOT + ".st.4", UID_ROOT + ".st.5"
    };
    private static final String[] MODALITIES = {"CT", "MR", "CT", "DX", "US"};
    private static final int[] INSTANCE_COUNTS = {320, 150, 88, 2, 640};

    /** 正常应答的外院 PACS（全程复用，避免用例间端口抖动）。 */
    private static FakePacsServer normalPacs;
    /** 接受关联但永不回 C-FIND 响应的外院 PACS。 */
    private static FakePacsServer silentPacs;

    private PeerRepository peerRepository;
    private DicomConfigProps dicomProps;
    private RoutingConfigProps routingProps;
    private DicomRoutingService service;

    @BeforeAll
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    static void startExternalPacs() throws IOException, GeneralSecurityException {
        normalPacs = new FakePacsServer(PEER_AET, 0, fiveStudies(),
                FakePacsServer.NORMAL, 0L).start();
        silentPacs = new FakePacsServer("SILENT_PACS", 0, fiveStudies(),
                FakePacsServer.SILENT, 0L).start();
    }

    @AfterAll
    static void stopExternalPacs() {
        if (normalPacs != null) normalPacs.close();
        if (silentPacs != null) silentPacs.close();
    }

    private static List<Attributes> fiveStudies() {
        List<Attributes> studies = new ArrayList<>();
        for (int i = 0; i < STUDY_UIDS.length; i++) {
            studies.add(FakePacsServer.studyOf(
                    PATIENT_MPI,
                    "ACC-" + (20260101 + i),
                    STUDY_UIDS[i],
                    MODALITIES[i],
                    "2026010" + (i + 1),
                    INSTANCE_COUNTS[i]));
        }
        return studies;
    }

    @BeforeEach
    void setUp() {
        peerRepository = mock(PeerRepository.class);
        dicomProps = new DicomConfigProps();
        dicomProps.setLocalAet(LOCAL_AET);
        dicomProps.setLocalHost("127.0.0.1");
        dicomProps.setRemoteConnectTimeoutMs(5000);

        routingProps = new RoutingConfigProps();
        routingProps.setQueryTimeoutMs(10000);

        service = new DicomRoutingService(peerRepository, dicomProps, routingProps);
    }

    /** 把当前被测的 fake PACS 端口登记进准入表 mock。 */
    private void givenPeer(FakePacsServer server, String aet) {
        Peer peer = new Peer();
        peer.setPeerAet(aet);
        peer.setHost("127.0.0.1");
        peer.setPort(server.getPort());
        peer.setTlsMode("none");
        peer.setPresentContexts("[\"" + FakePacsServer.STUDY_ROOT_FIND + "\"]");
        peer.setTransferSyntaxes("[\"1.2.840.10008.1.2.1\", \"1.2.840.10008.1.2\"]");
        peer.setBlacklistAets("[]");
        when(peerRepository.findByPeerAet(aet)).thenReturn(Optional.of(peer));
    }

    // ==================================================================
    // 成功路径
    // ==================================================================

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("成功：一次 C-FIND 取回外院 5 个 StudyInstanceUID，字段逐个核对")
    void queryStudiesReturnsFiveRefs() {
        givenPeer(normalPacs, PEER_AET);
        // 替身在 @BeforeAll 起、全类共用，计数器是累计值 —— 取增量而非绝对值
        int associationsBefore = normalPacs.getAcceptedAssociations();
        int cfindBefore = normalPacs.getCfindRequests();

        List<StudyRef> refs = service.queryStudies(PEER_AET, PATIENT_MPI, null);

        assertEquals(5, refs.size(), "外院 5 个 study 应全部命中");
        assertEquals(1, normalPacs.getAcceptedAssociations() - associationsBefore,
                "应只建立 1 次关联（5 个 study 在同一关联内一次查回，不按 study 循环建连）");
        assertEquals(1, normalPacs.getCfindRequests() - cfindBefore, "应只发 1 条 C-FIND-RQ");

        for (int i = 0; i < STUDY_UIDS.length; i++) {
            StudyRef ref = refs.get(i);
            assertEquals(STUDY_UIDS[i], ref.getStudyUid(), "第 " + i + " 条 StudyInstanceUID");
            assertEquals(PATIENT_MPI, ref.getPatientId(), "第 " + i + " 条 PatientID");
            assertEquals(MODALITIES[i], ref.getModality(), "第 " + i + " 条 Modality");
            assertEquals(INSTANCE_COUNTS[i], ref.getNumberOfInstances(), "第 " + i + " 条 instance 数");
            assertEquals("ACC-" + (20260101 + i), ref.getAccessionNumber(), "第 " + i + " 条 AccessionNumber");
            assertEquals("2026010" + (i + 1), ref.getStudyDate(), "第 " + i + " 条 StudyDate");
        }
    }

    @Test
    @DisplayName("成功：外院 StudyInstanceUID 原样透传，不重命名（实体锚点不变量）")
    void studyUidIsPassedThroughVerbatim() {
        givenPeer(normalPacs, PEER_AET);

        List<StudyRef> refs = service.queryStudies(PEER_AET, PATIENT_MPI, null);

        assertEquals(List.of(STUDY_UIDS[0], STUDY_UIDS[1], STUDY_UIDS[2], STUDY_UIDS[3], STUDY_UIDS[4]),
                refs.stream().map(StudyRef::getStudyUid).toList(),
                "UID 必须逐字透传，后续 C-MOVE 靠它定位同一份影像");
    }

    @Test
    @DisplayName("成功：StudyRef 带实际协商的查询 SOP Class 与传输语法")
    void studyRefCarriesNegotiatedSopClassAndTransferSyntax() {
        givenPeer(normalPacs, PEER_AET);

        List<StudyRef> refs = service.queryStudies(PEER_AET, PATIENT_MPI, null);

        StudyRef first = refs.get(0);
        assertEquals(FakePacsServer.STUDY_ROOT_FIND, first.getSopClassUid(),
                "应记录实际使用的 Study Root FIND SOP Class");
        assertEquals("1.2.840.10008.1.2.1", first.getTransferSyntax(),
                "两端共有的 Explicit VR Little Endian 应胜出");
        assertTrue(first.hasRetrievableUid());
    }

    // ==================================================================
    // 过滤路径
    // ==================================================================

    @Test
    @DisplayName("过滤：按 AccessionNumber 精确命中 1 条")
    void queryByAccessionNumberNarrowsToOneStudy() {
        givenPeer(normalPacs, PEER_AET);

        List<StudyRef> refs = service.queryStudies(PEER_AET, PATIENT_MPI, "ACC-20260103");

        assertEquals(1, refs.size());
        assertEquals(STUDY_UIDS[2], refs.get(0).getStudyUid());
        assertEquals("ACC-20260103", refs.get(0).getAccessionNumber());
    }

    @Test
    @DisplayName("过滤：按外院 StudyInstanceUID 精确命中 1 条（四参重载）")
    void queryByStudyUidNarrowsToOneStudy() {
        givenPeer(normalPacs, PEER_AET);

        List<StudyRef> refs = service.queryStudies(PEER_AET, PATIENT_MPI, null, STUDY_UIDS[4]);

        assertEquals(1, refs.size());
        assertEquals(STUDY_UIDS[4], refs.get(0).getStudyUid());
        assertEquals("US", refs.get(0).getModality());
    }

    @Test
    @DisplayName("零命中：外院无该患者影像 → 返回空列表，不抛异常（与查询失败严格区分）")
    void noMatchReturnsEmptyListNotException() {
        givenPeer(normalPacs, PEER_AET);

        List<StudyRef> refs = service.queryStudies(PEER_AET, "MPI-NOT-EXIST", null);

        assertNotNull(refs);
        assertTrue(refs.isEmpty(),
                "零命中是外院的业务事实，必须是空列表而非异常 —— 否则上游会误读为链路故障");
    }

    // ==================================================================
    // 失败路径 ×3
    // ==================================================================

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("失败 association_rejected：外院 PACS 回 A-ASSOCIATE-RJ（AE Title 未授权）")
    void associationRejectedWhenPeerRefuses() throws IOException, GeneralSecurityException {
        try (FakePacsServer rejecting = new FakePacsServer(
                "STRICT_PACS", 0, fiveStudies(), FakePacsServer.REJECT_ASSOCIATION, 0L).start()) {
            givenPeer(rejecting, "STRICT_PACS");

            RoutingFailureException ex = assertThrows(RoutingFailureException.class,
                    () -> service.queryStudies("STRICT_PACS", PATIENT_MPI, null));

            assertEquals(FailureCode.ASSOCIATION_REJECTED, ex.getFailureCode());
            assertFalse(ex.isRetryable(), "AE Title 未授权是配置型问题，重试没有意义");
            assertEquals("association_rejected", ex.getDbFailureCode(), "DB failure_code 用小写下划线");
            assertTrue(ex.getMessage().contains("STRICT_PACS"), "失败消息应带出 peer AET：" + ex.getMessage());
            assertEquals(1, rejecting.getRejectedAssociations(), "对端应确实收到了 A-ASSOCIATE-RQ");
            assertEquals(0, rejecting.getAcceptedAssociations());
            assertEquals(0, rejecting.getCfindRequests(), "关联被拒就不该发 C-FIND-RQ");
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("失败 query_timeout：外院接受关联但不应答，到 query-timeout-ms 放弃")
    void queryTimeoutWhenPeerStaysSilent() {
        givenPeer(silentPacs, "SILENT_PACS");
        routingProps.setQueryTimeoutMs(600);   // 缩短预算让用例快跑

        long start = System.currentTimeMillis();
        RoutingFailureException ex = assertThrows(RoutingFailureException.class,
                () -> service.queryStudies("SILENT_PACS", PATIENT_MPI, null));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(FailureCode.QUERY_TIMEOUT, ex.getFailureCode());
        assertTrue(ex.isRetryable(), "超时是网络瞬态，应交 task 21 RetryPolicy 重试");
        assertTrue(elapsed < 15_000, "应在超时预算附近放弃，实际 " + elapsed + "ms（未阻塞至默认 30s）");
        assertTrue(elapsed >= 400, "不应早于超时预算放弃，实际 " + elapsed + "ms");
        assertEquals(1, silentPacs.getAcceptedAssociations(), "关联本身应已建立成功");
        assertEquals(1, silentPacs.getCfindRequests(), "C-FIND-RQ 已发出，只是等不到响应");
    }

    @Test
    @DisplayName("失败：准入表无此节点 → HandshakeException(PEER_NOT_FOUND)，零外发调用")
    void peerNotFoundFailsBeforeAnyNetworkCall() {
        when(peerRepository.findByPeerAet("UNKNOWN_PACS")).thenReturn(Optional.empty());

        HandshakeException ex = assertThrows(HandshakeException.class,
                () -> service.queryStudies("UNKNOWN_PACS", PATIENT_MPI, null));

        assertEquals(HandshakeException.FailureCode.PEER_NOT_FOUND, ex.getFailureCode());
        assertFalse(ex.isRetryable(), "节点没登记，重试没有意义");
    }

    // ==================================================================
    // 入参边界
    // ==================================================================

    @Test
    @DisplayName("边界：patient_mpi 为空 / peer_aet 为空 → 参数异常，零外发调用")
    void blankInputsRejectedBeforeNetworkCall() {
        givenPeer(normalPacs, PEER_AET);
        int before = normalPacs.getCfindRequests();

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.queryStudies(PEER_AET, null, null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.queryStudies(PEER_AET, "  ", null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.queryStudies(null, PATIENT_MPI, null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.queryStudies("  ", PATIENT_MPI, null)));

        assertEquals(before, normalPacs.getCfindRequests(), "参数校验必须在建联之前，不能白跑一趟外院");
    }

    @Test
    @DisplayName("边界：外院实例数为空 → numberOfInstances 为 null，不臆造 0")
    void missingInstanceCountStaysNull() {
        Attributes study = FakePacsServer.studyOf(
                "MPI-NO-COUNT", "ACC-EMPTY", UID_ROOT + ".st.nocount", "CT", "20260110", 0);
        study.setNull(Tag.NumberOfStudyRelatedInstances,
                org.dcm4che3.data.VR.IS);   // 模拟外院 PACS 未回该键

        StudyRef ref = service.toStudyRef(study,
                FakePacsServer.STUDY_ROOT_FIND, "1.2.840.10008.1.2.1");

        assertNull(ref.getNumberOfInstances(), "外院没报不等于 0，null 才能让上层区分「未知」与「空片」");
        assertEquals(UID_ROOT + ".st.nocount", ref.getStudyUid());
    }

    @Test
    @DisplayName("边界：查询键把 PatientID 设为匹配键、StudyInstanceUID 设为返回键")
    void queryKeysSeparateMatchKeysFromReturnKeys() {
        Attributes keys = service.buildStudyQueryKeys(PATIENT_MPI, null, null);

        assertAll(
                () -> assertEquals(PATIENT_MPI, keys.getString(Tag.PatientID),
                        "PatientID 必须带值作匹配键"),
                () -> assertTrue(keys.contains(Tag.StudyInstanceUID),
                        "StudyInstanceUID 必须以零长返回键出现"),
                () -> assertFalse(keys.containsValue(Tag.StudyInstanceUID),
                        "StudyInstanceUID 作为返回键时应为零长（不含值），否则会被当成匹配键"),
                () -> assertTrue(keys.contains(Tag.NumberOfStudyRelatedInstances),
                        "instance 数是 task 14/15 C-MOVE 的必需返回键"),
                () -> assertEquals("STUDY", keys.getString(Tag.QueryRetrieveLevel)));
    }

    @Test
    @DisplayName("边界：指定 studyUid 时 StudyInstanceUID 由返回键升格为匹配键")
    void studyUidPromotedToMatchKeyWhenProvided() {
        Attributes keys = service.buildStudyQueryKeys(PATIENT_MPI, null, STUDY_UIDS[1]);

        assertEquals(STUDY_UIDS[1], keys.getString(Tag.StudyInstanceUID));
    }

    // ==================================================================
    // 值对象
    // ==================================================================

    @Test
    @DisplayName("StudyRef 是不可变值对象：equals/hashCode 按全部字段")
    void studyRefValueSemantics() {
        StudyRef a = new StudyRef(STUDY_UIDS[0], FakePacsServer.STUDY_ROOT_FIND,
                "1.2.840.10008.1.2.1", PATIENT_MPI, "ANON^PATIENT", "ACC-20260101",
                "CT", "20260101", 320);
        StudyRef b = new StudyRef(STUDY_UIDS[0], FakePacsServer.STUDY_ROOT_FIND,
                "1.2.840.10008.1.2.1", PATIENT_MPI, "ANON^PATIENT", "ACC-20260101",
                "CT", "20260101", 320);
        StudyRef c = new StudyRef(STUDY_UIDS[1], FakePacsServer.STUDY_ROOT_FIND,
                "1.2.840.10008.1.2.1", PATIENT_MPI, "ANON^PATIENT", "ACC-20260101",
                "CT", "20260101", 320);

        assertAll(
                () -> assertEquals(a, b),
                () -> assertEquals(a.hashCode(), b.hashCode()),
                () -> assertFalse(a.equals(c), "StudyInstanceUID 不同即视为不同记录"));
    }
}