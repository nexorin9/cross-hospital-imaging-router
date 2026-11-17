package com.crosshospital.imaging.service.model;

/**
 * 模型调用用量（OpenAI-compatible {@code usage} 段）。
 *
 * <p>落到 {@code ai_call_audit.latency_ms} 与运营看板，衡量 AI 增强层的成本面。
 *
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 * @param totalTokens      合计 token 数
 */
public record ModelUsage(int promptTokens, int completionTokens, int totalTokens) {

    /** 空用量（服务端未返回 usage 时）。 */
    public static ModelUsage empty() {
        return new ModelUsage(0, 0, 0);
    }
}
