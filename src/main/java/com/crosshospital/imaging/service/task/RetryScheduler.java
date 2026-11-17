package com.crosshospital.imaging.service.task;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.service.routing.FailureCodeRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 任务级重试调度器（task 21）—— 决定「再试一次」、安排退避、把任务放回队列。
 *
 * <p><b>它接管的是哪一段流程</b>：一次跨院调阅在 {@code querying / pulling / stored}
 * 任一步失败后，本调度器判断这次失败是不是「过一会儿会自己好」的那类：
 * <ul>
 *   <li>是（可重试失败码 + 预算没花完）→ 把任务退回 {@code queued}、
 *       {@code retry_count + 1}、落一行 {@code task_retry_scheduled} 审计，
 *       退避到点后把任务重新投进执行队列；</li>
 *   <li>否 → 返回「不重试」，由失败处置链照原样推失败终态 + 进死信。</li>
 * </ul>
 *
 * <h2>为什么「退回 queued」不算破坏状态机</h2>
 * <p>五态机（{@code queued→querying→pulling→stored→notified}）约束的是
 * <b>业务状态向前推进</b>；而重试表达的是「这次还没成，我们再来一次」——
 * 影像一个字节都没进本院 PACS，业务状态本就该留在起点。
 * 所以这是一次<b>显式授权的回退</b>，不是一次状态跃迁：
 * 每次回退都落一行审计（失败码 + 第几次重试），信息科能一眼看出
 * 「这条任务被重试过几次、为什么」。细节见 {@link TaskRequeueService}。
 *
 * <h2>幂等：同一次失败会被问两次</h2>
 * <p>一次失败的路径有两条问询：路由失败出口（task 16 的失败处置链）问一次，
 * 任务状态机在捕获同一个异常时问一次。第二次必须拿到「已经安排过了」的答复，
 * 否则同一次失败会退避两次、{@code retry_count} 多加一次。
 * 本类用一张「待重试任务」表做这道闸：安排时登记，退避真正触发时销账。
 *
 * <h2>依赖方向（重要）</h2>
 * <p>本类<b>不</b>依赖 {@link FetchTaskService}：任务行怎么回退由
 * {@link TaskRequeueService} 负责，调度器只做「判定 + 排期」。
 * 反过来 {@code FetchTaskService} 在推失败终态之前要问本类「要不要再试」——
 * 若本类再构造注入 {@code FetchTaskService}，依赖就绕成环，容器启动即失败。
 *
 * <p>源主路径证据：通用分布式系统重试模式（指数退避 + 抖动 + 预算）；与 task 16 的
 * {@code DicomRoutingRetryDecorator} 分工明确 —— 装饰器重试「一次协议调用」，
 * 本类重试「整条任务」，抖动的职责在本类（装饰器的代码注释里写明「抖动留给 task 21」）。
 */
@Service
@Profile("!test")
public class RetryScheduler implements TaskRetryPort {

    private static final Logger LOG = LoggerFactory.getLogger(RetryScheduler.class);

    private final RetryPolicy policy;
    private final TaskRequeueService requeueService;
    private final RetryBudgetResolver budgetResolver;
    private final BackoffScheduler backoffScheduler;

    /** 已安排重试、尚未到点执行的任务 —— 同一次失败的幂等闸。 */
    private final Set<UUID> pendingRetries = ConcurrentHashMap.newKeySet();

    public RetryScheduler(RetryPolicy policy,
                          TaskRequeueService requeueService,
                          RetryBudgetResolver budgetResolver,
                          BackoffScheduler backoffScheduler) {
        if (policy == null || requeueService == null || budgetResolver == null
                || backoffScheduler == null) {
            throw new IllegalArgumentException("RetryScheduler 依赖不能为 null");
        }
        this.policy = policy;
        this.requeueService = requeueService;
        this.budgetResolver = budgetResolver;
        this.backoffScheduler = backoffScheduler;
    }

    // ==================================================================
    // TaskRetryPort 实现
    // ==================================================================

    @Override
    public RetryScheduleResult scheduleRetry(UUID taskId, String failureCode, String who) {
        if (taskId == null) {
            return RetryScheduleResult.notRetried("taskId 为空，不安排重试");
        }
        Optional<FetchTask> found = requeueService.load(taskId);
        if (found.isEmpty()) {
            return RetryScheduleResult.notRetried("任务不存在，不安排重试");
        }
        FetchTask task = found.get();

        // 闸 1：同一次失败已经安排过 → 幂等返回，不再排第二次退避
        if (pendingRetries.contains(taskId)) {
            int retryCount = retryCountOf(task);
            LOG.debug("该任务的重试已安排过（幂等返回）: taskId={} retry_count={}", taskId, retryCount);
            return RetryScheduleResult.alreadyScheduled(retryCount, 0L, failureCode,
                    "本次失败的重试已安排过，不再重复排退避");
        }

        // 闸 2：终态任务不回退
        if (task.getStatus() != null && task.getStatus().isTerminal()) {
            return RetryScheduleResult.notRetried(failureCode,
                    "任务已是终态 " + task.getStatus().toDbValue() + "，不回退重试");
        }

        // 闸 3：失败码可重试？（词表唯一出自 FailureCodeRegistry）
        FailureCodeRegistry code = FailureCodeRegistry.fromDbValue(failureCode);
        if (!code.retryable()) {
            return RetryScheduleResult.notRetried(code.code(),
                    "失败码 " + code.code() + " 不可重试：" + code.suggestedAction());
        }

        // 闸 4：重试预算
        RetryBudgetResolver.RetryBudget budget = budgetResolver.budgetFor(task.getPeerAet());
        int attempt = retryCountOf(task);
        RetryPolicy.RetryDecision decision =
                policy.decide(attempt, budget.maxRetries(), budget.baseBackoffMs(), code);
        if (!decision.shouldRetry()) {
            return RetryScheduleResult.notRetried(code.code(), decision.reason());
        }

        // 安排：任务退回 queued + retry_count+1 + 落审计
        FetchTask requeued = requeueService.requeue(
                taskId, code.code(), attempt + 1, TaskRequeueService.AUDIT_TASK_RETRY_SCHEDULED);
        if (requeued == null) {
            return RetryScheduleResult.notRetried(code.code(), "任务回退失败（任务不存在或已终结）");
        }

        long backoffMs = decision.backoffMs();
        pendingRetries.add(taskId);
        requeueService.resubmitAfter(taskId, backoffMs, backoffScheduler,
                () -> pendingRetries.remove(taskId));

        LOG.info("调阅失败已安排重试：taskId={} peer={} code={} 第 {} 次，退避 {} ms",
                taskId, task.getPeerAet(), code.code(), attempt + 1, backoffMs);
        return RetryScheduleResult.scheduled(attempt + 1, backoffMs, code.code(), decision.reason());
    }

    private static int retryCountOf(FetchTask task) {
        return task.getRetryCount() == null ? 0 : task.getRetryCount();
    }

    /** 当前有多少任务处于「已安排重试、尚未到点」状态（运维看板用）。 */
    public int pendingRetryCount() {
        return pendingRetries.size();
    }
}
