package com.crosshospital.imaging.support;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.AssociationHandler;
import org.dcm4che3.net.Commands;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Dimse;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.AAssociateRJ;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.AbstractDicomService;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用「外院 PACS」替身 —— 一个**真的** dcm4che SCP，在 loopback 上监听 DICOM 端口，
 * 接受 A-ASSOCIATE-RQ，并对 STUDY 级 C-FIND-RQ 逐条回 Pending 响应 + 终态 Cancel 响应。
 *
 * <p>它**不是**把 HTTP stub 换了个名字：这里走的是完整的 DICOM 上层链路 ——
 * TCP → A-ASSOCIATE-RQ/AC → C-FIND-RQ → C-FIND-RSP(×N) → A-RELEASE-RQ/RP，
 * 被测的 {@code DicomRoutingService} 用真实 {@code Association.cfind} 消费这些 PDU。
 * 相当于把 dcm4che 自带 {@code storescp} 缩到只实现 C-FIND 这一条主路径，
 * 便于在测试里控制命中集合、响应延迟与拒绝行为。
 *
 * <p>数据来源：本替身持有的 STUDY 元数据由 {@link #studyOf} 在测试内按
 * <b>自造示例 UID 根</b> {@code 1.2.826.0.1.3680043.10.9999} 现场拼装，
 * 无任何可回溯到真实患者的标识；需要真实影像数据集的路径由
 * {@code scripts/generate-fixtures.sh} 负责，本类不涉及像素数据。
 *
 * <p>三种可配置行为，覆盖三条失败/成功路径：
 * <ul>
 *   <li>{@link Mode#NORMAL} —— 正常应答（命中集合 = 传入的 STUDY 列表）</li>
 *   <li>{@link Mode#REJECT_ASSOCIATION} —— 对 A-ASSOCIATE-RQ 回 A-ASSOCIATE-RJ
 *       （reason = 调用方 AE Title 未授权），对应 {@code ASSOCIATION_REJECTED}</li>
 *   <li>{@link Mode#SILENT} —— 接受关联但**永不**回应 C-FIND，
 *       逼 SCU 侧 {@code rspTimeout} 到点，对应 {@code QUERY_TIMEOUT}</li>
 * </ul>
 */
public final class FakePacsServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FakePacsServer.class);

    /** 替身行为模式。 */
    public enum Mode {
        NORMAL,
        REJECT_ASSOCIATION,
        SILENT
    }

    /** STUDY Root Query/Retrieve Information Model - FIND。 */
    public static final String STUDY_ROOT_FIND = "1.2.840.10008.5.1.4.1.2.2.1";

    /** 场景：正常应答。 */
    public static final Mode NORMAL = Mode.NORMAL;
    /** 场景：关联被拒。 */
    public static final Mode REJECT_ASSOCIATION = Mode.REJECT_ASSOCIATION;
    /** 场景：接受关联但不应答（逼超时）。 */
    public static final Mode SILENT = Mode.SILENT;

    private final String aet;
    private final int port;
    private final List<Attributes> studies;
    private final Mode mode;
    private final long findDelayMs;
    private final AtomicInteger acceptedAssociations = new AtomicInteger();
    private final AtomicInteger rejectedAssociations = new AtomicInteger();
    private final AtomicInteger cfindRequests = new AtomicInteger();

    private Device device;

    /**
     * @param aet         本替身对外呈现的 AE Title
     * @param port        监听端口（传 0 由 OS 分配，用 {@link #getPort()} 取实际值）
     * @param studies     STUDY 元数据集合（每条含 StudyInstanceUID / PatientID 等）
     * @param mode        替身行为模式
     * @param findDelayMs 收到 C-FIND-RQ 后延迟多少毫秒才回第一帧
     *                    （{@link Mode#SILENT} 下该值无意义，不回即可）
     */
    public FakePacsServer(String aet, int port, List<Attributes> studies,
                          Mode mode, long findDelayMs) {
        this.aet = aet;
        // dcm4che 的 Connection.setPort() 拒绝 0，因此由本类先向 OS 借一个空闲端口
        this.port = port > 0 ? port : pickFreePort();
        this.studies = studies == null ? new ArrayList<>() : new ArrayList<>(studies);
        this.mode = mode;
        this.findDelayMs = findDelayMs;
    }

    /** 向 OS 借一个当前空闲的 loopback 端口（借完即释放，随后由 dcm4che 绑定）。 */
    private static int pickFreePort() {
        try (java.net.ServerSocket probe =
                     new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("无法分配测试端口", e);
        }
    }

    /** 起服务：绑定端口 + 注册 C-FIND SCP。 */
    public FakePacsServer start() throws IOException, GeneralSecurityException {
        device = new Device("fake-pacs-" + aet);
        // dcm4che 的 Device 需要外部注入 Executor 才会处理 PDU。必须用**弹性**线程池：
        // TCP 监听线程本身也占用该 Executor，单线程池会让已 accept 的连接分不到 PDU 处理
        // 线程（表现为 A-ASSOCIATE-RQ 发出后对端无响应）。全部 daemon，surefire 可正常退出。
        device.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-pacs-" + aet + "-executor");
            t.setDaemon(true);
            return t;
        }));
        device.setScheduledExecutor(
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "fake-pacs-" + aet + "-scheduler");
                    t.setDaemon(true);
                    return t;
                }));

        Connection conn = new Connection();
        conn.setHostname("127.0.0.1");
        conn.setPort(port);
        conn.setProtocol(Connection.Protocol.DICOM);
        conn.setBacklog(50);
        device.addConnection(conn);

        device.setDimseRQHandler(buildRegistry());

        org.dcm4che3.net.ApplicationEntity ae =
                new org.dcm4che3.net.ApplicationEntity(aet);
        device.addApplicationEntity(ae);
        // 源主路径：AssociationHandler.negotiate 的第 3 项检查
        //   ae.getConnections().contains(as.getConnection())
        // 只有把 AE 绑到 Connection 上才通过；漏掉会被回 A-ASSOCIATE-RJ
        // (reason = called-AE-title-not-recognized)。
        ae.addConnection(conn);
        // 源主路径：ApplicationEntity.negotiate(rq, ac, pc) 用
        // roleSelection → scpTCs.get(cuid) → TransferCapability.selectTransferSyntax
        // 决定每个 PC 的接受结果。**role 必须显式给 SCP**：TransferCapability 无参构造
        // 默认 role=SCU，会被登记进 scuTCs；SCP 侧查 scpTCs 查不到 →
        // 全部 PC 回 abstract-syntax-not-supported（等价于真实 PACS 未声明支持该模型）。
        org.dcm4che3.net.TransferCapability tc =
                new org.dcm4che3.net.TransferCapability(
                        null,                                    // commonName = 对所有调用方生效
                        STUDY_ROOT_FIND,                         // sopClass
                        org.dcm4che3.net.TransferCapability.Role.SCP,
                        org.dcm4che3.data.UID.ImplicitVRLittleEndian,
                        org.dcm4che3.data.UID.ExplicitVRLittleEndian);
        ae.addTransferCapability(tc);
        device.setAssociationHandler(new RejectingOrAcceptingHandler(mode));

        device.bindConnections();
        LOG.info("FakePacsServer[{}] 已监听 127.0.0.1:{} mode={} studies={}",
                aet, getPort(), mode, studies.size());
        return this;
    }

    private DicomServiceRegistry buildRegistry() {
        DicomServiceRegistry registry = new DicomServiceRegistry();
        registry.addDicomService(new StudyFindScp(studies, mode, findDelayMs, cfindRequests));
        return registry;
    }

    /** 实际监听端口（构造时传 0 时由 OS 分配）。 */
    public int getPort() {
        if (device == null) {
            return port;
        }
        return device.listConnections().get(0).getPort();
    }

    public String getAet() { return aet; }

    /** 成功接受的关联数（REJECT_ASSOCIATION 模式下恒为 0）。 */
    public int getAcceptedAssociations() { return acceptedAssociations.get(); }

    /** 被拒的关联数。 */
    public int getRejectedAssociations() { return rejectedAssociations.get(); }

    /** 收到的 C-FIND-RQ 条数。 */
    public int getCfindRequests() { return cfindRequests.get(); }

    @Override
    public void close() {
        if (device != null) {
            device.unbindConnections();
            LOG.info("FakePacsServer[{}] 已关闭", aet);
        }
    }

    // ==================================================================
    // 关联层：接受 / 拒绝 A-ASSOCIATE-RQ
    // ==================================================================

    /**
     * 在 {@code negotiate()} 阶段决定接受还是拒绝。
     *
     * <p>源主路径：dcm4che-net {@code AssociationHandler.negotiate(Association, AAssociateRQ)}
     * —— 默认实现接受全部 PC；这里在 {@link Mode#REJECT_ASSOCIATION} 下抛
     * {@link AAssociateRJ}（REASON_CALLING_AET_NOT_RECOGNIZED），由 dcm4che 的接受循环
     * 据此发出真正的 A-ASSOCIATE-RJ PDU，对端随后抛关联状态异常。
     */
    private final class RejectingOrAcceptingHandler extends AssociationHandler {
        private final Mode handlerMode;

        RejectingOrAcceptingHandler(Mode handlerMode) {
            this.handlerMode = handlerMode;
        }

        @Override
        protected org.dcm4che3.net.pdu.AAssociateAC negotiate(Association as, AAssociateRQ rq)
                throws IOException {
            if (handlerMode == Mode.REJECT_ASSOCIATION) {
                rejectedAssociations.incrementAndGet();
                LOG.info("FakePacsServer[{}] 拒绝关联: callingAET={}",
                        aet, rq.getCallingAET());
                throw new AAssociateRJ(
                        AAssociateRJ.RESULT_REJECTED_PERMANENT,
                        AAssociateRJ.SOURCE_SERVICE_USER,
                        AAssociateRJ.REASON_CALLING_AET_NOT_RECOGNIZED);
            }
            acceptedAssociations.incrementAndGet();
            return super.negotiate(as, rq);
        }
    }

    // ==================================================================
    // DIMSE 层：STUDY 级 C-FIND SCP
    // ==================================================================

    /**
     * STUDY 级 C-FIND 服务提供者。
     *
     * <p>逐条命中回 {@code Pending} + 数据集，全部发完回终态 {@code Cancel} ——
     * 与 DICOM PS3.4 C.2.2.1 规定的一致，也正是 dcm4che 自带 {@code findscu}
     * 所对端应有的行为。
     */
    private static final class StudyFindScp extends AbstractDicomService {

        private final List<Attributes> studies;
        private final Mode mode;
        private final long delayMs;
        private final AtomicInteger requestCounter;

        StudyFindScp(List<Attributes> studies, Mode mode, long delayMs,
                     AtomicInteger requestCounter) {
            super(STUDY_ROOT_FIND);
            this.studies = studies;
            this.mode = mode;
            this.delayMs = delayMs;
            this.requestCounter = requestCounter;
        }

        @Override
        protected void onDimseRQ(Association as, PresentationContext pc, Dimse dimse,
                                 Attributes rq, Attributes rqKeys) throws IOException {
            requestCounter.incrementAndGet();
            if (dimse != Dimse.C_FIND_RQ) {
                // 本替身只实现 C-FIND；其余 DIMSE 一律按「不支持」回绝
                as.writeDimseRSP(pc, Commands.mkCFindRSP(rq, Status.ProcessingFailure));
                return;
            }
            if (mode == Mode.SILENT) {
                // 接受请求但不回任何帧 —— SCU 侧 rspTimeout 到点后自行放弃关联
                LOG.info("FakePacsServer[SILENT] 收到 C-FIND-RQ 但保持沉默");
                return;
            }
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            String patientId = rqKeys == null ? null : rqKeys.getString(Tag.PatientID);
            String accession = rqKeys == null ? null : rqKeys.getString(Tag.AccessionNumber);
            String studyUid = rqKeys == null ? null : rqKeys.getString(Tag.StudyInstanceUID);

            int sent = 0;
            for (Attributes candidate : studies) {
                if (!matches(candidate, patientId, accession, studyUid)) {
                    continue;
                }
                as.writeDimseRSP(pc, Commands.mkCFindRSP(rq, Status.Pending),
                        projectResponseKeys(candidate, rqKeys));
                sent++;
            }
            // 终态 Cancel：无数据集，宣告查询正常结束（含零命中）
            as.writeDimseRSP(pc, Commands.mkCFindRSP(rq, Status.Cancel));
            LOG.info("FakePacsServer C-FIND 完成: patientId={} accession={} studyUid={} 命中 {}",
                    patientId, accession, studyUid, sent);
        }

        /** 匹配语义：非空的键才参与过滤，空值键视作通配。 */
        private boolean matches(Attributes candidate, String patientId,
                                String accession, String studyUid) {
            if (patientId != null && !patientId.isBlank()
                    && !patientId.equals(candidate.getString(Tag.PatientID))) {
                return false;
            }
            if (accession != null && !accession.isBlank()
                    && !accession.equals(candidate.getString(Tag.AccessionNumber))) {
                return false;
            }
            if (studyUid != null && !studyUid.isBlank()
                    && !studyUid.equals(candidate.getString(Tag.StudyInstanceUID))) {
                return false;
            }
            return true;
        }

        /**
         * 按 SCU 请求的返回键组装响应数据集 —— 真实 PACS 也是这么做的：
         * 不多给，也不漏给，SCU 没请求的键就不会出现在响应里。
         */
        private Attributes projectResponseKeys(Attributes candidate, Attributes rqKeys) {
            Attributes out = new Attributes();
            copyIfRequested(rqKeys, candidate, out, Tag.StudyInstanceUID);
            copyIfRequested(rqKeys, candidate, out, Tag.PatientID);
            copyIfRequested(rqKeys, candidate, out, Tag.PatientName);
            copyIfRequested(rqKeys, candidate, out, Tag.AccessionNumber);
            copyIfRequested(rqKeys, candidate, out, Tag.StudyDate);
            copyIfRequested(rqKeys, candidate, out, Tag.StudyID);
            copyIfRequested(rqKeys, candidate, out, Tag.StudyDescription);
            copyIfRequested(rqKeys, candidate, out, Tag.SeriesDescription);
            copyIfRequested(rqKeys, candidate, out, Tag.Modality);
            copyIfRequested(rqKeys, candidate, out, Tag.NumberOfStudyRelatedInstances);
            return out;
        }

        private void copyIfRequested(Attributes rqKeys, Attributes candidate,
                                     Attributes out, int tag) {
            if (rqKeys == null || !rqKeys.contains(tag)) {
                return;
            }
            String value = candidate.getString(tag);
            if (value == null) {
                return;
            }
            out.setString(tag, vrFor(tag), value);
        }

        private static VR vrFor(int tag) {
            return switch (tag) {
                case Tag.StudyInstanceUID -> VR.UI;
                case Tag.PatientID, Tag.StudyDescription,
                     Tag.SeriesDescription, Tag.PatientName -> VR.LO;
                case Tag.AccessionNumber -> VR.SH;
                case Tag.StudyID -> VR.SH;
                case Tag.StudyDate -> VR.DA;
                case Tag.Modality -> VR.CS;
                case Tag.NumberOfStudyRelatedInstances -> VR.IS;
                default -> VR.LO;
            };
        }
    }

    // ==================================================================
    // 样例数据构造
    // ==================================================================

    /** 拼装一条 STUDY 元数据（自造示例 UID 根，非真实机构分配）。 */
    public static Attributes studyOf(String patientId, String accessionNumber,
                                     String studyUid, String modality,
                                     String studyDate, int instanceCount) {
        Attributes a = new Attributes();
        a.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 100");
        a.setString(Tag.PatientID, VR.LO, patientId);
        a.setString(Tag.PatientName, VR.PN, "ANON^PATIENT");
        a.setString(Tag.AccessionNumber, VR.SH, accessionNumber);
        a.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
        a.setString(Tag.StudyDate, VR.DA, studyDate);
        a.setString(Tag.StudyID, VR.SH, "ST" + studyDate);
        a.setString(Tag.StudyDescription, VR.LO, "EXTERNAL STUDY " + modality);
        a.setString(Tag.SeriesDescription, VR.LO, "EXTERNAL SERIES");
        a.setString(Tag.Modality, VR.CS, modality);
        a.setString(Tag.NumberOfStudyRelatedInstances, VR.IS, Integer.toString(instanceCount));
        return a;
    }
}