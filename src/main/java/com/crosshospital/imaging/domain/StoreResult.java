package com.crosshospital.imaging.domain;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一次跨院 C-STORE 写入本院 PACS 的产出 —— 不可变值对象。
 *
 * <p>本对象是 DICOM 路由主路径第三步（写入本院 PACS）的标准产物；它回答
 * 信息科最关心的一个问题：**「外院那套影像，到底有没有真的落进本院 PACS？」**
 *
 * <pre>
 *   C-FIND  (task 13)  →  StudyRef 列表
 *   C-MOVE  (task 14)  →  RetrieveResult  （字节流还在本服务内存里）
 *   C-STORE (task 15)  →  StoreResult 本类 ← 影像真正进了本院 PACS 缓存
 * </pre>
 *
 * <h2>字段语义</h2>
 * <ul>
 *   <li>{@link #getSopInstanceCount()} —— 本院 PACS 回 {@code Success(0x0000)} 的 instance 数。
 *       这是**唯一**能证明「影像真进去了」的计数；本服务自己 buffers 了多少字节
 *       （{@link #getRequestedCount()}）并不等于进了 PACS。</li>
 *   <li>{@link #getSopInstanceUids()} —— 与 {@code getSopInstanceCount()} 一一对应的
 *       SOPInstanceUID，<b>逐字来自外院</b>，不做任何重命名（互认场景要求影像实体
 *       在两院 PACS 里指向同一个对象）。</li>
 *   <li>{@link #getSopClassUids()} —— 每个 instance 实际协商到的存储 SOP Class；
 *       {@link #distinctSopClassUids()} 给出去重集合，落审计的 {@code sop_class_uid} 列用它。</li>
 *   <li>{@link #getTransferSyntaxes()} —— 每个 instance 实际协商到的传输语法。
 *       同一个 study 里 CT/MR 走不同传输语法是常态，不做归一。</li>
 *   <li>{@link #getTotalBytes()} —— 成功写入的 instance 字节数之和；</li>
 *   <li>{@link #getDurationMs()} —— 从建关联到最后一个 C-STORE-RSP 终态的端到端耗时。</li>
 * </ul>
 *
 * <h2>部分失败语义</h2>
 * <p>{@link DicomRoutingService} 只在**全部** instance 都拿到 Success 时才构造本对象返回；
 * 部分失败会抛 {@code RoutingFailureException(PARTIAL_FAILURE)}，全部失败抛
 * {@code STORE_FAILED} —— 理由是：让 {@code FetchTask} 推进到 {@code stored}
 * 就等于向 HIS 宣告「影像已在本院 PACS」，而实际上有一半没进去，属于错误的业务状态推进。
 * 本对象的 {@link #getFailedCount()} / {@link #isTerminalSuccess()} 仍然保留，
 * 供 {@code RoutingFailureHandler}（task 16）与运维看板复盘这次到底写进去几条。
 *
 * <h2>源主路径证据</h2>
 * <p>dcm4che README「Utilities」中的 {@code storescu} —— 本类是其服务化产物的封装；
 * {@code Association.cstore(cuid, iuid, priority, DataWriter, tsuid)} 的
 * {@code C-STORE-RSP} 终态状态位（PS3.4 §C.4.3.1.2.2）是
 * {@link #getTerminalStatusHex()} 的来源。
 */
public final class StoreResult {

    private final String targetPacsAet;
    private final String targetPacsHost;
    private final int requestedCount;
    private final int skippedCount;
    private final List<String> sopInstanceUids;
    private final List<String> sopClassUids;
    private final List<String> transferSyntaxes;
    private final long totalBytes;
    private final long durationMs;
    private final Integer terminalStatusHex;

    private StoreResult(Builder b) {
        this.targetPacsAet = b.targetPacsAet;
        this.targetPacsHost = b.targetPacsHost;
        this.requestedCount = b.requestedCount == null ? 0 : b.requestedCount;
        this.sopInstanceUids = b.sopInstanceUids == null
                ? List.of()
                : List.copyOf(b.sopInstanceUids);
        this.sopClassUids = b.sopClassUids == null ? List.of() : List.copyOf(b.sopClassUids);
        this.transferSyntaxes = b.transferSyntaxes == null ? List.of() : List.copyOf(b.transferSyntaxes);
        if (this.sopInstanceUids.size() != this.sopClassUids.size()
                || this.sopInstanceUids.size() != this.transferSyntaxes.size()) {
            throw new IllegalArgumentException(
                    "sopInstanceUids / sopClassUids / transferSyntaxes 长度必须一致: "
                            + sopInstanceUids.size() + "/" + sopClassUids.size()
                            + "/" + transferSyntaxes.size());
        }
        // 未显式给出「被跳过」数时按 输入 - 成功 反推；输入少于成功时夹到 0（不出现负数）
        this.skippedCount = b.skippedCount == null
                ? Math.max(0, this.requestedCount - this.sopInstanceUids.size())
                : b.skippedCount;
        this.totalBytes = b.totalBytes == null ? 0L : b.totalBytes;
        this.durationMs = b.durationMs == null ? 0L : b.durationMs;
        this.terminalStatusHex = b.terminalStatusHex;
    }

    public String getTargetPacsAet() { return targetPacsAet; }
    public String getTargetPacsHost() { return targetPacsHost; }

    /** 调用方交给我们要写的 instance 总数。 */
    public int getRequestedCount() { return requestedCount; }

    /** 本院 PACS 回 Success 的 instance 数 —— 「影像真进去了」的证据。 */
    public int getSopInstanceCount() { return sopInstanceUids.size(); }

    /** 因 SOP Class 不受支持 / instance 不可解析等原因，在建关联前就没发的 instance 数。 */
    public int getSkippedCount() { return skippedCount; }

    /** 发了 C-STORE-RQ 但本院 PACS 没回 Success 的 instance 数。 */
    public int getFailedCount() {
        return Math.max(0, requestedCount - skippedCount - sopInstanceUids.size());
    }

    /** 与 {@link #getSopInstanceCount()} 一一对应的外院 SOPInstanceUID，逐字透传。 */
    public List<String> getSopInstanceUids() { return sopInstanceUids; }
    public List<String> getSopClassUids() { return sopClassUids; }
    public List<String> getTransferSyntaxes() { return transferSyntaxes; }

    public long getTotalBytes() { return totalBytes; }
    public long getDurationMs() { return durationMs; }

    /** 最后一个 C-STORE-RSP 终态状态码十六进制（0x0000 = 全部成功）。 */
    public Integer getTerminalStatusHex() { return terminalStatusHex; }

    /** 本次写入是否有任何一条 instance 进了本院 PACS。 */
    public boolean isNonEmpty() { return !sopInstanceUids.isEmpty(); }

    /** 全部 instance 一次成功（{@code true} 只在 service 全成功路径出现）。 */
    public boolean isTerminalSuccess() {
        return getFailedCount() == 0 && skippedCount == 0
                && sopInstanceUids.size() == requestedCount;
    }

    /** 去重后的存储 SOP Class 集合（保序）—— 落审计 {@code sop_class_uid} 列用它。 */
    public List<String> distinctSopClassUids() {
        List<String> out = new java.util.ArrayList<>();
        for (String sop : sopClassUids) {
            if (sop != null && !out.contains(sop)) {
                out.add(sop);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** 去重后的传输语法集合（保序）—— 落审计 {@code transfer_syntax} 列用它。 */
    public List<String> distinctTransferSyntaxes() {
        List<String> out = new java.util.ArrayList<>();
        for (String ts : transferSyntaxes) {
            if (ts != null && !out.contains(ts)) {
                out.add(ts);
            }
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StoreResult other)) return false;
        return requestedCount == other.requestedCount
                && skippedCount == other.skippedCount
                && totalBytes == other.totalBytes
                && durationMs == other.durationMs
                && Objects.equals(targetPacsAet, other.targetPacsAet)
                && Objects.equals(targetPacsHost, other.targetPacsHost)
                && Objects.equals(terminalStatusHex, other.terminalStatusHex)
                && sopInstanceUids.equals(other.sopInstanceUids)
                && sopClassUids.equals(other.sopClassUids)
                && transferSyntaxes.equals(other.transferSyntaxes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetPacsAet, targetPacsHost, requestedCount, skippedCount,
                sopInstanceUids, sopClassUids, transferSyntaxes, totalBytes, durationMs,
                terminalStatusHex);
    }

    @Override
    public String toString() {
        return "StoreResult{target=" + targetPacsAet + '@' + targetPacsHost
                + " requested=" + requestedCount
                + " stored=" + sopInstanceUids.size()
                + " skipped=" + skippedCount
                + " failed=" + getFailedCount()
                + " bytes=" + totalBytes
                + " durationMs=" + durationMs
                + " status=0x" + (terminalStatusHex == null
                        ? "----" : String.format("%04X", terminalStatusHex))
                + '}';
    }

    public static Builder builder() { return new Builder(); }

    /** 零命中占位（正常路径不会返回它；供复盘与看板渲染「什么都没写进去」用）。 */
    public static StoreResult empty(String targetPacsAet, String targetPacsHost) {
        return builder()
                .targetPacsAet(targetPacsAet)
                .targetPacsHost(targetPacsHost)
                .requestedCount(0)
                .skippedCount(0)
                .durationMs(0L)
                .build();
    }

    public static final class Builder {
        private String targetPacsAet;
        private String targetPacsHost;
        private Integer requestedCount;
        private Integer skippedCount;
        private List<String> sopInstanceUids;
        private List<String> sopClassUids;
        private List<String> transferSyntaxes;
        private Long totalBytes;
        private Long durationMs;
        private Integer terminalStatusHex;

        public Builder targetPacsAet(String v) { this.targetPacsAet = v; return this; }
        public Builder targetPacsHost(String v) { this.targetPacsHost = v; return this; }
        public Builder requestedCount(int v) { this.requestedCount = v; return this; }
        public Builder skippedCount(int v) { this.skippedCount = v; return this; }
        public Builder sopInstanceUids(List<String> v) { this.sopInstanceUids = v; return this; }
        public Builder sopClassUids(List<String> v) { this.sopClassUids = v; return this; }
        public Builder transferSyntaxes(List<String> v) { this.transferSyntaxes = v; return this; }
        public Builder totalBytes(long v) { this.totalBytes = v; return this; }
        public Builder durationMs(long v) { this.durationMs = v; return this; }
        public Builder terminalStatusHex(Integer v) { this.terminalStatusHex = v; return this; }

        public StoreResult build() { return new StoreResult(this); }
    }
}