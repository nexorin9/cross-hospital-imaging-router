package com.crosshospital.imaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.context.annotation.Profile;

/**
 * 模型网关配置（{@code model.*}）。
 *
 * <p>本项目只对接 **OpenAI-compatible** 一种协议（自建端点 / 本地模型 / 云服务都走同一套
 * {@code POST {base-url}/v1/chat/completions} 报文），因此业务层不直接耦合任何厂商 SDK，
 * 换模型服务只需改这一份配置。
 *
 * <p><b>凭据位</b>：{@link #apiKey} 由环境变量 {@code MODEL_API_KEY} 注入，
 * {@link #baseUrl} 由 {@code MODEL_BASE_URL} 注入 —— <b>不硬编码、不落库、不进日志</b>。
 *
 * <p><b>留空即「未配置」</b>：{@link #baseUrl} 为空时 {@link #isConfigured()} 返回 {@code false}，
 * 网关直接抛 {@code ModelUnreachableException(NOT_CONFIGURED)}，由 task 27 的规则模板降级路径接管。
 * 这条链路的意义是：<b>没配模型服务时，整套 AI 增强层照样能跑</b>（只是走规则兜底），
 * 而不是让整个服务起不来。
 *
 * <p><b>超时语义</b>：{@link #timeoutMs} 是**单次 HTTP 调用**的读超时（同时也是连接超时与
 * 整次调用超时）。AI 只是增强层，不能让模型服务卡住把跨院调阅主路径一起拖死 —— 默认 5s。
 */
@ConfigurationProperties(prefix = "model")
@Profile("!test")
public class ModelConfigProps {

    /** OpenAI-compatible 服务根地址。留空 = 未配置（走规则模板兜底）。 */
    private String baseUrl = "";

    /** API Key（环境变量 {@code MODEL_API_KEY}）；留空 = 不带 Authorization 头（本地模型常见）。 */
    private String apiKey = "";

    /** 模型名（环境变量 {@code MODEL_NAME}）。 */
    private String model = "gpt-4o-mini";

    /** 单次模型调用超时（毫秒）。 */
    private int timeoutMs = 5000;

    /** 传输层失败（连接拒绝 / 超时 / 5xx / 429）的重试次数；解析失败与 4xx 不重试。 */
    private int maxRetries = 1;

    /**
     * 默认响应格式：{@code json_schema} / {@code json_object} / {@code text}。
     * 调用方未显式指定时按本值组装 {@code response_format}。
     */
    private String responseFormat = "json_schema";

    /** 规则模板兜底配置（task 27 消费）。 */
    @NestedConfigurationProperty
    private Fallback fallback = new Fallback();

    /** 规则模板兜底开关与模板位置。 */
    public static class Fallback {
        /** 是否允许在模型不可达时回退规则模板。 */
        private boolean enabled = true;

        /** 规则词典文件位置（classpath 或磁盘路径）—— 模态映射、兜底文案、互认判据。 */
        private String templatesPath = "classpath:hl7/segment-mappings.yml";

        /** 规则模板骨架文件位置（classpath 或磁盘路径）—— 输出字段与渲染指令。 */
        private String ruleTemplatesPath = "classpath:hl7/rule-templates.json";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getTemplatesPath() { return templatesPath; }
        public void setTemplatesPath(String templatesPath) { this.templatesPath = templatesPath; }

        public String getRuleTemplatesPath() { return ruleTemplatesPath; }
        public void setRuleTemplatesPath(String ruleTemplatesPath) { this.ruleTemplatesPath = ruleTemplatesPath; }
    }

    /** 是否配置了可用的模型服务端点。 */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /** 凭据位是否已填（用于自检与 /actuator/info 展示，不回显密钥本身）。 */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public String getResponseFormat() { return responseFormat; }
    public void setResponseFormat(String responseFormat) { this.responseFormat = responseFormat; }

    public Fallback getFallback() { return fallback; }

    public void setFallback(Fallback fallback) { this.fallback = fallback; }
}
