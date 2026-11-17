package com.crosshospital.imaging.config;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一次调阅任务推进的日志上下文（task 37）。
 *
 * <p>把 {@code traceId / spanId / who / taskId} 放进 MDC，logback 的 JSON encoder
 * 会把它们作为独立字段输出，运维按 taskId 就能把「HL7 触发 → C-FIND → C-MOVE →
 * C-STORE → AI 草稿 → HIS 回调」这一整条链捞出来，不用靠时间戳猜。
 *
 * <p>实现要点：
 * <ul>
 *   <li><b>可嵌套</b>：close() 恢复进上下文之前的旧值，而不是简单 remove —— 外层
 *       已有 traceId 时内层继续复用同一个 traceId；</li>
 *   <li><b>不覆盖 traceId</b>：如果上游过滤器已经给了链路 ID，这里不另造；
 *       只在缺失时生成，避免一次请求出现两个 trace；</li>
 *   <li>用 try-with-resources 包住业务调用，异常路径也不会把 MDC 泄漏到线程池下一个任务。</li>
 * </ul>
 */
public final class TaskContext implements AutoCloseable {

    private final Map<String, String> previous = new HashMap<>();

    private TaskContext() {
    }

    /**
     * 打开一个任务上下文。
     *
     * @param taskId 任务 UUID（可空，日志里就不写 taskId）
     * @param who    操作人工号 / 系统标识
     */
    public static TaskContext open(UUID taskId, String who) {
        TaskContext ctx = new TaskContext();
        ctx.put("taskId", taskId == null ? null : taskId.toString());
        ctx.put("who", who == null || who.isBlank() ? "system" : who);
        if (MDC.get("traceId") == null) {
            ctx.put("traceId", UUID.randomUUID().toString());
        }
        if (MDC.get("spanId") == null) {
            ctx.put("spanId", UUID.randomUUID().toString().substring(0, 8));
        }
        return ctx;
    }

    private void put(String key, String value) {
        previous.put(key, MDC.get(key));
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    @Override
    public void close() {
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            if (entry.getValue() == null) {
                MDC.remove(entry.getKey());
            } else {
                MDC.put(entry.getKey(), entry.getValue());
            }
        }
        previous.clear();
    }
}
