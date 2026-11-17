package com.crosshospital.imaging.tools;

import com.crosshospital.imaging.service.routing.MoveDestinationStoreScp;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Commands;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.Dimse;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.AbstractDicomService;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Task 39 证据环境用「外院 PACS」：一个真 dcm4che SCP，同一个端口上同时实现
 * STUDY Root C-FIND 与 STUDY Root C-MOVE。
 *
 * <p><b>为什么单独写一个</b>：生产运行期的 {@code FetchTaskRunner} 对同一次调阅的
 * querying / pulling 两步都按准入表里<b>同一个 peerAet + 同一个 host:port</b> 建关联；
 * 仓库里现成的 {@code FakePacsServer}（只会 C-FIND）与 {@code FakeMoveScp}（只会 C-MOVE）
 * 是两个独立端口的独立 Device，单元测试里靠 mock 仓库逐次换端口绕过去了，
 * 真实运行期绕不过去。所以这里把两个 DIMSE 服务装进同一个 Device / AE。
 *
 * <p><b>不是 mock</b>：TCP → A-ASSOCIATE-RQ/AC → C-FIND-RQ / C-MOVE-RQ →
 * Pending/终态 RSP → A-RELEASE-RQ/RP 全部由 dcm4che 真实协议栈收发。
 * C-MOVE 的 sub-op 走「直接写本端 C-STORE-SCP 的 InstanceSink」快路径：
 * 字节来自 {@code src/test/fixtures/samples-dicom/} 里 3 个真实 DICOM Part-10 文件，
 * 之后 C-STORE 那一步再由生产代码把它们推给本院 PACS 的 SCP。
 *
 * <p>自造示例 UID 根，无任何可回溯到真实患者的标识。本类只在 test classpath。
 */
public final class EvidenceCombinedPacs implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EvidenceCombinedPacs.class);

    public static final String STUDY_ROOT_FIND = "1.2.840.10008.5.1.4.1.2.2.1";
    public static final String STUDY_ROOT_MOVE = "1.2.840.10008.5.1.4.1.2.2.2";
    public static final String PATIENT_ROOT_MOVE = "1.2.840.10008.5.1.4.1.2.1.2";
    public static final String CT_STORAGE = "1.2.840.10008.5.1.4.1.1.2";
    private static final String EXPLICIT_VR_LE = "1.2.840.10008.1.2.1";

    /**
     * 每个 DIMSE 步的人为响应延迟（毫秒）。真实外院 PACS 的 C-FIND / C-MOVE
     * 本来就是百毫秒级；这里固定一个可预期的值，让采集脚本能在 curl 轮询里
     * 真的拍到 querying / pulling 两个中间态，而不是只剩 queued 和 notified。
     */
    private static final long STEP_DELAY_MS = 600L;

    private final String aet;
    private final int port;
    private final Attributes study;
    private final List<byte[]> instances;
    private final List<String> sopInstanceUids;
    private final MoveDestinationStoreScp.InstanceSink sink;
    private final String moveDestAet;

    private Device device;
    private ExecutorService executor;

    public EvidenceCombinedPacs(String aet, int port, Attributes study,
                                 List<byte[]> instances, List<String> sopInstanceUids,
                                 MoveDestinationStoreScp.InstanceSink sink,
                                 String moveDestAet) {
        this.aet = aet;
        this.port = port > 0 ? port : pickFreePort();
        this.study = study;
        this.instances = new ArrayList<>(instances);
        this.sopInstanceUids = new ArrayList<>(sopInstanceUids);
        this.sink = sink;
        this.moveDestAet = moveDestAet;
    }

    private static int pickFreePort() {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("无法分配证据用 DICOM 端口", e);
        }
    }

    /** 从磁盘上的真实 Part-10 fixture 读 3 个对象（不合成、不造假字节）。 */
    public static List<byte[]> loadDicomFixtures(Path dir) throws IOException {
        List<byte[]> out = new ArrayList<>();
        for (String name : List.of("instance-01.dcm", "instance-02.dcm", "instance-03.dcm")) {
            out.add(Files.readAllBytes(dir.resolve(name)));
        }
        return out;
    }

    public static Attributes studyOf(String patientId, String accession, String studyUid,
                                     String modality, String studyDate, int instanceCount) {
        Attributes a = new Attributes();
        a.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 100");
        a.setString(Tag.PatientID, VR.LO, patientId);
        a.setString(Tag.PatientName, VR.PN, "ANON^PATIENT");
        a.setString(Tag.AccessionNumber, VR.SH, accession);
        a.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
        a.setString(Tag.StudyDate, VR.DA, studyDate);
        a.setString(Tag.StudyID, VR.SH, "ST" + studyDate);
        a.setString(Tag.StudyDescription, VR.LO, "EXTERNAL STUDY " + modality);
        a.setString(Tag.SeriesDescription, VR.LO, "EXTERNAL SERIES");
        a.setString(Tag.Modality, VR.CS, modality);
        a.setString(Tag.NumberOfStudyRelatedInstances, VR.IS, Integer.toString(instanceCount));
        return a;
    }

    public EvidenceCombinedPacs start() throws IOException, GeneralSecurityException {
        device = new Device("evidence-pacs-" + aet);
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "evidence-pacs-executor");
            t.setDaemon(true);
            return t;
        });
        device.setExecutor(executor);
        device.setScheduledExecutor(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "evidence-pacs-scheduler");
            t.setDaemon(true);
            return t;
        }));

        Connection conn = new Connection();
        conn.setHostname("127.0.0.1");
        conn.setPort(port);
        conn.setProtocol(Connection.Protocol.DICOM);
        conn.setBacklog(50);
        device.addConnection(conn);

        DicomServiceRegistry registry = new DicomServiceRegistry();
        registry.addDicomService(new FindScp());
        registry.addDicomService(new MoveScp());
        device.setDimseRQHandler(registry);

        org.dcm4che3.net.ApplicationEntity ae =
                new org.dcm4che3.net.ApplicationEntity(aet);
        device.addApplicationEntity(ae);
        ae.addConnection(conn);
        for (String sop : List.of(STUDY_ROOT_FIND, STUDY_ROOT_MOVE, PATIENT_ROOT_MOVE)) {
            ae.addTransferCapability(new TransferCapability(null, sop,
                    TransferCapability.Role.SCP,
                    UID.ExplicitVRLittleEndian, UID.ImplicitVRLittleEndian));
        }
        device.bindConnections();
        LOG.info("[evidence] 外院 PACS 已监听 127.0.0.1:{} 同时提供 C-FIND / C-MOVE", getPort());
        return this;
    }

    public int getPort() {
        return device == null ? port : device.listConnections().get(0).getPort();
    }

    @Override
    public void close() {
        if (device != null) {
            try {
                device.unbindConnections();
            } catch (Exception ignored) {
                // 进程退出一并回收
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(STEP_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** STUDY 级 C-FIND：按请求键过滤，逐条 Pending，终态 Cancel。 */
    private final class FindScp extends AbstractDicomService {
        FindScp() {
            super(STUDY_ROOT_FIND);
        }

        @Override
        protected void onDimseRQ(Association as, PresentationContext pc, Dimse dimse,
                                 Attributes rq, Attributes keys) throws IOException {
            if (dimse != Dimse.C_FIND_RQ) {
                as.writeDimseRSP(pc, Commands.mkCFindRSP(rq, Status.ProcessingFailure));
                return;
            }
            pause();
            if (matches(keys)) {
                as.writeDimseRSP(pc, Commands.mkCFindRSP(rq, Status.Pending), study);
            }
            as.writeDimseRSP(pc, Commands.mkCFindRSP(rq, Status.Cancel));
            LOG.info("[evidence] C-FIND 已应答 1 个 study");
        }

        private boolean matches(Attributes keys) {
            if (keys == null) {
                return true;
            }
            return equalsOrWildcard(keys.getString(Tag.PatientID), study.getString(Tag.PatientID))
                    && equalsOrWildcard(keys.getString(Tag.AccessionNumber),
                    study.getString(Tag.AccessionNumber))
                    && equalsOrWildcard(keys.getString(Tag.StudyInstanceUID),
                    study.getString(Tag.StudyInstanceUID));
        }

        private boolean equalsOrWildcard(String requested, String actual) {
            return requested == null || requested.isBlank() || requested.equals(actual);
        }
    }

    /** STUDY 级 C-MOVE：把真实 Part-10 字节写进本端 C-STORE-SCP 的 sink，再回终态。 */
    private final class MoveScp extends AbstractDicomService {
        MoveScp() {
            super(STUDY_ROOT_MOVE, PATIENT_ROOT_MOVE);
        }

        @Override
        protected void onDimseRQ(Association as, PresentationContext pc, Dimse dimse,
                                 Attributes rq, Attributes keys) throws IOException {
            if (dimse != Dimse.C_MOVE_RQ) {
                as.writeDimseRSP(pc, Commands.mkCMoveRSP(rq, Status.ProcessingFailure));
                return;
            }
            String moveDest = rq == null ? null : rq.getString(Tag.MoveDestination);
            if (moveDest == null || !moveDest.equalsIgnoreCase(moveDestAet)) {
                as.writeDimseRSP(pc, Commands.mkCMoveRSP(rq, Status.MoveDestinationUnknown));
                return;
            }

            pause();
            int completed = 0;
            int failed = 0;
            for (int i = 0; i < instances.size(); i++) {
                boolean ok = sink.onInstance(CT_STORAGE, sopInstanceUids.get(i),
                        EXPLICIT_VR_LE, instances.get(i));
                if (ok) {
                    completed++;
                } else {
                    failed++;
                }
                as.writeDimseRSP(pc, pending(rq, instances.size() - completed - failed,
                        completed, failed));
            }
            Attributes terminal = new Attributes(Commands.mkCMoveRSP(rq,
                    failed == 0 ? Status.Success : Status.OneOrMoreFailures));
            terminal.setInt(Tag.NumberOfRemainingSuboperations, VR.US, 0);
            terminal.setInt(Tag.NumberOfCompletedSuboperations, VR.US, completed);
            terminal.setInt(Tag.NumberOfFailedSuboperations, VR.US, failed);
            terminal.setInt(Tag.NumberOfWarningSuboperations, VR.US, 0);
            as.writeDimseRSP(pc, terminal);
            LOG.info("[evidence] C-MOVE 已完成 {} 个 sub-op（失败 {}）", completed, failed);
        }

        private Attributes pending(Attributes rq, int remaining, int completed, int failed) {
            Attributes pending = new Attributes(Commands.mkCMoveRSP(rq, Status.Pending));
            pending.setInt(Tag.NumberOfRemainingSuboperations, VR.US, remaining);
            pending.setInt(Tag.NumberOfCompletedSuboperations, VR.US, completed);
            pending.setInt(Tag.NumberOfFailedSuboperations, VR.US, failed);
            pending.setInt(Tag.NumberOfWarningSuboperations, VR.US, 0);
            return pending;
        }
    }
}
