package com.crosshospital.imaging.service.routing;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把 C-MOVE 的 sub-operation 进度按 <b>每完成 10% 落一行</b> 写进 {@code audit_imaging_fetch}。
 *
 * <h2>为什么要 10% 一行，而不是每帧一行</h2>
 * <p>{@code audit_imaging_fetch} 是 append-only 且带链式 checksum 的表（task 24/25），
 * 每写一行都要 {@code SELECT MAX(id)} 拿 prev_checksum 再插一行。一个 3000 instance 的
 * study 在部分 PACS 上会回上千帧 Pending，逐帧落审计会把审计表撑爆、且 checksum 链
 * 被无信息的行淹没。10% 一行让一次拉回最多产生 <b>11 行</b>（0%..100%），
 * 足以回答「拉了多久、卡在哪一段、失败集中在哪个阶段」，又不会淹没审计。
 *
 * <h2>去重规则</h2>
 * <ul>
 *   <li>同一 {@link RetrieveProgress#bucket()}（0..10）只落一行；分桶不前进就不写。</li>
 *   <li>终态帧<b>一定</b>落一行（若终态分桶与上一行相同，仍以
 *       {@code status_terminal} 不同的语义补一行），否则「拉完了但审计里没有终态行」
 *       会让 {@code task 31} 的审计查询看不出这次拉阅的结局。</li>
 * </ul>
 *
 * <h2>异常语义</h2>
 * <p>审计写失败<b>不冒泡</b>（{@link RetrieveProgressListener} 约定第 2 条）：
 * 进度回传是旁证，审计库短暂不可用不应把已经拉回本端的影像判成拉阅失败。
 * 写失败只降级为 {@code LOG.warn}，不改变 C-MOVE 的返回值与状态推进。
 */
public final class RetrieveAuditProgressListener implements RetrieveProgressListener {

    private static final Logger LOG = LoggerFactory.getLogger(RetrieveAuditProgressListener.class);

    /** 进度行在 {@code status_terminal} 里的固定取值。 */
    public static final String STATUS_RETRIEVE_PROGRESS = "retrieve_progress";
    /** 终态行在 {@code status_terminal} 里的取值；{@code failureCode} 非空即失败。 */
    public static final String STATUS_RETRIEVE_FINISHED = "retrieve_finished";

    private final AuditService auditService;
    private final String who;

    /** 已落审计的最大分桶；初始 -1 表示尚未落过任何一行（含 0% 那行）。 */
    private final AtomicInteger lastAuditedBucket = new AtomicInteger(-1);

    public RetrieveAuditProgressListener(AuditService auditService, String who) {
        if (auditService == null) {
            throw new IllegalArgumentException("auditService 不能为空");
        }
        this.auditService = auditService;
        this.who = (who == null || who.isBlank()) ? "system" : who;
    }

    @Override
    public void onProgress(RetrieveProgress progress) {
        if (progress == null) {
            return;
        }
        AuditRow row;
        synchronized (this) {
            // 分桶不前进且不是终态 → 该帧没有新信息，不落审计。
            // 首帧 0% 会落一行，作为「拉回开始」的锚点。
            int bucket = progress.bucket();
            if (!progress.terminal() && bucket <= lastAuditedBucket.get()) {
                return;
            }
            lastAuditedBucket.set(Math.max(lastAuditedBucket.get(), bucket));
            row = new AuditRow()
                    .withWho(who)
                    .withFromAet("local")
                    .withToAet(progress.peerAet())
                    .withStudyUid(progress.studyUid())
                    .withRetryCount(0)
                    .withStatusTerminal(progress.terminal()
                            ? STATUS_RETRIEVE_FINISHED : STATUS_RETRIEVE_PROGRESS)
                    .withFailureCode(terminalFailureCode(progress));
        }
        try {
            auditService.record(row);
            LOG.debug("retrieve progress audited peer={} status_terminal={}",
                    row.getToAet(), row.getStatusTerminal());
        } catch (RuntimeException e) {
            // 审计是旁证：写不进去不能把已拉回的影像判成失败
            LOG.warn("retrieve 进度审计写入失败（已吞掉，不影响拉回结果）: {}",
                    e.getMessage());
        }
    }

    /**
     * 终态行的失败码：仅 0xB000 / 0xFE00 / 0xFFxx 之外且存在 failed·warning 时落
     * {@code partial_failure}；终态 Success 不落失败码。
     */
    private static String terminalFailureCode(RetrieveProgress progress) {
        if (!progress.terminal()) {
            return null;
        }
        int status = progress.statusHex() == null ? 0 : progress.statusHex();
        boolean subOpLoss = progress.failed() > 0 || progress.warning() > 0
                || progress.remaining() > 0;
        if (status == 0) {
            return null;
        }
        return subOpLoss ? "partial_failure" : "retrieve_failed";
    }

    /** 已落审计的最大分桶（测试与运维查看用）。 */
    public int lastAuditedBucket() {
        return lastAuditedBucket.get();
    }

    /** 重置分桶状态；下一次拉回从 0% 重新计。 */
    public void reset() {
        lastAuditedBucket.set(-1);
    }
}