package com.crosshospital.imaging.service.model;

/**
 * 一条对话消息（OpenAI-compatible {@code messages[]} 数组的元素）。
 *
 * <p>角色取值：{@code system}（角色设定）/ {@code user}（用户输入）/ {@code assistant}（模型回话）。
 * 本项目只用前两种 —— 报告归一与互认初筛都是「系统提示 + 一段外院原文」的单轮问答。
 *
 * @param role    角色标识
 * @param content 消息正文
 */
public record ChatMessage(String role, String content) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public ChatMessage {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("消息角色不得为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("消息正文不得为 null：" + role);
        }
    }

    /** 系统提示（角色设定）。 */
    public static ChatMessage system(String content) {
        return new ChatMessage(ROLE_SYSTEM, content);
    }

    /** 用户输入（外院报告原文 / study 元数据）。 */
    public static ChatMessage user(String content) {
        return new ChatMessage(ROLE_USER, content);
    }

    /** 模型回话（多轮场景保留用）。 */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(ROLE_ASSISTANT, content);
    }
}
