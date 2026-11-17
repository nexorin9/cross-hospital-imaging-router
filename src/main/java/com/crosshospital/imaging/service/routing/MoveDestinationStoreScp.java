package com.crosshospital.imaging.service.routing;

import com.crosshospital.imaging.config.RoutingConfigProps;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomEncodingOptions;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.ApplicationEntity;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本端 C-STORE-SCP —— 接收外院 PACS 通过 C-MOVE-RQ 推回来的 DICOM instance。
 *
 * <h2>角色（C-MOVE 协议中的 S）</h2>
 * <ol>
 *   <li>本地起一个 dcm4che {@link Device}，绑定 socket + 本地 AET</li>
 *   <li>作为 {@link TransferCapability.Role#SCP} 注册若干 storage SOP Class</li>
 *   <li>等待外院 MOVE-SCP 主动开关联过来，对每个 C-STORE-RQ 把数据缓冲到内存并回 Pending</li>
 *   <li>容量超 {@link RoutingConfigProps#getMaxInstancesPerStudy()} 时拒收，避免无限堆积</li>
 * </ol>
 *
 * <h2>为什么是单例</h2>
 * <p>本端 AE Title（{@code IMAGER}）应让外院 PACS 视为**已知节点**，每次只听一处；
 * 多次调阅通过「并发承载多个 C-STORE 关联」解决，而非「开多端口」。
 *
 * <h2>源主路径证据</h2>
 * <ul>
 *   <li>dcm4che README 中 {@code storescp} —— 本类是其服务化产物（不再命令行调用）</li>
 *   <li>{@code TransferCapability.Role#SCP} 必须显式给出（无参构造默认 SCU，登记到 scuTCs）</li>
 * </ul>
 */
@Component
@Profile("!test")
public class MoveDestinationStoreScp {

    private static final Logger LOG = LoggerFactory.getLogger(MoveDestinationStoreScp.class);

    private final RoutingConfigProps routingProps;
    private Device device;
    private InstanceSink sink;
    private ExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean(false);

    @Autowired
    public MoveDestinationStoreScp(RoutingConfigProps routingProps) {
        this.routingProps = routingProps;
    }

    /** 测试用构造：不启动 SCP，直接挂指定 sink 给调用方用。 */
    public MoveDestinationStoreScp(RoutingConfigProps routingProps, InstanceSink sink) {
        this.routingProps = routingProps;
        this.sink = sink;
    }

    @PostConstruct
    synchronized void start() throws IOException, GeneralSecurityException {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (this.sink == null) {
            this.sink = buildSink();
        }
        startInternal(this.sink);
    }

    /**
     * 启动并挂上指定 sink（测试 / 远端驱动场景使用）。
     * @return 实际监听端口（{@code routing.localMoveDestPort=0} 时为 OS 分配）
     */
    public synchronized int startWithSink(InstanceSink instanceSink)
            throws IOException, GeneralSecurityException {
        if (!started.compareAndSet(false, true)) {
            return getPort();
        }
        this.sink = instanceSink;
        startInternal(this.sink);
        return getPort();
    }

    private InstanceSink buildSink() {
        return new InstanceSink(routingProps.getMaxInstancesPerStudy(),
                routingProps.getStorageSopClasses());
    }

    private void startInternal(InstanceSink targetSink)
            throws IOException, GeneralSecurityException {
        String aet = routingProps.getLocalMoveDestAet();
        device = new Device("cross-hospital-store-scp");

        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "move-dest-scp-executor");
            t.setDaemon(true);
            return t;
        });
        device.setExecutor(executor);
        device.setScheduledExecutor(
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "move-dest-scp-scheduler");
                    t.setDaemon(true);
                    return t;
                }));

        int requestedPort = routingProps.getLocalMoveDestPort();
        int bindPort = requestedPort > 0 ? requestedPort : pickFreePort();
        Connection conn = new Connection();
        conn.setHostname(routingProps.getLocalMoveDestHost());
        conn.setPort(bindPort);
        conn.setProtocol(Connection.Protocol.DICOM);
        conn.setBacklog(routingProps.getMaxLocalStoreAssociations());
        // 显式声明无 TLS，避免 dcm4che 5.x 默认 TLSv1.2 在测试环境触发握手异常
        conn.setTlsCipherSuites(new String[0]);
        conn.setTlsProtocols(new String[0]);
        device.addConnection(conn);

        List<String> sopClasses = routingProps.getStorageSopClasses();
        DicomServiceRegistry registry = new DicomServiceRegistry();
        registry.addDicomService(new CStoreScp(targetSink,
                sopClasses.toArray(new String[0])));
        device.setDimseRQHandler(registry);

        ApplicationEntity ae = new ApplicationEntity(aet);
        device.addApplicationEntity(ae);
        ae.addConnection(conn);

        // 显式声明本端支持若干 storage SOP Class（role 必为 SCP，源主路径：TransferCapability 无参构造默认 SCU）
        for (String sopClass : sopClasses) {
            TransferCapability tc = new TransferCapability(
                    null,
                    sopClass,
                    TransferCapability.Role.SCP,
                    UID.ExplicitVRLittleEndian,
                    UID.ImplicitVRLittleEndian);
            ae.addTransferCapability(tc);
        }

        device.bindConnections();
        LOG.info("MoveDestinationStoreScp[{}] 已监听 {}:{} 支持 {} 类存储 SOP",
                aet, routingProps.getLocalMoveDestHost(), getPort(), sopClasses.size());
    }

    @PreDestroy
    synchronized void shutdown() {
        if (device != null) {
            try {
                device.unbindConnections();
            } catch (Exception e) {
                LOG.warn("MoveDestinationStoreScp 关闭异常：{}", e.getMessage());
            }
            LOG.info("MoveDestinationStoreScp 已关闭");
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * 显式回收监听端口（集成测试用）。
     *
     * <p>{@code @PreDestroy} 在容器关闭时会调 {@link #shutdown()}；这里只是把同一个
     * 幂等动作暴露成 public，让不依赖 Spring 生命周期的集成测试也能关掉 SCP。
     */
    public synchronized void close() {
        shutdown();
    }

    /** 实际监听端口（{@code port=0} 时 OS 分配）。 */
    public synchronized int getPort() {
        if (device == null) {
            return routingProps.getLocalMoveDestPort();
        }
        List<Connection> conns = device.listConnections();
        return conns.isEmpty() ? routingProps.getLocalMoveDestPort() : conns.get(0).getPort();
    }

    /** OS 借一个当前空闲的 loopback 端口（与 FakePacsServer 保持一致：dcm4che 拒绝 port=0）。 */
    private static int pickFreePort() {
        try (java.net.ServerSocket probe =
                     new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("无法分配 MoveDestinationStoreScp 测试端口", e);
        }
    }

    public synchronized InstanceSink getSink() {
        if (sink == null) {
            sink = buildSink();
        }
        return sink;
    }

    // ==================================================================
    // C-STORE SCP 实现
    // ==================================================================

    private static final class CStoreScp extends AbstractDicomService {

        private final InstanceSink sink;

        CStoreScp(InstanceSink sink, String... sopClasses) {
            super(sopClasses);
            this.sink = sink;
        }

        @Override
        protected void onDimseRQ(Association as, PresentationContext pc, Dimse dimse,
                                 Attributes rq, Attributes rqDataset) throws IOException {
            if (dimse != Dimse.C_STORE_RQ) {
                as.writeDimseRSP(pc, Commands.mkCStoreRSP(rq, Status.ProcessingFailure));
                return;
            }
            String sopInstanceUid = rqDataset == null ? null
                    : rqDataset.getString(Tag.SOPInstanceUID);
            String sopClass = rqDataset == null ? null
                    : rqDataset.getString(Tag.SOPClassUID);

            byte[] instanceBytes;
            try {
                instanceBytes = serializeDataset(rqDataset);
            } catch (IOException | RuntimeException e) {
                // 单条 instance 序列化失败只回 ProcessingFailure，不让异常冒泡：
                // 冒泡会让 dcm4che 发 A-ABORT 掐掉整条关联，
                // 一次拉阅从「部分失败」退化成「整体超时」。
                LOG.warn("C-STORE dataset 序列化失败：{} / {}",
                        e.getClass().getSimpleName(), e.getMessage());
                as.writeDimseRSP(pc, Commands.mkCStoreRSP(rq, Status.ProcessingFailure));
                return;
            }

            boolean accepted = sink.onInstance(sopClass, sopInstanceUid,
                    pc.getTransferSyntax(), instanceBytes);
            if (!accepted) {
                // sink 拒收：超过最大 instance 数 / SOP Class 不在白名单 / 数据为空
                // 回 OneOrMoreFailures 让外院 MOVE-SCP 把它计入 failed sub-op
                as.writeDimseRSP(pc, Commands.mkCStoreRSP(rq,
                        Status.OneOrMoreFailures));
                return;
            }
            as.writeDimseRSP(pc, Commands.mkCStoreRSP(rq, Status.Success));
        }
    }

    /**
     * dataset → 显式 VR LE 字节流。
     *
     * <p>不替 dcm4che 的 {@code Attributes#writeTo(OutputStream)}：那会把 group 0002 (file-meta)
     * 也写出来，而 C-STORE 的 dataset 段通常**不**含 file-meta（meta 仅在 PS 媒体文件 /
     * WADO-RS 响应里出现）。这里走 DicomOutputStream 把 dataset 当「无 meta」媒体写入，足够
     * 让 task 15 的 C-STORE-SCU 把它再发回本院 PACS。
     */
    static byte[] serializeDataset(Attributes rqDataset) throws IOException {
        if (rqDataset == null) {
            return new byte[0];
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            DicomOutputStream dos = new DicomOutputStream(
                    (OutputStream) baos, UID.ExplicitVRLittleEndian);
            dos.setEncodingOptions(DicomEncodingOptions.DEFAULT);
            // fileMeta 传 **null**：C-STORE 的 dataset 段本身不含 group 0002。
            // 传空 Attributes() 会被 dcm4che 误判为「有 file-meta 要写」，进
            // writeFileMetaInformation → Attributes.writeGroupTo(0) 抛
            // IllegalStateException("No attributes")，整个关联被 A-ABORT 掉，
            // 表现为一次拉阅整体变成 partial_failure / move_timeout。
            dos.writeDataset(null, rqDataset);
            dos.flush();
            return baos.toByteArray();
        }
    }

    // ==================================================================
    // Sink：对外可观察的实例收集器
    // ==================================================================

    /**
     * 收到的 instance 集合 + 拒收判定。
     *
     * <p>线程安全：外院 MOVE-SCP 可能在独立线程并发地调 {@link #onInstance}。
     */
    public static final class InstanceSink {
        private final List<byte[]> instances = new CopyOnWriteArrayList<>();
        private final List<String> sopInstanceUids = new CopyOnWriteArrayList<>();
        private final List<String> sopClasses = new CopyOnWriteArrayList<>();
        private final List<String> transferSyntaxes = new CopyOnWriteArrayList<>();
        private final AtomicInteger received = new AtomicInteger();
        private final AtomicInteger rejected = new AtomicInteger();
        private final int maxInstances;
        private final Set<String> acceptedSopClasses;

        public InstanceSink() {
            this(5000, List.of());
        }

        public InstanceSink(int maxInstances, List<String> storageSopClasses) {
            this.maxInstances = maxInstances;
            this.acceptedSopClasses = storageSopClasses == null
                    ? Set.of()
                    : Set.copyOf(storageSopClasses);
        }

        /**
         * @return {@code true} = 接收并缓冲；{@code false} = 拒收
         *         （容量满 / SOP Class 不在白名单 / 数据为空）
         */
        public boolean onInstance(String sopClass, String sopInstanceUid,
                                  String transferSyntax, byte[] instanceBytes) {
            received.incrementAndGet();
            if (instanceBytes == null || instanceBytes.length == 0) {
                rejected.incrementAndGet();
                return false;
            }
            if (!acceptedSopClasses.isEmpty()
                    && (sopClass == null || !acceptedSopClasses.contains(sopClass))) {
                rejected.incrementAndGet();
                return false;
            }
            if (instances.size() >= maxInstances) {
                rejected.incrementAndGet();
                return false;
            }
            instances.add(instanceBytes);
            sopInstanceUids.add(sopInstanceUid);
            sopClasses.add(sopClass);
            transferSyntaxes.add(transferSyntax);
            return true;
        }

        public int size() { return instances.size(); }
        public byte[] getInstance(int idx) { return instances.get(idx); }
        public String getSopInstanceUid(int idx) { return sopInstanceUids.get(idx); }
        public String getSopClass(int idx) { return sopClasses.get(idx); }
        public String getTransferSyntax(int idx) { return transferSyntaxes.get(idx); }
        public List<byte[]> getInstances() { return List.copyOf(instances); }
        public List<String> getSopInstanceUids() { return List.copyOf(sopInstanceUids); }
        public int received() { return received.get(); }
        public int rejected() { return rejected.get(); }
        public void clear() {
            instances.clear();
            sopInstanceUids.clear();
            sopClasses.clear();
            transferSyntaxes.clear();
        }
    }
}
