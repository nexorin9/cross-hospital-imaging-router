package com.crosshospital.imaging.service.routing;

/**
 * 任务侧对「这次失败怎么收场」的回答 —— 失败处置链据此决定要不要记终局账。
 *
 * <p>task 21 引入任务级重试之后，一次失败有了<b>两种</b>收场方式，
 * 而「失败」本身不再等于「终局失败」：
 * <ul>
 *   <li>失败码可重试 → 任务退回 {@code queued} 等退避后重跑 ——
 *       <b>不是</b>终局失败：不该进死信；</li>
 *   <li>不可重试 / 重试预算耗尽 → 任务判死 ——
 *       落终局失败审计 + 进死信，信息科据此人工处置。</li>
 * </ul>
 *
 * <p><b>为什么死信表尤其不能收可重试的失败</b>：死信是信息科的「人工处置待办」
 * （task 22 会在它上面建告警 webhook 与处置界面）。一条还在自动重跑、
 * 甚至很可能重跑就成功的任务进了这张表，就是一条<b>假待办</b> ——
 * 信息科白跑一趟，还以为外院真的挂了。
 *
 * <p>这与 {@code dlq_fetch_tasks} 迁移脚本自己写的口径一致：
 * 「当调阅任务<b>重试耗尽</b>或遇到不可恢复错误时入队」。
 *
 * @see com.crosshospital.imaging.service.task.RetryScheduleResult
 */
public enum TaskFailureOutcome {

    /**
     * 终局失败：落一行终局失败审计 + 进死信。
     *
     * <p>含两种情形：任务确实被判成了 failed；以及没有任务可推
     * （无 taskId / 任务不存在 / 任务已 notified 不许翻成失败）——
     * 后者任务状态没被本服务改动，但这次协议失败仍需留痕并让信息科看得见。
     */
    TERMINAL_FAILURE,

    /**
     * 已安排任务级重试：任务已退回 {@code queued} 等退避后重跑。
     * <b>不是</b>终局失败 —— <b>不进死信</b>（重试那一行审计已经记了原因）。
     */
    RETRY_SCHEDULED
}
