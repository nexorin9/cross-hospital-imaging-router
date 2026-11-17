package com.crosshospital.imaging.support;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.AssociationHandler;
import org.dcm4che3.net.Commands;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.Dimse;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.pdu.AAssociateRJ;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.AbstractDicomService;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用「本院 PACS」替身 —— 真 dcm4che 5.x C-STORE-SCP，接收我们写入的 DICOM instance。
 *
 * <p>它扮演的是<b>调阅的终点</b>：外院的片子经 C-MOVE 拉回本服务内存后，由
 * {@code DicomRoutingService.storeToLocal} 以 C-STORE-SCU 身份写进这里。
 * 与 {@link FakeMoveScp}（外院 PACS，我们从它那里拉）和
 * {@code MoveDestinationStoreScp}（我们自己的 C-MOVE 落点）是三个不同角色，
 * 三者一起才能跑通 query → retrieve → store 三步闭环。
 *
 * <h2>可配置行为（{@link Mode}）</h2>
 * <ul>
 *   <li>{@link Mode#NORMAL} —— 全部回 {@code Success(0x0000)}，并把收到的 dataset 存档供断言</li>
 *   <li>{@link Mode#REJECT_ALL} —— 每条都回 {@code ProcessingFailure(0xC000)}（写入整批失败）</li>
 *   <li>{@link Mode#REJECT_AFTER_N} —— 前 N 条成功、其余失败（写入部分失败）</li>
 *   <li>{@link Mode#SILENT} —— 接受关联但永不回应 C-STORE，逼 SCU 侧 {@code storeTimeoutMs} 到点</li>
 *   <li>{@link Mode#REJECT_ASSOCIATION} —— 直接回 A-ASSOCIATE-RJ（AE Title 未授权）</li>
 * </ul>
 *
 * <p>{@link #storedDatasets()} 是「影像真的进了 PACS」的证据 —— 断言 UID 逐字透传
 * 靠它，而不是靠 SCU 侧的返回值。
 */
public final class FakeLocalPacs implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FakeLocalPacs.class);

    /** 替身行为模式。 */
    public enum Mode {
        NORMAL,
        REJECT_ALL,
        REJECT_AFTER_N,
        SILENT,
        REJECT_ASSOCIATION
    }

    private final String aet;
    private final int port;
    private final Mode mode;
    private final int succeedBeforeReject;
    private final List<String> storageSopClasses;

    private final AtomicInteger acceptedAssociations = new AtomicInteger();
    private final AtomicInteger rejectedAssociations = new AtomicInteger();
    private final AtomicInteger cstoreRequests = new AtomicInteger();
    private final AtomicInteger cstoreSucceeded = new AtomicInteger();
    private final AtomicInteger cstoreRejected = new AtomicInteger();
    /** 真实收到的 instance dataset（按接收顺序）；UID 断言全靠它。 */
    private final List<Attributes> storedDatasets = new CopyOnWriteArrayList<>();

    private Device device;
    private ExecutorService executor;

    public FakeLocalPacs(String aet, int port, Mode mode,
                         int succeedBeforeReject, List<String> storageSopClasses) {
        this.aet = aet;
        this.port = port > 0 ? port : pickFreePort();
        this.mode = mode == null ? Mode.NORMAL : mode;
        this.succeedBeforeReject = succeedBeforeReject;
        this.storageSopClasses = storageSopClasses == null || storageSopClasses.isEmpty()
                ? List.of(UID.CTImageStorage, UID.MRImageStorage)
                : List.copyOf(storageSopClasses);
    }

    /** 便捷构造：默认接受 CT / MR 两类存储 SOP Class。 */
    public static FakeLocalPacs normal(String aet, int port) {
        return new FakeLocalPacs(aet, port, Mode.NORMAL, Integer.MAX_VALUE, null);
    }

    public static FakeLocalPacs withMode(String aet, int port, Mode mode) {
        return new FakeLocalPacs(aet, port, mode, Integer.MAX_VALUE, null);
    }

    private static int pickFreePort() {
        try (java.net.ServerSocket probe =
                     new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("无法分配测试端口", e);
        }
    }

    public FakeLocalPacs start() throws IOException, java.security.GeneralSecurityException {
        device = new Device("fake-local-pacs-" + aet);
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-local-pacs-" + aet);
            t.setDaemon(true);
            return t;
        });
        device.setExecutor(executor);
        device.setScheduledExecutor(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fake-local-pacs-sched-" + aet);
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
        registry.addDicomService(new CStoreScp(this,
                storageSopClasses.toArray(new String[0])));
        device.setDimseRQHandler(registry);

        ApplicationEntity ae = new ApplicationEntity(aet);
        device.addApplicationEntity(ae);
        // 必须把 AE 绑到 Connection 上：AssociationHandler.negotiate 的第 3 项检查
        // ae.getConnections().contains(as.getConnection())，漏掉会被回
        // A-ASSOCIATE-RJ(called-AE-title-not-recognized) —— 与真实 PACS 未登记本节点同义。
        ae.addConnection(conn);
        for (String sopClass : storageSopClasses) {
            // role 必须显式给 SCP：无参构造默认 SCU，会登记到 scuTCs，
            // SCP 侧查 scuTCs 查不到 → 所有 PC 被回 abstract-syntax-not-supported。
            ae.addTransferCapability(new TransferCapability(
                    null, sopClass, TransferCapability.Role.SCP,
                    UID.ExplicitVRLittleEndian, UID.ImplicitVRLittleEndian));
        }

        device.setAssociationHandler(new AssociationHandler() {
            @Override
            protected org.dcm4che3.net.pdu.AAssociateAC negotiate(Association as, AAssociateRQ rq)
                    throws IOException {
                if (mode == Mode.REJECT_ASSOCIATION) {
                    rejectedAssociations.incrementAndGet();
                    throw new AAssociateRJ(
                            AAssociateRJ.RESULT_REJECTED_PERMANENT,
                            AAssociateRJ.SOURCE_SERVICE_USER,
                            AAssociateRJ.REASON_CALLING_AET_NOT_RECOGNIZED);
                }
                acceptedAssociations.incrementAndGet();
                return super.negotiate(as, rq);
            }
        });

        device.bindConnections();
        LOG.info("FakeLocalPacs[{}] 已监听 127.0.0.1:{} mode={} sopClasses={}",
                aet, getPort(), mode, storageSopClasses);
        return this;
    }

    public int getPort() {
        if (device == null) {
            return port;
        }
        return device.listConnections().get(0).getPort();
    }

    public String getAet() { return aet; }

    public int getAcceptedAssociations() { return acceptedAssociations.get(); }
    public int getRejectedAssociations() { return rejectedAssociations.get(); }
    public int getCstoreRequests() { return cstoreRequests.get(); }
    public int getCstoreSucceeded() { return cstoreSucceeded.get(); }
    public int getCstoreRejected() { return cstoreRejected.get(); }

    /** 真实落进「本院 PACS」的 instance dataset —— UID 逐字透传的断言依据。 */
    public List<Attributes> storedDatasets() { return List.copyOf(storedDatasets); }

    /** 落库 instance 的 SOPInstanceUID 列表（顺序 = 接收顺序）。 */
    public List<String> storedSopInstanceUids() {
        List<String> out = new ArrayList<>();
        for (Attributes ds : storedDatasets) {
            out.add(ds.getString(Tag.SOPInstanceUID));
        }
        return out;
    }

    /** 落库 instance 的 StudyInstanceUID 列表（顺序 = 接收顺序）。 */
    public List<String> storedStudyInstanceUids() {
        List<String> out = new ArrayList<>();
        for (Attributes ds : storedDatasets) {
            out.add(ds.getString(Tag.StudyInstanceUID));
        }
        return out;
    }

    /** 落库 instance 的传输语法（取自 presentation context，即本端实际写入时用的语法）。 */
    public List<String> storedTransferSyntaxes() {
        List<String> out = new ArrayList<>();
        for (Attributes ds : storedDatasets) {
            out.add(ds.getString(Tag.TransferSyntaxUID));
        }
        return out;
    }

    public void reset() {
        storedDatasets.clear();
        cstoreRequests.set(0);
        cstoreSucceeded.set(0);
        cstoreRejected.set(0);
        acceptedAssociations.set(0);
        rejectedAssociations.set(0);
    }

    @Override
    public void close() {
        if (device != null) {
            try {
                device.unbindConnections();
            } catch (Exception ignored) {
                // 容许：端口释放失败不影响后续用例（每个用例用 OS 分配的新端口）
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // ==================================================================
    // DIMSE 层：C-STORE SCP
    // ==================================================================

    /**
     * C-STORE 服务提供者。
     *
     * <p>源主路径：dcm4che-net 里 SCP 侧 {@code onDimseRQ(..., Dimse.C_STORE_RQ, rq, rqDataset)}
     * —— {@code rq} 是命令组（带 SOP Instance UID / Storage Media SOP Class UID），
     * {@code rqDataset} 是 instance 本体。我们把 dataset 存进
     * {@link #storedDatasets}，测试据此断言「UID 逐字透传」。
     */
    private static final class CStoreScp extends AbstractDicomService {

        private final FakeLocalPacs outer;

        CStoreScp(FakeLocalPacs outer, String... sopClasses) {
            super(sopClasses);
            this.outer = outer;
        }

        @Override
        protected void onDimseRQ(Association as, PresentationContext pc, Dimse dimse,
                                 Attributes rq, Attributes rqDataset) throws IOException {
            if (dimse != Dimse.C_STORE_RQ) {
                as.writeDimseRSP(pc, Commands.mkCStoreRSP(rq, Status.ProcessingFailure));
                return;
            }
            outer.cstoreRequests.incrementAndGet();

            if (outer.mode == Mode.SILENT) {
                // 接受请求但永不回应：逼 SCU 侧 storeTimeoutMs 到点
                LOG.info("FakeLocalPacs[{}] SILENT：不回应 C-STORE", outer.aet);
                return;
            }

            int seq = outer.cstoreRequests.get();
            boolean reject = outer.mode == Mode.REJECT_ALL
                    || (outer.mode == Mode.REJECT_AFTER_N && seq > outer.succeedBeforeReject);

            if (reject) {
                outer.cstoreRejected.incrementAndGet();
                as.writeDimseRSP(pc, Commands.mkCStoreRSP(rq, Status.ProcessingFailure));
                return;
            }

            if (rqDataset == null) {
                outer.cstoreRejected.incrementAndGet();
                as.writeDimseRSP(pc, Commands.mkCStoreRSP(rq, Status.ProcessingFailure));
                return;
            }

            // 把实际协商到的传输语法记进 dataset 副本，供测试核对「写入用的是什么语法」
            Attributes stored = rqDataset;
            if (pc.getTransferSyntax() != null
                    && !pc.getTransferSyntax().equals(UID.ExplicitVRLittleEndian)) {
                stored = new Attributes(rqDataset);
                stored.setString(Tag.TransferSyntaxUID, org.dcm4che3.data.VR.UI,
                        pc.getTransferSyntax());
            }
            outer.storedDatasets.add(stored);
            outer.cstoreSucceeded.incrementAndGet();
            as.writeDimseRSP(pc, Commands.mkCStoreRSP(rq, Status.Success));
        }
    }
}