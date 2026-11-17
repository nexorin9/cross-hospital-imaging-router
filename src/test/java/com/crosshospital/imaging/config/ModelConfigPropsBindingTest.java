package com.crosshospital.imaging.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 26 验收（配置面）：{@code model.*} 是否真的能被绑定。
 *
 * <p>「配置化」这四个字最容易翻车的地方不是代码写没写，而是<b>键名对不上</b> ——
 * 类里叫 {@code timeoutMs}、yml 里写 {@code timeout-ms}、环境变量又是 {@code MODEL_TIMEOUT_MS}，
 * 三处名不一致时应用照样启动，只是那个配置<b>永远没生效</b>，而且不报任何错。
 * 所以这里直接用 Spring 的 Binder 逐键核对。
 */
class ModelConfigPropsBindingTest {

    @Test
    @DisplayName("model.* 全部键位能绑定到配置类（连字符 → camelCase）")
    void allKeysBind() {
        Map<String, Object> source = new HashMap<>();
        source.put("model.base-url", "http://127.0.0.1:8088/v1");
        source.put("model.api-key", "sk-local-test");
        source.put("model.model", "qwen2.5-instruct");
        source.put("model.timeout-ms", "8000");
        source.put("model.max-retries", "2");
        source.put("model.response-format", "json_object");
        source.put("model.fallback.enabled", "false");
        source.put("model.fallback.templates-path", "classpath:model/rule-templates.json");

        ModelConfigProps bound = bind(source);

        assertEquals("http://127.0.0.1:8088/v1", bound.getBaseUrl());
        assertEquals("sk-local-test", bound.getApiKey());
        assertEquals("qwen2.5-instruct", bound.getModel());
        assertEquals(8000, bound.getTimeoutMs());
        assertEquals(2, bound.getMaxRetries());
        assertEquals("json_object", bound.getResponseFormat());
        assertFalse(bound.getFallback().isEnabled());
        assertEquals("classpath:model/rule-templates.json", bound.getFallback().getTemplatesPath());
        assertTrue(bound.isConfigured());
        assertTrue(bound.hasApiKey());
    }

    @Test
    @DisplayName("未配置时用默认值：超时 5s / 重试 1 次 / json_schema / 兜底开启")
    void defaultsWhenNothingConfigured() {
        ModelConfigProps bound = bind(Map.of());

        assertEquals("", bound.getBaseUrl());
        assertEquals("gpt-4o-mini", bound.getModel());
        assertEquals(5000, bound.getTimeoutMs());
        assertEquals(1, bound.getMaxRetries());
        assertEquals("json_schema", bound.getResponseFormat());
        assertTrue(bound.getFallback().isEnabled());
        assertFalse(bound.isConfigured(), "没配 base-url = 未配置，调用直接失败并交规则模板兜底");
        assertFalse(bound.hasApiKey());
    }

    @Test
    @DisplayName("只配 base-url 也算已配置（本地模型端点不需要 api key）")
    void baseUrlAloneIsConfigured() {
        ModelConfigProps bound = bind(Map.of("model.base-url", "http://127.0.0.1:11434/v1"));

        assertTrue(bound.isConfigured());
        assertFalse(bound.hasApiKey());
    }

    private static ModelConfigProps bind(Map<String, Object> source) {
        Binder binder = new Binder(new MapConfigurationPropertySource(source));
        return binder.bind("model", ModelConfigProps.class).orElseGet(ModelConfigProps::new);
    }
}
