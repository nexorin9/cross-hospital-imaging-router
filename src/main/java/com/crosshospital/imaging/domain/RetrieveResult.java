package com.crosshospital.imaging.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一次跨院 C-MOVE 拉回的产出 —— 不可变值对象。
 *
 * <p>本对象是 DICOM 路由主路径第二步（C-MOVE）的标准产物；被后续 C-STORE 步骤
 * （task 15）按 {@link #getInstances()} 顺序写入本院 PACS 缓存，
 * 与上游 FETCH 任务的影像流不重命名 {@link #getSopInstanceUids()}：
 * <pre>
 *   C-FIND  (task 13)  →  StudyRef 列表
 *   C-MOVE  (task 14)  →  RetrieveResult 本类          ← 你在这里
 *   C-STORE (task 15)  →  StoreResult 落本院 PACS
 * </pre>
 *
 * <h2>字段语义</h2>
 * <ul>
 *   <li>{@link #getInstances()} —— 各 instance 的 DICOM Part-10 字节流。
 *       dcm4che 的 C-STORE-SCP 按接收顺序追加；保留顺序即保留外院 PACS 的 SERIE/SOP 顺序。
 *       调用方**不**应假定顺序对应外院 study / series / instance 的语义顺序，只保证稳定。</li>
 *   <li>{@link #getSopInstanceUids()} —— 与 {@code instances} 一一对应的
 *       SOPInstanceUID（来自每条 instance 的 {@code (0008,0018)}）；若 C-STORE
 *       收到的 dataset 缺 SOPInstanceUID，对应位为 {@code null}，**不**臆造。</li>
 *   <li>{@link #getTransferSyntax()} —— 本次实际协商的传输语法（C-STORE-RQ 头里写明的）；</li>
 *   <li>{@link #getTotalBytes()} —— 所有 instance 字节数之和；</li>
 *   <li>{@link #getDurationMs()} —— 从发 C-MOVE-RQ 到收 C-MOVE-RSP 终态的端到端耗时。</li>
 * </ul>
 *
 * <h2>部分失败语义</h2>
 * <p>本对象默认认为「被外院 PACS 通过 C-MOVE 推过来的 instance」全部已成功；
 * 若 C-MOVE-RSP 终态为 {@code Warning/OneOrMoreFailures}（{@code 0xB000}），本对象**仍**代表
 * 已落地的实例集合，剩余失败由调用方（task 16 {@code RoutingFailureHandler}）根据
 * C-MOVE-RSP 状态位另行处理 —— {@link #getSucceededCount()} 与 {@link #getFailedCount()}
 * 给出 sub-op 计数，调用方据此决定进 {@code PARTIAL_FAILURE} 还是直接放行。
 *
 * <h2>源主路径证据</h2>
 * <p>dcm4che README「Utilities」中的 {@code movescu} —— 本类是其服务化产物的封装。
 */
public final class RetrieveResult {

    private final List<byte[]> instances;
    private final List<String> sopInstanceUids;
    private final String transferSyntax;
    private final long totalBytes;
    private final long durationMs;
    private final int remainingSubOps;
    private final int completedSubOps;
    private final int failedSubOps;
    private final int warningSubOps;
    private final boolean terminalSuccess;
    private final Integer terminalStatusHex;

    private RetrieveResult(Builder b) {
        List<byte[]> inst = b.instances == null ? List.of() : List.copyOf(b.instances);
        List<String> sops = b.sopInstanceUids == null
                ? Collections.nCopies(inst.size(), null)
                : List.copyOf(b.sopInstanceUids);
        if (sops.size() != inst.size()) {
            throw new IllegalArgumentException(
                    "instances 与 sopInstanceUids 长度不一致: instances=" + inst.size()
                            + " sopUids=" + sops.size());
        }
        this.instances = inst;
        this.sopInstanceUids = sops;
        this.transferSyntax = b.transferSyntax;
        this.totalBytes = b.totalBytes == null ? sumBytes(inst) : b.totalBytes;
        this.durationMs = b.durationMs == null ? 0L : b.durationMs;
        this.remainingSubOps = b.remainingSubOps == null ? 0 : b.remainingSubOps;
        this.completedSubOps = b.completedSubOps == null ? inst.size() : b.completedSubOps;
        this.failedSubOps = b.failedSubOps == null ? 0 : b.failedSubOps;
        this.warningSubOps = b.warningSubOps == null ? 0 : b.warningSubOps;
        this.terminalSuccess = b.terminalSuccess;
        this.terminalStatusHex = b.terminalStatusHex;
    }

    private static long sumBytes(List<byte[]> instances) {
        long total = 0L;
        for (byte[] inst : instances) {
            total += inst == null ? 0 : inst.length;
        }
        return total;
    }

    public List<byte[]> getInstances() { return instances; }
    public List<String> getSopInstanceUids() { return sopInstanceUids; }
    public String getTransferSyntax() { return transferSyntax; }
    public long getTotalBytes() { return totalBytes; }
    public long getDurationMs() { return durationMs; }

    public int getInstanceCount() { return instances.size(); }
    public int getSucceededCount() { return completedSubOps; }
    public int getFailedCount() { return failedSubOps; }
    public int getWarningCount() { return warningSubOps; }
    public int getRemainingCount() { return remainingSubOps; }

    /** C-MOVE-RSP 终态是否为 {@code Success}（{@code 0x0000}）。 */
    public boolean isTerminalSuccess() { return terminalSuccess; }
    /** 终态状态码十六进制（如 0x0000 / 0xB000 / 0xA701），仅用于诊断与落审计。 */
    public Integer getTerminalStatusHex() { return terminalStatusHex; }

    /** 至少成功拉到一条 instance（区别于空列表 —— 空可能是链路异常）。 */
    public boolean isNonEmpty() { return !instances.isEmpty(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RetrieveResult other)) return false;
        return totalBytes == other.totalBytes
                && durationMs == other.durationMs
                && remainingSubOps == other.remainingSubOps
                && completedSubOps == other.completedSubOps
                && failedSubOps == other.failedSubOps
                && warningSubOps == other.warningSubOps
                && terminalSuccess == other.terminalSuccess
                && Objects.equals(transferSyntax, other.transferSyntax)
                && Objects.equals(terminalStatusHex, other.terminalStatusHex)
                && sopInstanceUids.equals(other.sopInstanceUids)
                && sameByteLists(other.instances, instances);
    }

    @Override
    public int hashCode() {
        int h = Objects.hash(sopInstanceUids, transferSyntax, totalBytes,
                durationMs, remainingSubOps, completedSubOps, failedSubOps,
                warningSubOps, terminalSuccess, terminalStatusHex);
        for (byte[] inst : instances) {
            h = 31 * h + Arrays.hashCode(inst);
        }
        return h;
    }

    /** byte[] 没有内容 equals —— 用 Arrays.equals 逐个比。 */
    private static boolean sameByteLists(List<byte[]> a, List<byte[]> b) {
        if (a == null || b == null) return a == b;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!Arrays.equals(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "RetrieveResult{instances=" + instances.size()
                + " succeeded=" + completedSubOps
                + " failed=" + failedSubOps
                + " warning=" + warningSubOps
                + " bytes=" + totalBytes
                + " ts='" + transferSyntax + '\''
                + " durationMs=" + durationMs
                + " terminalSuccess=" + terminalSuccess
                + " status=0x" + (terminalStatusHex == null
                        ? "----" : String.format("%04X", terminalStatusHex))
                + '}';
    }

    /** 简洁构造（不需要 sub-op 详情，常见于成功路径）。 */
    public static RetrieveResult of(List<byte[]> instances,
                                    List<String> sopInstanceUids,
                                    String transferSyntax,
                                    long durationMs) {
        Builder b = new Builder()
                .instances(instances)
                .sopInstanceUids(sopInstanceUids)
                .transferSyntax(transferSyntax)
                .durationMs(durationMs)
                .completedSubOps(instances == null ? 0 : instances.size());
        b.totalBytes = null;     // 让构造器自己 sum
        return b.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 显式把空集合当作零命中场景，而非链路异常 —— 由调用方负责业务语义判定。 */
    public static RetrieveResult empty(String transferSyntax, long durationMs) {
        return of(new ArrayList<>(), new ArrayList<>(), transferSyntax, durationMs);
    }

    public static final class Builder {
        private List<byte[]> instances;
        private List<String> sopInstanceUids;
        private String transferSyntax;
        private Long totalBytes;
        private Long durationMs;
        private Integer remainingSubOps;
        private Integer completedSubOps;
        private Integer failedSubOps;
        private Integer warningSubOps;
        private boolean terminalSuccess;
        private Integer terminalStatusHex;

        public Builder instances(List<byte[]> v) { this.instances = v; return this; }
        public Builder sopInstanceUids(List<String> v) { this.sopInstanceUids = v; return this; }
        public Builder transferSyntax(String v) { this.transferSyntax = v; return this; }
        public Builder totalBytes(long v) { this.totalBytes = v; return this; }
        public Builder durationMs(long v) { this.durationMs = v; return this; }
        public Builder remainingSubOps(int v) { this.remainingSubOps = v; return this; }
        public Builder completedSubOps(int v) { this.completedSubOps = v; return this; }
        public Builder failedSubOps(int v) { this.failedSubOps = v; return this; }
        public Builder warningSubOps(int v) { this.warningSubOps = v; return this; }
        public Builder terminalSuccess(boolean v) { this.terminalSuccess = v; return this; }
        public Builder terminalStatusHex(Integer v) { this.terminalStatusHex = v; return this; }

        public RetrieveResult build() {
            return new RetrieveResult(this);
        }
    }
}
