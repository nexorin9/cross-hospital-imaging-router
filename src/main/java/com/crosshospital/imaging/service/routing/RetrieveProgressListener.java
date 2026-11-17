package com.crosshospital.imaging.service.routing;

/**
 * C-MOVE 拉回进度的回传监听器 —— task 14 交付物。
 *
 * <p>实现约定（三条，违反其一即视为破坏「失败不静默改数据」）：
 * <ol>
 *   <li><b>不得阻塞调用线程</b>。本监听器在读 C-MOVE-RSP 的循环里被同步调用；
 *       若监听方做慢 I/O，会直接吃掉 {@code RoutingConfigProps.retrieveTimeoutMs}
 *       的预算，把一次顺利的拉回拖成 {@code MOVE_TIMEOUT}。落审计请用异步或
 *       只写内存缓冲。</li>
 *   <li><b>不得抛异常冒泡</b>。进度回传是<b>旁证</b>，不是业务状态推进；
 *       监听方失败不应把已经拉回来的影像判成失败。本接口的实现应自行吞掉异常
 *       （{@link RetrieveAuditProgressListener} 即如此），让 C-MOVE 主流程继续。</li>
 *   <li><b>不得据此推进任务状态</b>。{@code queued→querying→pulling→stored→notified}
 *       的推进归 {@code FetchTaskService}（task 20）的五态机所有；
 *       进度帧只是给它提供「要不要现在落一行审计」的依据。</li>
 * </ol>
 */
@FunctionalInterface
public interface RetrieveProgressListener {

    /**
     * 每收到一帧 C-MOVE-RSP（Pending 或终态）回调一次。
     *
     * @param progress 进度快照，见 {@link RetrieveProgress}
     */
    void onProgress(RetrieveProgress progress);

    /** 空实现：默认不开启进度回传。 */
    static RetrieveProgressListener noop() {
        return progress -> { };
    }
}