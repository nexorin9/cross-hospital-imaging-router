package com.crosshospital.imaging.service.model;

/**
 * 模型调用的归一化响应（把 OpenAI-compatible 报文拆成业务层好用的几块）。
 *
 * <p>对应报文：
 * <pre>
 * {
 *   "id": "chatcmpl-...",
 *   "model": "...",
 *   "choices": [{"index":0,"message":{"role":"assistant","content":"..."},"finish_reason":"stop"}],
 *   "usage": {"prompt_tokens":123,"completion_tokens":45,"total_tokens":168}
 * }
 * </pre>
 *
 * <p>{@link #latencyMs} 与 {@link #attemptCount} 不来自报文，而是网关自己记的**可审计调用元数据**：
 * task 26 要求「模型调用元数据可审计」，这两项是落 {@code ai_call_audit} 表的字段来源。
 *
 * @param id           响应 ID
 * @param model        实际服务模型名
 * @param content      {@code choices[0].message.content}
 * @param finishReason {@code choices[0].finish_reason}
 * @param usage        token 用量
 * @param latencyMs    本次调用耗时（毫秒）
 * @param attemptCount 本次调用实际发出的 HTTP 请求次数（含重试）
 */
public record ModelResponse(String id,
                            String model,
                            String content,
                            String finishReason,
                            ModelUsage usage,
                            long latencyMs,
                            int attemptCount) {

    /** 回话内容是否为空（空回话不应被当作「模型没意见」，而应视为解析失败）。 */
    public boolean isEmptyContent() {
        return content == null || content.isBlank();
    }
}
