package com.crosshospital.imaging.service.routing;

/**
 * 一次 C-MOVE 的 sub-operation 进度快照 —— 不可变值对象。
 *
 * <h2>数据来源（DICOM PS3.4 §C.4.1.1.1）</h2>
 * <p>C-MOVE-RSP 的 Pending 与终态帧都在<b>命令字节组</b>（不是 dataset）里带四个计数：
 * <ul>
 *   <li>(0020,0xxx) {@code NumberOfRemainingSuboperations} → {@link #remaining()}</li>
 *   <li>(0020,0xxx) {@code NumberOfCompletedSuboperations} → {@link #completed()}</li>
 *   <li>(0020,0xxx) {@code NumberOfFailedSuboperations}   → {@link #failed()}</li>
 *   <li>(0020,0xxx) {@code NumberOfWarningSuboperations}  → {@link #warning()}</li>
 * </ul>
 *
 * <h2>为什么进度要「带出」而不是只落日志</h2>
 * <p>跨院调阅一个 study 常见几百到几千个 instance，WADO-RS / C-MOVE 在院内网络上动辄几十秒到
 * 几分钟。这段时间里影像科技师唯一能问的是「到第几个了、还剩多少、拉下来的是不是能读」。
 * 四个计数是<b>外院 PACS 自己的权威口径</b>，比本端数「已经收到几个 byte[]」更可信 ——
 * 因为它包含了本端还没收到的、正在外院排队发送的量。所以本类对外暴露外院口径，
 * 本端 sink 的字节数只作旁证（{@link RetrieveResult#getInstanceCount()}）。
 *
 * <h2>百分比的坑</h2>
 * <p>{@link #percentComplete()} 用 {@code completed / (completed + remaining + failed + warning)}
 * 而不是 {@code completed / expectedTotal}：外院不一定在首帧就报总数，
 * 且分母随外院侧推进而变化。因此本值是<b>单调不减的估计</b>，不是精确百分比；
 * 监听方按 {@link #bucket()}（10% 分桶）落审计即可，不要拿它做精确进度条。
 */
public final class RetrieveProgress {

    private final String peerAet;
    private final String studyUid;
    private final int remaining;
    private final int completed;
    private final int failed;
    private final int warning;
    private final int receivedSoFar;
    private final boolean terminal;
    private final Integer statusHex;

    private RetrieveProgress(String peerAet, String studyUid,
                             int remaining, int completed, int failed, int warning,
                             int receivedSoFar, boolean terminal, Integer statusHex) {
        this.peerAet = peerAet;
        this.studyUid = studyUid;
        this.remaining = Math.max(0, remaining);
        this.completed = Math.max(0, completed);
        this.failed = Math.max(0, failed);
        this.warning = Math.max(0, warning);
        this.receivedSoFar = Math.max(0, receivedSoFar);
        this.terminal = terminal;
        this.statusHex = statusHex;
    }

    /** 一帧 Pending 携带的计数快照（尚未终态）。 */
    public static RetrieveProgress pending(String peerAet, String studyUid,
                                           int remaining, int completed,
                                           int failed, int warning, int receivedSoFar) {
        return new RetrieveProgress(peerAet, studyUid, remaining, completed,
                failed, warning, receivedSoFar, false, null);
    }

    /** 终态帧快照；{@code statusHex} 为 C-MOVE-RSP 命令字组里的 Status。 */
    public static RetrieveProgress terminal(String peerAet, String studyUid,
                                            int remaining, int completed,
                                            int failed, int warning,
                                            int receivedSoFar, int statusHex) {
        return new RetrieveProgress(peerAet, studyUid, remaining, completed,
                failed, warning, receivedSoFar, true, statusHex);
    }

    public String peerAet() { return peerAet; }
    public String studyUid() { return studyUid; }
    /** 外院口径：尚未发起的 sub-operation 数。 */
    public int remaining() { return remaining; }
    /** 外院口径：已成功完成（含已推送到本端）的 sub-operation 数。 */
    public int completed() { return completed; }
    /** 外院口径：失败的 sub-operation 数（对应 C-STORE-SCP 回非 Success）。 */
    public int failed() { return failed; }
    /** 外院口径：警告但被接受的 sub-operation 数（PS3.4 §C.4.1.1.1.6 与 failed 同语义）。 */
    public int warning() { return warning; }
    /** 本端 sink 已实际收到的 instance 数（旁证，非权威）。 */
    public int receivedSoFar() { return receivedSoFar; }
    /** 是否为终态帧。 */
    public boolean terminal() { return terminal; }
    /** 终态 Status 十六进制（仅终态非 null）。 */
    public Integer statusHex() { return statusHex; }

    /** 外院本次报出的 sub-operation 总数；全为 0 时无法给出分母，返回 0。 */
    public int reportedTotal() {
        return completed + remaining + failed + warning;
    }

    /**
     * 估计完成度 0..100（单调不减的估计值，非精确百分比，见类注释）。
     * 外院未报任何计数时返回 0 而不是臆造 100。
     */
    public int percentComplete() {
        int total = reportedTotal();
        if (total <= 0) {
            return 0;
        }
        int pct = completed * 100 / total;
        return Math.min(100, Math.max(0, pct));
    }

    /** 10% 分桶序号 0..10；用于「每完成 10% 落一行审计」的去重键。 */
    public int bucket() { return percentComplete() / 10; }

    @Override
    public String toString() {
        return "RetrieveProgress{peer=" + peerAet
                + " study=" + studyUid
                + " completed=" + completed
                + " remaining=" + remaining
                + " failed=" + failed
                + " warning=" + warning
                + " received=" + receivedSoFar
                + " pct=" + percentComplete()
                + (terminal ? " TERMINAL status=0x" + String.format("%04X",
                        statusHex == null ? 0 : statusHex) : " PENDING")
                + '}';
    }
}