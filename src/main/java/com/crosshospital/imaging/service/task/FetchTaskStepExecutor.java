package com.crosshospital.imaging.service.task;

import java.util.UUID;

import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;

/**
 * 「状态 → 协议子步」执行端口 —— 五态机推进到某个状态时，由谁把这一步真正跑出来。
 *
 * <p><b>为什么要抽成端口</b>：状态机本身是纯逻辑，不该认识 DICOM。
 * 但「推进到 querying 就该去 C-FIND，推进到 pulling 就该去 C-MOVE」这条规则
 * 又是产品主路径的一部分，必须与状态推进<b>同一个事务边界</b>里成立：
 * 子步失败 → 任务转 failed，不能出现「状态停在 pulling、但其实没拉到东西」的中间态。
 *
 * <p>实现方（{@code RoutingStepExecutor}）依赖 DICOM 协议栈；状态机只依赖本接口。
 * 这样测试里可以换成一个记录型替身，把「状态推进」与「协议调用」分别验收。
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li>{@link #execute} 抛异常 = 这一步失败，由调用方把任务推向 failed；</li>
 *   <li>实现方<b>不</b>自行改任务状态 —— 状态只能由 {@code FetchTaskService} 推进；</li>
 *   <li>实现方<b>不</b>吞异常 —— 「拉回 0 个实例」与「拉回失败」是两回事，
 *       吞掉异常会让失败看起来像成功。</li>
 * </ul>
 */
public interface FetchTaskStepExecutor {

    /**
     * 本执行器是否负责这个状态的子步。
     *
     * @param status 目标状态
     */
    boolean supports(TaskStatus status);

    /**
     * 执行目标状态对应的协议子步。
     *
     * @param taskId       调阅任务 UUID
     * @param task         任务行快照（调用方已持久化的状态）
     * @param targetStatus 目标状态（{@code querying} / {@code pulling} / {@code stored}）
     * @return 子步产出（可回写的补充字段）；无产出时返回 {@link FetchTaskStepOutcome#none()}
     * @throws RuntimeException 子步失败（连接失败 / 协议不兼容 / 对端拒接 / 无匹配影像）
     */
    FetchTaskStepOutcome execute(UUID taskId, FetchTask task, TaskStatus targetStatus);
}
