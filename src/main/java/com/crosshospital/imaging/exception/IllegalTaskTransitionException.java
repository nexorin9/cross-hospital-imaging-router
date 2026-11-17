package com.crosshospital.imaging.exception;

import com.crosshospital.imaging.domain.TaskStatus;

/**
 * 非法状态跃迁 —— 调阅任务状态机拒绝了一次推进。
 *
 * <p><b>为什么要单独一个异常类型</b>：信息科排查「任务卡住了」时，第一件事是分清
 * 「外院没回片」（协议失败）和「本服务内部把任务状态推乱了」（代码缺陷）。前者进死信、
 * 后者是 bug —— 两者的处置路径完全不同，混成一个 {@code RuntimeException}
 * 就只能靠翻日志猜。
 *
 * <p>典型触发场景：
 * <ul>
 *   <li>影像已落本院 PACS（{@code stored}）后，迟到的重试回调把它拉回 {@code querying}；</li>
 *   <li>任务已通知 HIS（{@code notified}）后，又来了一次失败回调想改成 {@code failed}；</li>
 *   <li>并发推进：两个执行线程同时把同一个任务从 {@code queued} 推走，后者拿到的是
 *       已经不匹配的状态。</li>
 * </ul>
 *
 * <p>拒绝而不是「纠正着推」是刻意的：一个已经 {@code stored} 的任务被改成
 * {@code querying}，等于对外宣称「影像还没进本院 PACS」—— 业务状态被倒退，
 * 比直接报一个错坏得多。
 */
public class IllegalTaskTransitionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** 跃迁前的状态；任务行刚建、尚未有状态时可能为 {@code null}。 */
    private final TaskStatus from;

    /** 请求推进到的状态。 */
    private final TaskStatus to;

    public IllegalTaskTransitionException(TaskStatus from, TaskStatus to) {
        super(buildMessage(from, to));
        this.from = from;
        this.to = to;
    }

    private static String buildMessage(TaskStatus from, TaskStatus to) {
        String fromText = from == null ? "(null)" : from.toDbValue();
        String toText = to == null ? "(null)" : to.toDbValue();
        String allowed = (from == null || from.allowedNextStates().isEmpty())
                ? "无"
                : String.join(", ", from.allowedNextStates().stream()
                .map(TaskStatus::toDbValue).sorted().toList());
        return "非法状态跃迁：" + fromText + " → " + toText
                + "（该状态合法的后继：" + allowed + "）";
    }

    /** 跃迁前的状态。 */
    public TaskStatus getFrom() {
        return from;
    }

    /** 请求推进到的状态。 */
    public TaskStatus getTo() {
        return to;
    }
}
