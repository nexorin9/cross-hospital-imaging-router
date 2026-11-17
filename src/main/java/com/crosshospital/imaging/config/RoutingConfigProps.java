package com.crosshospital.imaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;

/**
 * 路由执行配置（{@code routing.*}）—— 覆盖跨院调阅主路径三步（DICOM）的执行预算。
 *
 * <p>对应 dcm4che README 中 {@code findscu / movescu / storescu} 三个工具各自的超时维度：
 * 查询（C-FIND）、拉回（C-MOVE）、写入（C-STORE）。这三个操作在源码里都是
 * {@code Association.invoke(..., int rspTimeout)} 的 {@code rspTimeout} 实参；
 * 本类把三档超时从硬编码提为配置，并按院内网络实测值给出默认。
 *
 * <p>超时语义：到点即**放弃本次关联**（发 A-RELEASE 或 A-ABORT 硬关闭），并把
 * {@code QUERY_TIMEOUT} 失败码交给上层重试判定（task 16）——绝不静默返回空列表，
 * 否则上游会误读成「外院没有这个病人的影像」而推进错误的业务状态。
 *
 * <p>源主路径证据：dcm4che-net {@code Association.java} 的
 * {@code cfind/cget/cmove/cstore(..., int rspTimeout)} 全部走同一条
 * {@code invoke(PresentationContext, Attributes, DataWriter, DimseRSPHandler, int rspTimeout, int priority)}
 * 主路径；{@code Connection.java:1034-1091} 的 {@code connectTimeout} 之外，
 * DIMSE 响应超时是第二道独立预算。
 */
@ConfigurationProperties(prefix = "routing")
@Profile("!test")
public class RoutingConfigProps {

    /** C-FIND 查询响应超时（毫秒）。默认 30s —— 外院 PACS 冷查询 + 跨院链路。 */
    private int queryTimeoutMs = 30000;

    /** C-MOVE 拉回响应超时（毫秒）。默认 120s —— 一次调阅动辄几百个 instance。 */
    private int retrieveTimeoutMs = 120000;

    /** C-STORE 写入本院 PACS 响应超时（毫秒）。默认 60s。 */
    private int storeTimeoutMs = 60000;

    /** 单个 study 允许拉回的最大 instance 数；超限直接拒拉，避免一条脏数据打满缓存。 */
    private int maxInstancesPerStudy = 5000;

    /** A-ASSOCIATE-RQ 协商用的传输语法列表（按偏好顺序）。 */
    private List<String> transferSyntaxes = new ArrayList<>(List.of(
            "1.2.840.10008.1.2.1",     // Explicit VR Little Endian
            "1.2.840.10008.1.2",       // Implicit VR Little Endian
            "1.2.840.10008.1.2.4.70",  // JPEG Lossless, Non-Hierarchical-14
            "1.2.840.10008.1.2.4.90"   // JPEG 2000 Image Compression (Lossless Only)
    ));

    /**
     * STUDY 级 C-FIND 的 Query/Retrieve SOP Class，按偏好顺序排列。
     *
     * <p>优先用 Study Root（{@code 1.2.840.10008.5.1.4.1.2.2.1}）—— 外院 PACS
     * 普遍支持；不支持时依次回退 Patient Root、Patient Study Only。
     * 全部不被接受时关联会以 {@code ASSOCIATION_REJECTED} 失败（task 16 分类为
     * 配置型错误、**不**重试）。
     */
    private List<String> queryRetrieveSopClasses = new ArrayList<>(List.of(
            "1.2.840.10008.5.1.4.1.2.2.1",  // Study Root Query/Retrieve Information Model - FIND
            "1.2.840.10008.5.1.4.1.2.1.1",  // Patient Root Query/Retrieve Information Model - FIND
            "1.2.840.10008.5.1.4.1.2.3.1"   // Patient Study Only Query/Retrieve Information Model - FIND
    ));

    /** 关联协商用的最大 PDU 长度（字节）。0 = 不显式设置，由 dcm4che 取协议默认。 */
    private int maxPduLength = 16384;

    /** 本服务作 SCU 的 Implementation Class UID。上线前应替换为本院向影像互认平台登记的值。 */
    private String implementationClassUid = "1.2.826.0.1.3680043.10.7777.1.13";

    /**
     * C-MOVE 用的 Query/Retrieve SOP Class（Study Root MOVE），按偏好顺序。
     *
     * <p>外院 PACS 的 MOVE-SCP 几乎只支持 Study Root；
     * 取不到则用 Patient Root 兜底。完整列表见 DICOM PS3.4。
     */
    private List<String> moveRetrieveSopClasses = new ArrayList<>(List.of(
            "1.2.840.10008.5.1.4.1.2.2.2",  // Study Root Query/Retrieve Information Model - MOVE
            "1.2.840.10008.5.1.4.1.2.1.2"   // Patient Root Query/Retrieve Information Model - MOVE
    ));

    /**
     * 本端作为 C-STORE-SCP（外院 PACS 把 instance 推给我们时）接收哪些 SOP Class。
     *
     * <p>跨院互认场景几乎只用 CT / MR / CR / US 四类存储 SOP Class；
     * 取自 PS3.4 Annex C.6 与院内影像互认公示清单。
     */
    private List<String> storageSopClasses = new ArrayList<>(List.of(
            "1.2.840.10008.5.1.4.1.1.2",   // CT Image Storage
            "1.2.840.10008.5.1.4.1.1.4",   // MR Image Storage
            "1.2.840.10008.5.1.4.1.1.1",   // Computed Radiography Image Storage
            "1.2.840.10008.5.1.4.1.1.6.1"  // Ultrasound Image Storage
    ));

    /**
     * 本端「承接外院 C-MOVE 推过来 instance」的 AE Title。
     *
     * <p>外院 PACS 的 C-MOVE-SCP 收到 C-MOVE-RQ 后会按 {@code MoveDestination} 字段
     * 解析这个 AET，然后**主动**开一条新关联回本端这个 AET 的端口去做 C-STORE；
     * 它必须能从准入表中查出本端 host/port。生产应与准入表登记的本地节点 AET 严格一致。
     */
    private String localMoveDestAet = "IMAGER";

    /** 本端 C-STORE-SCP 监听 host（一般 loopback，生产取网卡 IP）。 */
    private String localMoveDestHost = "127.0.0.1";

    /** 本端 C-STORE-SCP 监听端口（0 = 测试时由 OS 分配，生产必须固定）。 */
    private int localMoveDestPort = 11113;

    /** 单次 C-MOVE 的接收连接接受上限（防恶意外院一次连接连发数千次 C-STORE）。 */
    private int maxLocalStoreAssociations = 4;

    // ------------------------------------------------------------------
    // 写入目标：本院 PACS（task 15 主路径第三步）
    // ------------------------------------------------------------------

    /**
     * 本院 PACS 的 AE Title —— C-STORE 的 {@code Called AE Title}。
     *
     * <p>凭据/寻址位来自环境变量 {@code LOCAL_PACS_AET}；部署时填影像科登记的本院 PACS
     * 节点 AET（如 {@code LOCAL_PACS} / {@code PACS_MAIN}），本服务以 SCU 身份主动写进去。
     *
     * <p>与 {@link #localMoveDestAet} 的区别：后者是<b>我们自己的</b> C-STORE-SCP
     * （外院 C-MOVE 把片子推给我们时的落点），本项是<b>本院 PACS</b>（我们把片子写进去的地方）。
     * 两者在小型医院可能是同一台机器，但角色不同、配置也不同，混用会让运维在
     * 「片子到底推给谁」上绕不明白。
     */
    private String localPacsAet = "LOCAL_PACS";

    /** 本院 PACS 主机（生产取 PACS 网卡 IP；dcm4che 拒绝 port=0，测试须显式给真实端口）。 */
    private String localPacsHost = "127.0.0.1";

    /** 本院 PACS 端口（DICOM 标准 11112）。 */
    private int localPacsPort = 11112;

    /**
     * 写入本院 PACS 时**只**向关联提议的传输语法（按偏好顺序）。
     *
     * <p>为什么不用 {@link #transferSyntaxes}（含 JPEG Lossless / JPEG 2000）：
     * 那是<b>查询/拉回</b>侧的偏好 —— 从外院读东西时当然希望对方用高压缩率的语法。
     * 但写入侧要把内存里的 dataset 重新编码到协商到的传输语法，若对端接受了
     * JPEG 2000，而本服务没挂对应的压缩编解码器，{@code DicomOutputStream}
     * 会在编码第一条 instance 时抛异常，整批调阅从「写入 PACS」退化成一堆 C-STORE 失败。
     * 因此写入侧默认只提议**未压缩**语法：外院传过来的压缩 instance 已在
     * {@code MoveDestinationStoreScp} 侧解成内存 dataset，这里重新编码为
     * Explicit/Implicit VR LE 写入，本院 PACS 拿到的是标准 Part-10 文件。
     */
    private List<String> localStoreTransferSyntaxes = new ArrayList<>(List.of(
            "1.2.840.10008.1.2.1",     // Explicit VR Little Endian
            "1.2.840.10008.1.2"        // Implicit VR Little Endian
    ));

    public int getQueryTimeoutMs() { return queryTimeoutMs; }
    public void setQueryTimeoutMs(int queryTimeoutMs) { this.queryTimeoutMs = queryTimeoutMs; }

    public int getRetrieveTimeoutMs() { return retrieveTimeoutMs; }
    public void setRetrieveTimeoutMs(int retrieveTimeoutMs) { this.retrieveTimeoutMs = retrieveTimeoutMs; }

    public int getStoreTimeoutMs() { return storeTimeoutMs; }
    public void setStoreTimeoutMs(int storeTimeoutMs) { this.storeTimeoutMs = storeTimeoutMs; }

    public int getMaxInstancesPerStudy() { return maxInstancesPerStudy; }
    public void setMaxInstancesPerStudy(int maxInstancesPerStudy) {
        this.maxInstancesPerStudy = maxInstancesPerStudy;
    }

    public List<String> getTransferSyntaxes() { return transferSyntaxes; }
    public void setTransferSyntaxes(List<String> transferSyntaxes) {
        this.transferSyntaxes = transferSyntaxes;
    }

    public List<String> getQueryRetrieveSopClasses() { return queryRetrieveSopClasses; }
    public void setQueryRetrieveSopClasses(List<String> queryRetrieveSopClasses) {
        this.queryRetrieveSopClasses = queryRetrieveSopClasses;
    }

    public int getMaxPduLength() { return maxPduLength; }
    public void setMaxPduLength(int maxPduLength) { this.maxPduLength = maxPduLength; }

    public String getImplementationClassUid() { return implementationClassUid; }
    public void setImplementationClassUid(String implementationClassUid) {
        this.implementationClassUid = implementationClassUid;
    }

    public List<String> getMoveRetrieveSopClasses() { return moveRetrieveSopClasses; }
    public void setMoveRetrieveSopClasses(List<String> moveRetrieveSopClasses) {
        this.moveRetrieveSopClasses = moveRetrieveSopClasses;
    }

    public List<String> getStorageSopClasses() { return storageSopClasses; }
    public void setStorageSopClasses(List<String> storageSopClasses) {
        this.storageSopClasses = storageSopClasses;
    }

    public String getLocalMoveDestAet() { return localMoveDestAet; }
    public void setLocalMoveDestAet(String localMoveDestAet) {
        this.localMoveDestAet = localMoveDestAet;
    }

    public String getLocalMoveDestHost() { return localMoveDestHost; }
    public void setLocalMoveDestHost(String localMoveDestHost) {
        this.localMoveDestHost = localMoveDestHost;
    }

    public int getLocalMoveDestPort() { return localMoveDestPort; }
    public void setLocalMoveDestPort(int localMoveDestPort) {
        this.localMoveDestPort = localMoveDestPort;
    }

    public int getMaxLocalStoreAssociations() { return maxLocalStoreAssociations; }
    public void setMaxLocalStoreAssociations(int maxLocalStoreAssociations) {
        this.maxLocalStoreAssociations = maxLocalStoreAssociations;
    }

    public String getLocalPacsAet() { return localPacsAet; }
    public void setLocalPacsAet(String localPacsAet) { this.localPacsAet = localPacsAet; }

    public String getLocalPacsHost() { return localPacsHost; }
    public void setLocalPacsHost(String localPacsHost) { this.localPacsHost = localPacsHost; }

    public int getLocalPacsPort() { return localPacsPort; }
    public void setLocalPacsPort(int localPacsPort) { this.localPacsPort = localPacsPort; }

    public List<String> getLocalStoreTransferSyntaxes() { return localStoreTransferSyntaxes; }
    public void setLocalStoreTransferSyntaxes(List<String> localStoreTransferSyntaxes) {
        this.localStoreTransferSyntaxes = localStoreTransferSyntaxes == null
                ? new ArrayList<>()
                : localStoreTransferSyntaxes;
    }
}