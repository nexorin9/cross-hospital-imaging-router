package com.crosshospital.imaging.support;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomEncodingOptions;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Commands;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.Dimse;
import org.dcm4che3.net.DimseRSP;
import org.dcm4che3.net.PDVOutputStream;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.AbstractDicomService;
import org.dcm4che3.net.service.DicomServiceRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用「外院 C-MOVE-SCP」替身 —— 真 dcm4che 5.x SCP，回 C-MOVE-RSP，可配置行为。
 *
 * <h2>可配置行为（{@link Mode}）</h2>
 * <ul>
 *   <li>{@link Mode#NORMAL} —— 全 Success，sub-op 计数报全 completed</li>
 *   <li>{@link Mode#ALL_SUBOP_FAIL} —— 所有 sub-op 失败（仅在命令阶段标记，不真推字节）</li>
 *   <li>{@link Mode#MOVE_DEST_UNKNOWN} —— MoveDestination 不识别，0xA801</li>
 *   <li>{@link Mode#SILENT} —— 接受关联但不应答任何 C-MOVE 帧，逼 SCU 侧 rspTimeout 到点</li>
 * </ul>
 *
 * <p>测试主路径推荐{@link #setInstanceSink}，替身会直接调本端 sink 的 {@code onInstance}；
 * 不注入时走真 dcm4che SCU 关联推字节（task 35 集成用，当前单测不依赖）。
 */
public final class FakeMoveScp implements AutoCloseable {

    /** 本端 MoveDestinationStoreScp 的 AET 与协商地址。可被 setMoveDestination 调整。 */
    private volatile String moveDestAet;
    private volatile String moveDestHost;
    private volatile int moveDestPort;
    private final String aet;
    private final int port;
    private final Map<String, List<DicomInstance>> studyInstances = new HashMap<>();
    private final Mode mode;
    private final AtomicInteger acceptedAssociations = new AtomicInteger();
    private final AtomicInteger cmoveRequests = new AtomicInteger();
    private final AtomicInteger storesForwarded = new AtomicInteger();
    private final AtomicInteger storesAccepted = new AtomicInteger();
    private final AtomicInteger storesRejected = new AtomicInteger();
    private final AtomicInteger cmoveFailedAtRsp = new AtomicInteger();

    private Device device;
    private ExecutorService scuExecutor;

    /** 测试注入：本端 InstanceSink（替身直接写它，避免嵌套 C-STORE 字节流） */
    private com.crosshospital.imaging.service.routing
            .MoveDestinationStoreScp.InstanceSink registeredSink;

    public enum Mode { NORMAL, ALL_SUBOP_FAIL, MOVE_DEST_UNKNOWN, SILENT }

    private static final String STUDY_ROOT_MOVE = "1.2.840.10008.5.1.4.1.2.2.2";
    private static final String CT_STORAGE = "1.2.840.10008.5.1.4.1.1.2";

    public FakeMoveScp(String aet, int port,
                       String moveDestAet, String moveDestHost, int moveDestPort,
                       Mode mode) {
        this.aet = aet;
        this.port = port > 0 ? port : pickFreePort();
        this.moveDestAet = moveDestAet;
        this.moveDestHost = moveDestHost;
        this.moveDestPort = moveDestPort;
        this.mode = mode;
    }

    private static int pickFreePort() {
        try (java.net.ServerSocket probe =
                     new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("无法分配测试端口", e);
        }
    }

    public void registerStudy(String studyUid, List<DicomInstance> instances) {
        studyInstances.put(studyUid, new ArrayList<>(instances));
    }

    public void setMoveDestination(String aet, String host, int port) {
        this.moveDestAet = aet;
        this.moveDestHost = host;
        this.moveDestPort = port;
    }

    public void setInstanceSink(com.crosshospital.imaging.service.routing
                                    .MoveDestinationStoreScp.InstanceSink sink) {
        this.registeredSink = sink;
    }

    public FakeMoveScp start() throws IOException, java.security.GeneralSecurityException {
        device = new Device("fake-move-scp-" + aet);
        scuExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-move-scu-" + aet);
            t.setDaemon(true);
            return t;
        });
        device.setExecutor(scuExecutor);
        device.setScheduledExecutor(
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "fake-move-scheduler-" + aet);
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
        registry.addDicomService(new MoveScp(this));
        device.setDimseRQHandler(registry);

        ApplicationEntity ae = new ApplicationEntity(aet);
        device.addApplicationEntity(ae);
        ae.addConnection(conn);

        ae.addTransferCapability(new TransferCapability(
                null,
                STUDY_ROOT_MOVE,
                TransferCapability.Role.SCP,
                UID.ExplicitVRLittleEndian,
                UID.ImplicitVRLittleEndian));

        device.bindConnections();
        return this;
    }

    public int getPort() {
        if (device == null) return port;
        return device.listConnections().get(0).getPort();
    }

    public int getAcceptedAssociations() { return acceptedAssociations.get(); }
    public int getCmoveRequests() { return cmoveRequests.get(); }
    public int getStoresForwarded() { return storesForwarded.get(); }
    public int getStoresAccepted() { return storesAccepted.get(); }
    public int getStoresRejected() { return storesRejected.get(); }
    public int getCmoveFailedAtRsp() { return cmoveFailedAtRsp.get(); }

    @Override
    public void close() {
        if (device != null) {
            try { device.unbindConnections(); } catch (Exception ignored) { /* 容许 */ }
        }
        if (scuExecutor != null) {
            scuExecutor.shutdownNow();
        }
    }

    public static final class DicomInstance {
        public final String studyUid;
        public final String sopInstanceUid;
        public final String sopClassUid;
        public final String patientId;

        public DicomInstance(String studyUid, String sopInstanceUid,
                             String sopClassUid, String patientId) {
            this.studyUid = studyUid;
            this.sopInstanceUid = sopInstanceUid;
            this.sopClassUid = sopClassUid;
            this.patientId = patientId;
        }
    }

    private final class MoveScp extends AbstractDicomService {

        private final FakeMoveScp outer;

        MoveScp(FakeMoveScp outer) {
            super(STUDY_ROOT_MOVE);
            this.outer = outer;
        }

        @Override
        protected void onDimseRQ(Association as, PresentationContext pc, Dimse dimse,
                                 Attributes rq, Attributes rqKeys) throws IOException {
            acceptedAssociations.incrementAndGet();
            if (dimse != Dimse.C_MOVE_RQ) {
                as.writeDimseRSP(pc, Commands.mkCMoveRSP(rq, Status.ProcessingFailure));
                return;
            }
            cmoveRequests.incrementAndGet();

            String moveDest = rq == null ? null : rq.getString(Tag.MoveDestination);
            String studyUid = rqKeys == null ? null : rqKeys.getString(Tag.StudyInstanceUID);

            if (outer.mode == Mode.SILENT) {
                return;
            }
            if (outer.mode == Mode.MOVE_DEST_UNKNOWN
                    || moveDest == null
                    || !moveDest.equalsIgnoreCase(outer.moveDestAet)) {
                as.writeDimseRSP(pc, Commands.mkCMoveRSP(rq, Status.MoveDestinationUnknown));
                return;
            }
            List<DicomInstance> instances = outer.studyInstances.getOrDefault(studyUid,
                    List.of());

            int total = instances.size();
            writeCMovePending(as, pc, rq, total, 0, 0, 0);

            int completed = 0;
            int failed = 0;
            for (DicomInstance inst : instances) {
                if (outer.mode == Mode.ALL_SUBOP_FAIL) {
                    failed++;
                    writeCMovePending(as, pc, rq, total - completed - failed,
                            completed, failed, 0);
                    continue;
                }
                storesForwarded.incrementAndGet();
                boolean ok = pushInstance(inst);
                if (ok) {
                    storesAccepted.incrementAndGet();
                    completed++;
                } else {
                    storesRejected.incrementAndGet();
                    failed++;
                }
                writeCMovePending(as, pc, rq, total - completed - failed,
                        completed, failed, 0);
            }
            int terminalStatus = (failed == 0)
                    ? Status.Success
                    : Status.OneOrMoreFailures;
            if (terminalStatus != Status.Success) {
                cmoveFailedAtRsp.incrementAndGet();
            }
            Attributes terminal = new Attributes(Commands.mkCMoveRSP(rq, terminalStatus));
            terminal.setInt(Tag.NumberOfRemainingSuboperations, VR.US, 0);
            terminal.setInt(Tag.NumberOfCompletedSuboperations, VR.US, completed);
            terminal.setInt(Tag.NumberOfFailedSuboperations, VR.US, failed);
            terminal.setInt(Tag.NumberOfWarningSuboperations, VR.US, 0);
            as.writeDimseRSP(pc, terminal);
        }
    }

    private static void writeCMovePending(Association as, PresentationContext pc,
                                          Attributes rq, int remaining, int completed,
                                          int failed, int warning) throws IOException {
        Attributes pending = new Attributes(Commands.mkCMoveRSP(rq, Status.Pending));
        pending.setInt(Tag.NumberOfRemainingSuboperations, VR.US, remaining);
        pending.setInt(Tag.NumberOfCompletedSuboperations, VR.US, completed);
        pending.setInt(Tag.NumberOfFailedSuboperations, VR.US, failed);
        pending.setInt(Tag.NumberOfWarningSuboperations, VR.US, warning);
        as.writeDimseRSP(pc, pending);
    }

    /**
     * 单测主路径：替身在「推 C-STORE」时直接把字节写到已注入的本地 sink。
     * 不注入时走真 dcm4che SCU 关联推字节路径（task 35 集成用）。
     */
    private boolean pushInstance(DicomInstance inst) {
        if (registeredSink != null) {
            byte[] bytes = buildPart10Bytes(inst.studyUid, inst.sopInstanceUid,
                    inst.sopClassUid, inst.patientId);
            boolean ok = registeredSink.onInstance(inst.sopClassUid, inst.sopInstanceUid,
                    "1.2.840.10008.1.2.1", bytes);
            return ok;
        }
        return pushInstanceRawScu(inst);
    }

    /** 保留原始 SCU 关联路径，给 task 35 集成测试用。 */
    @SuppressWarnings("unused")
    private boolean pushInstanceRawScu(DicomInstance inst) {
        Device scuDev = new Device("fake-move-scu-" + aet);
        ExecutorService es = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fake-cstore-scu-" + aet);
            t.setDaemon(true);
            return t;
        });
        try {
            scuDev.setExecutor(es);
            scuDev.setScheduledExecutor(Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fake-cstore-scheduler-" + aet);
                t.setDaemon(true);
                return t;
            }));
            Connection localConn = new Connection();
            localConn.setHostname("127.0.0.1");
            localConn.setPort(0x7FFE);
            localConn.setProtocol(Connection.Protocol.DICOM);
            localConn.setTlsCipherSuites(new String[0]);
            localConn.setTlsProtocols(new String[0]);

            Connection remoteConn = new Connection();
            remoteConn.setHostname(moveDestHost);
            remoteConn.setPort(moveDestPort);
            remoteConn.setProtocol(Connection.Protocol.DICOM);
            remoteConn.setTlsCipherSuites(new String[0]);
            remoteConn.setTlsProtocols(new String[0]);

            AAssociateRQ rq = new AAssociateRQ();
            rq.setCalledAET(moveDestAet);
            rq.setCallingAET(aet);
            rq.setApplicationContext("1.2.840.10008.3.1.1.1");
            rq.setMaxPDULength(16384);
            rq.addPresentationContextFor(inst.sopClassUid, UID.ExplicitVRLittleEndian);

            scuDev.addConnection(localConn);
            ApplicationEntity ae = new ApplicationEntity(aet);
            scuDev.addApplicationEntity(ae);
            ae.addConnection(localConn);

            Association storeAs = ae.connect(localConn, remoteConn, rq);
            try {
                DimseRSP rsp = storeAs.cstore(inst.sopClassUid, inst.sopInstanceUid,
                        0, new DatasetDataWriter(inst),
                        UID.ExplicitVRLittleEndian);
                int status = -1;
                while (rsp.next()) {
                    int s = rsp.getCommand().getInt(Tag.Status, -1);
                    if (!Status.isPending(s)) {
                        status = s;
                        break;
                    }
                }
                return status == Status.Success;
            } finally {
                if (storeAs.isReadyForDataTransfer()) {
                    try { storeAs.release(); } catch (Exception e) {
                        try { storeAs.abort(); } catch (Exception ignored) { /* 关不掉不影响 */ }
                    }
                }
                scuDev.unbindConnections();
                es.shutdownNow();
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(FakeMoveScp.class).error(
                    "doStore 失败 inst={} dest={}:{}",
                    inst.sopInstanceUid, moveDestHost, moveDestPort, e);
            return false;
        }
    }

    /** DataWriter：把 dataset 写为纯 dataset（无 preamble / DICM / file-meta）。 */
    private static final class DatasetDataWriter implements org.dcm4che3.net.DataWriter {
        private final DicomInstance inst;
        DatasetDataWriter(DicomInstance inst) { this.inst = inst; }

        @Override
        public void writeTo(PDVOutputStream out, String tsuid) throws IOException {
            Attributes ds = new Attributes();
            ds.setString(Tag.SOPInstanceUID, VR.UI, inst.sopInstanceUid);
            ds.setString(Tag.SOPClassUID, VR.UI, inst.sopClassUid);
            ds.setString(Tag.PatientID, VR.LO, inst.patientId);
            ds.setString(Tag.StudyInstanceUID, VR.UI, inst.studyUid);
            DicomOutputStream dos =
                    new DicomOutputStream((OutputStream) out, tsuid);
            dos.setEncodingOptions(DicomEncodingOptions.DEFAULT);
            dos.writeDataset(null, ds);
            dos.flush();
        }
    }

    /** 构造 DICOM Part-10 字节（含 128 字节前导 + DICM + file-meta + dataset），用于 sink mock 路径。 */
    public static byte[] buildPart10Bytes(String studyUid, String sopInstanceUid,
                                          String sopClassUid, String patientId) {
        Attributes fileMeta = new Attributes();
        fileMeta.setString(Tag.MediaStorageSOPInstanceUID, VR.UI, sopInstanceUid);
        fileMeta.setString(Tag.MediaStorageSOPClassUID, VR.UI, sopClassUid);
        fileMeta.setString(Tag.TransferSyntaxUID, VR.UI, UID.ExplicitVRLittleEndian);
        fileMeta.setString(Tag.ImplementationClassUID, VR.UI, "1.2.3.4.5.6.7.8");
        Attributes ds = new Attributes();
        ds.setString(Tag.SOPInstanceUID, VR.UI, sopInstanceUid);
        ds.setString(Tag.SOPClassUID, VR.UI, sopClassUid);
        ds.setString(Tag.PatientID, VR.LO, patientId);
        ds.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
        ds.setString(Tag.SeriesInstanceUID, VR.UI, studyUid + ".series");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DicomOutputStream dos =
                     new DicomOutputStream((OutputStream) baos, UID.ExplicitVRLittleEndian)) {
            dos.setEncodingOptions(DicomEncodingOptions.DEFAULT);
            dos.writeDataset(fileMeta, ds);
            dos.flush();
        } catch (IOException e) {
            throw new IllegalStateException("构造 DICOM 字节失败", e);
        }
        return baos.toByteArray();
    }
}
