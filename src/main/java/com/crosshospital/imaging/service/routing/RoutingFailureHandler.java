package com.crosshospital.imaging.service.routing;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 路由失败的**统一出口** —— 分类 → 落审计 → 进死信 → 推进任务终态，三件事一处做。
 *
 * <p>task 13/14/15 各自在自己的 catch 里落审计，逻辑正确但有三点不足：
 * <ol>
 *   <li><b>失败码口径不一</b>：三处各写各的字面串，运维查审计要背三套词表；</li>
 *   <li><b>漏了死信</b>：重试耗尽的失败只停在审计表里，没有一处把它送进 DLQ，
 *       信息科只能靠翻审计表找「哪些单子卡住了」；</li>
 *   <li><b>重复落行</b>：C-STORE 的部分失败路径会被内外两层 catch 各记一次。</li>
 * </ol>
 * 本类把这三点收成一条路：{@link FailureCodeRegistry} 定码（唯一词表）→
 * {@link FetchTaskFailurePort} 推任务终态并问清「是不是终局」→
 * （只有终局失败才）{@link AuditService} 落一行 + {@link DlqEnqueuePort} 入死信。
 *
 * <p><b>为什么先问任务层再决定要不要进死信</b>：任务层在可重试失败时会把任务退回
 * {@code queued} 等退避重跑，并回一个 {@link TaskFailureOutcome#RETRY_SCHEDULED}。
 * 这时若照旧入死信，信息科会看到一条「没人管的外院故障」，其实任务正在自动重跑 ——
 * 一条假待办比没有待办更糟（它会让信息科白跑一趟，还以为外院真挂了）。
 *
 * <h2>不静默原则（本类存在的全部意义）</h2>
 * <p>三条副作用<b>各自</b>兜异常、<b>各自</b>在报告里留痕：
 * 审计库挂了不影响死信入队，死信挂了不影响任务推终态。原因很简单 ——
 * 调阅已经失败了，排障链路再断一环，运维看到的就只剩一句「失败了」，
 * 连「为什么失败、卡在哪、该找谁」都没有。<b>本类自己绝不抛异常</b>：
 * 排障失败不该把调阅流程本身炸掉。
 *
 * <h2>源主路径证据</h2>
 * <p>{@code github_ref/dcm4che-net/.../ConnectionMonitor.java:49-58} 的
 * {@code onConnectionFailed(conn, remoteConn, socket, Throwable e)} ——
 * 源产品在建连失败的<b>每一处</b>（{@code Connection.java:1080-1089} 的
 * {@code GeneralSecurityException} 与 {@code IOException} 两个 catch）都回调同一个
 * 失败事件，而不是各写各的日志。本类即这条「失败只有一个出口」的产品化改写。
 *
 * <p>端口注入说明：两个端口在本轮（task 16）尚无实现（task 20 / task 22 落地），
 * 因此用 setter 注入而非构造注入；未注入时对应副作用标记为「未发生」，
 * 其余副作用照常执行 —— 保证本类单独可用、可测。
 */
@Service
@Profile("!test")
public class RoutingFailureHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingFailureHandler.class);

    /** 失败消息写入 DLQ 时的截断长度（信息科要的是可读，不是完整堆栈）。 */
    private static final int MESSAGE_MAX_CHARS = 2000;

    private final AuditService auditService;

    private volatile FetchTaskFailurePort fetchTaskFailurePort;
    private volatile DlqEnqueuePort dlqEnqueuePort;

    public RoutingFailureHandler(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 注入任务终态推进端口（task 20 的 FetchTaskService 实现）。
     *
     * <p>setter 注入：{@code FetchTaskService} 的协议子步执行器依赖 DICOM 协议栈，
     * 而协议栈的失败出口是本类 —— 构造注入会绕成环，setter 注入才解得开。
     */
    @Autowired(required = false)
    public void setFetchTaskFailurePort(FetchTaskFailurePort port) {
        this.fetchTaskFailurePort = port;
    }

    /** 注入死信入队端口（task 22 的 DlqService 实现）。 */
    @Autowired(required = false)
    public void setDlqEnqueuePort(DlqEnqueuePort port) {
        this.dlqEnqueuePort = port;
    }

    /**
     * 处置一次路由失败。**本方法不抛异常。**
     *
     * @param ctx         失败定位上下文
     * @param failure     原始异常（用于分类与留消息）
     * @param retryCount  已重试次数（0 = 首次即失败）
     * @return 处置报告（三条副作用各自的成败）
     */
    public RoutingFailureReport handle(RoutingFailureContext ctx, Throwable failure, int retryCount) {
        FailureCodeRegistry code = FailureCodeRegistry.classify(failure);
        String message = summarize(failure);
        RoutingFailureContext safeCtx = ctx == null
                ? new RoutingFailureContext(RoutingFailureContext.STEP_QUERY, null, null, null, null, null)
                : ctx;

        // 先问任务层「这次失败是不是终局」—— 顺序不能反：
        // 任务层若把任务退回 queued 等退避重跑，这次失败就不是终局失败，
        // 此时既不能落终局审计、也不能进死信（那是一条假待办：信息科会白跑一趟，
        // 还以为外院真挂了，而任务其实正在自动重试）。见 FetchTaskFailurePort 的契约。
        TaskPush push = pushTaskFailure(safeCtx, code);
        boolean terminal = push.outcome() != TaskFailureOutcome.RETRY_SCHEDULED;

        boolean auditRecorded = terminal && recordAudit(safeCtx, code, message, retryCount);
        boolean dlqEnqueued = terminal && enqueueDlq(safeCtx, code, message, retryCount);
        boolean taskMarkedFailed = push.pushed();

        RoutingFailureReport report = new RoutingFailureReport(
                safeCtx.step(), safeCtx.toAet(), safeCtx.patientMpi(), safeCtx.studyUid(),
                code, message, Math.max(0, retryCount),
                auditRecorded, dlqEnqueued, taskMarkedFailed);

        LOG.warn("路由失败已处置: step={} peer={} study={} code={} retry={} {} 收场={} audit={} dlq={} task={}",
                report.step(), report.peerAet(), report.studyUid(), report.failureCode(),
                report.retryCount(), code.retryable() ? "可重试" : "不可重试",
                push.outcome(), auditRecorded, dlqEnqueued, taskMarkedFailed);
        return report;
    }

    /**
     * 落一行审计。{@code status_terminal} 取步骤终态（query_failed / retrieve_failed /
     * store_failed），{@code failure_code} 取统一词表的值。
     *
     * <p>{@code retry_count} 落真实已重试次数 —— 信息科据此区分「一次就挂」
     * 与「重试三次还挂」，两者的处置优先级完全不同。
     */
    private boolean recordAudit(RoutingFailureContext ctx, FailureCodeRegistry code,
                                String message, int retryCount) {
        if (auditService == null) {
            return false;
        }
        try {
            auditService.record(new AuditRow()
                    .withWho(ctx.auditWho())
                    .withFromAet(ctx.fromAet())
                    .withToAet(ctx.toAet())
                    .withStudyUid(ctx.studyUid())
                    .withRetryCount(Math.max(0, retryCount))
                    .withStatusTerminal(ctx.statusTerminal())
                    .withFailureCode(code.code()));
            return true;
        } catch (RuntimeException e) {
            LOG.warn("失败审计写入失败（已吞掉，继续入死信）: {}", e.getMessage());
            return false;
        }
    }

    /** 入死信。端口未注入（task 22 尚未落地）时返回 false，不算失败。 */
    private boolean enqueueDlq(RoutingFailureContext ctx, FailureCodeRegistry code,
                               String message, int retryCount) {
        DlqEnqueuePort port = dlqEnqueuePort;
        if (port == null) {
            LOG.debug("死信端口未接入，跳过入队（task 22 落地后生效）: {}", code.code());
            return false;
        }
        try {
            port.enqueue(DlqEnqueueRequest.of(ctx, code, message, retryCount));
            return true;
        } catch (RuntimeException e) {
            LOG.warn("死信入队失败（已吞掉，调阅失败原因不因此丢失）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 推进任务终态，并问清「这次失败是不是终局」。
     *
     * <p>本服务<b>不</b>在路由层直接改 {@code imaging_fetch_tasks}：
     * 状态推进必须过五态机的跃迁校验（{@code stored → querying} 之类的非法跃迁要拒绝），
     * 那是 task 20 {@code FetchTaskService} 的职责。本类只负责把失败码交出去。
     *
     * <p>端口未接入 / 无 {@code taskId} / 端口抛异常时，一律按
     * {@link TaskFailureOutcome#TERMINAL_FAILURE} 收场（保守：宁可多留一条待办，
     * 也不可让一次真实失败从信息科的视野里消失）。
     */
    private TaskPush pushTaskFailure(RoutingFailureContext ctx, FailureCodeRegistry code) {
        FetchTaskFailurePort port = fetchTaskFailurePort;
        String taskId = ctx.taskId();
        if (port == null || taskId == null || taskId.isBlank()) {
            return new TaskPush(TaskFailureOutcome.TERMINAL_FAILURE, false);
        }
        try {
            TaskFailureOutcome outcome = port.markFailed(taskId, code.code());
            return new TaskPush(outcome == null ? TaskFailureOutcome.TERMINAL_FAILURE : outcome, true);
        } catch (RuntimeException e) {
            LOG.warn("任务推失败终态失败（已吞掉，保留原始失败原因）: {}", e.getMessage());
            return new TaskPush(TaskFailureOutcome.TERMINAL_FAILURE, false);
        }
    }

    /** 任务终态推进的结果：收场方式 + 是否真的推到了任务层。 */
    private record TaskPush(TaskFailureOutcome outcome, boolean pushed) {
    }

    /** 失败消息取整条链上最外层的可读文本，截断到 {@value #MESSAGE_MAX_CHARS} 字符。 */
    private static String summarize(Throwable failure) {
        if (failure == null) {
            return "(无异常对象)";
        }
        String text = failure.getMessage();
        if (text == null || text.isBlank()) {
            text = failure.getClass().getSimpleName();
        }
        String cause = failure.getCause() == null ? "" : " | cause=" + failure.getCause().getClass().getSimpleName();
        String full = text + cause;
        return full.length() <= MESSAGE_MAX_CHARS
                ? full
                : full.substring(0, MESSAGE_MAX_CHARS) + "…(截断)";
    }
}