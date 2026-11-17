package com.crosshospital.imaging.service.task;

import java.util.Set;

import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.exception.IllegalTaskTransitionException;

/**
 * 调阅任务状态机校验器 —— 独立于持久层与协议层的纯逻辑件。
 *
 * <p><b>为什么单独抽一个类</b>：跃迁规则是本产品最不能出错的一段。
 * 「影像已经写进本院 PACS」与「影像还没拉回来」在业务上完全相反，
 * 而这两件事在库里只差一个 {@code status} 字段。规则散落在各个 service 的
 * {@code if} 里，迟早会出现某条路径忘了校验 —— 表现就是「影像明明拉回来了，
 * 系统却说还在查询」，医生在工作站上查不到片子。
 *
 * <p>本类是<b>纯函数</b>：不碰数据库、不碰网络、可以在任何一层被调用。
 * 真正的跃迁合法性由 {@link TaskStatus#canTransitionTo(TaskStatus)} 持有
 * （与 V2 迁移的 CHECK 约束同源），本类提供的是「用它做决定」的一层：
 *
 * <pre>
 *   queued ──▶ querying ──▶ pulling ──▶ stored ──▶ notified
 *      │           │           │           │
 *      └───────────┴───────────┴───────────┴──▶ failed
 * </pre>
 *
 * <p><b>三条硬规则</b>：
 * <ol>
 *   <li>终态（{@code notified} / {@code failed}）不可再跃迁 —— 业务状态不可倒退；</li>
 *   <li>同态跃迁（{@code querying → querying}）不是「非法」，而是<b>幂等无操作</b> ——
 *       网络重投与重复回调是常态，重复推进必须安静通过而不是炸掉；</li>
 *   <li>非终态之间的任意跳跃（如 {@code queued → stored}）一律拒绝 —— 一次调阅
 *       必须留下「查过」「拉过」「写进本院 PACS」三段可核对的痕迹。</li>
 * </ol>
 *
 * <p>源主路径证据：{@code github_ref/dcm4che/dcm4che-net/.../ConnectionMonitor.java:49-58}
 * 的五态回调 —— 源产品用「状态 + 回调」把一次连接的完整生命周期分成可枚举的几段，
 * 本产品把同一套思路搬到业务对象（调阅任务）上。
 */
public final class TaskStateMachine {

    private TaskStateMachine() {
        // 工具类，不可实例化
    }

    /**
     * 某状态允许的后继集合（不可变）。
     *
     * @param from 起始状态；{@code null} 返回空集
     */
    public static Set<TaskStatus> allowedNext(TaskStatus from) {
        if (from == null) {
            return Set.of();
        }
        return from.allowedNextStates();
    }

    /**
     * 这次跃迁是否合法。
     *
     * <p>注意：<b>同态跃迁在这里算合法</b>（幂等语义由
     * {@link #isNoOp(TaskStatus, TaskStatus)} 单独判定）。
     */
    public static boolean isLegal(TaskStatus from, TaskStatus to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        return from.canTransitionTo(to);
    }

    /**
     * 同态跃迁 = 幂等无操作（重复推进）。
     *
     * <p>与「非法」严格区分：重复推进不改变任何状态，不落第二行审计，
     * 调用方拿到的是「已经在那个状态了」这一事实，而不是一个异常。
     */
    public static boolean isNoOp(TaskStatus from, TaskStatus to) {
        return from != null && from == to;
    }

    /**
     * 跃迁校验 —— 非法即抛。
     *
     * @throws IllegalTaskTransitionException 非法跃迁（含 {@code null} 目标状态）
     */
    public static void assertTransition(TaskStatus from, TaskStatus to) {
        if (!isLegal(from, to)) {
            throw new IllegalTaskTransitionException(from, to);
        }
    }

    /** 是否终态（{@code notified} / {@code failed}）。 */
    public static boolean isTerminal(TaskStatus status) {
        return status != null && status.isTerminal();
    }

    /** 终态集合。 */
    public static Set<TaskStatus> terminalStates() {
        return Set.of(TaskStatus.NOTIFIED, TaskStatus.FAILED);
    }

    /**
     * 是否处于幂等块（未终结但已入业务流）——
     * 数据库 UNIQUE INDEX {@code idx_fetch_tasks_idem} 在这些状态上生效。
     */
    public static boolean isIdempotentBlock(TaskStatus status) {
        return status != null && status.isIdempotentBlock();
    }

    /**
     * 跃迁表的可读文本（排障时直接贴进工单）。
     *
     * <p>一行一段，格式：{@code queued → querying / failed}。
     */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (TaskStatus from : TaskStatus.values()) {
            sb.append(from.toDbValue()).append(" → ");
            Set<TaskStatus> next = allowedNext(from);
            if (next.isEmpty()) {
                sb.append("(终态)");
            } else {
                sb.append(next.stream().map(TaskStatus::toDbValue).sorted().reduce((a, b) -> a + " / " + b).orElse(""));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}
