package com.crosshospital.imaging.service.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-compatible {@code POST /v1/chat/completions} 请求体。
 *
 * <p>报文形状：
 * <pre>
 * {
 *   "model": "...",
 *   "messages": [{"role":"system","content":"..."},{"role":"user","content":"..."}],
 *   "response_format": {"type":"json_schema","json_schema":{...}},
 *   "temperature": 0.1,
 *   "max_tokens": 1024
 * }
 * </pre>
 *
 * <p><b>温度默认 0.1</b>：AI 层是辅助（报告归一 / 互认初筛），不是让模型自由发挥 ——
 * 低温度让同一份外院原文的归一结果稳定可复现，便于医生复核与审计留痕。
 *
 * <p>用 {@link Builder} 构造：{@code ModelRequest.builder().model(...).message(...)}。
 * 请求体<b>不可变</b>：改响应格式用 {@link #withResponseFormat(ResponseFormat)} 造副本，
 * 避免同一个请求对象在多个调用点之间互相污染。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelRequest {

    @JsonProperty("model")
    private final String model;

    @JsonProperty("messages")
    private final List<ChatMessage> messages;

    @JsonProperty("response_format")
    private final ResponseFormat responseFormat;

    @JsonProperty("temperature")
    private final double temperature;

    @JsonProperty("max_tokens")
    private final Integer maxTokens;

    private ModelRequest(String model, List<ChatMessage> messages, ResponseFormat responseFormat,
                         double temperature, Integer maxTokens) {
        this.model = model;
        this.messages = List.copyOf(messages);
        this.responseFormat = responseFormat;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    private ModelRequest(Builder b) {
        this(b.model, b.messages, b.responseFormat, b.temperature, b.maxTokens);
    }

    /** 快捷构造（模型名 + 消息 + 响应格式）。 */
    public static ModelRequest of(String model, List<ChatMessage> messages, ResponseFormat responseFormat) {
        return builder().model(model).messages(messages).responseFormat(responseFormat).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 复制一份请求并替换响应格式。
     *
     * <p>网关在调用方未指定格式时按 {@code model.response-format} 补齐，用的就是这个方法。
     */
    public ModelRequest withResponseFormat(ResponseFormat newFormat) {
        return new ModelRequest(model, messages, newFormat, temperature, maxTokens);
    }

    public String getModel() { return model; }

    public List<ChatMessage> getMessages() { return messages; }

    public ResponseFormat getResponseFormat() { return responseFormat; }

    public double getTemperature() { return temperature; }

    public Integer getMaxTokens() { return maxTokens; }

    /** 请求体构造器。 */
    public static class Builder {
        private String model;
        private final List<ChatMessage> messages = new ArrayList<>();
        private ResponseFormat responseFormat;
        private double temperature = 0.1;
        private Integer maxTokens;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder messages(List<ChatMessage> messages) {
            if (messages != null) {
                this.messages.addAll(messages);
            }
            return this;
        }

        public Builder message(ChatMessage message) {
            if (message != null) {
                this.messages.add(message);
            }
            return this;
        }

        public Builder responseFormat(ResponseFormat responseFormat) {
            this.responseFormat = responseFormat;
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public ModelRequest build() {
            return new ModelRequest(this);
        }
    }
}
