package com.crosshospital.imaging.service.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code response_format} 段（OpenAI-compatible 报文）。
 *
 * <p>三种取值：
 * <ul>
 *   <li>{@code json_schema} —— 附带完整 schema，模型按 schema 输出；本项目结构化输出的首选</li>
 *   <li>{@code json_object} —— 只保证「是合法 JSON」，字段由提示词约束</li>
 *   <li>{@code text} —— 纯文本，不做 JSON 解析</li>
 * </ul>
 *
 * <p>报文形状：
 * <pre>
 * {"type":"json_schema","json_schema":{"name":"...","strict":true,"schema":{...}}}
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseFormat {

    public static final String TYPE_JSON_SCHEMA = "json_schema";
    public static final String TYPE_JSON_OBJECT = "json_object";
    public static final String TYPE_TEXT = "text";

    @JsonProperty("type")
    private final String type;

    @JsonProperty("json_schema")
    private final JsonSchema jsonSchema;

    public ResponseFormat(String type, JsonSchema jsonSchema) {
        this.type = type;
        this.jsonSchema = jsonSchema;
    }

    /** 需要模型按 schema 输出。 */
    public static ResponseFormat jsonSchema(JsonSchema schema) {
        return new ResponseFormat(TYPE_JSON_SCHEMA, schema);
    }

    /** 只要求合法 JSON。 */
    public static ResponseFormat jsonObject() {
        return new ResponseFormat(TYPE_JSON_OBJECT, null);
    }

    /** 纯文本。 */
    public static ResponseFormat text() {
        return new ResponseFormat(TYPE_TEXT, null);
    }

    public String getType() { return type; }

    @JsonProperty("json_schema")
    public JsonSchema getJsonSchema() { return jsonSchema; }

    /** 是否需要把模型回话当 JSON 解析。 */
    public boolean expectsJson() {
        return TYPE_JSON_SCHEMA.equals(type) || TYPE_JSON_OBJECT.equals(type);
    }
}
