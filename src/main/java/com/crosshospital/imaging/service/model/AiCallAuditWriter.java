package com.crosshospital.imaging.service.model;

import com.crosshospital.imaging.domain.AiCallAudit;
import com.crosshospital.imaging.exception.ModelResponseParseException;
import com.crosshospital.imaging.exception.ModelTimeoutException;
import com.crosshospital.imaging.exception.ModelUnreachableException;
import com.crosshospital.imaging.repository.AiCallAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 模型调用审计写入器（task 27）。
 *
 * <p><b>每一次模型调用都留一行</b> —— 成功的、失败的、走了规则兜底的。
 * 信息科排障时最常问的一句是「那条归一结果是模型写的还是规则凑的」，
 * 答案就在这张表里（配合 {@code ai_summaries.source} 看业务结果，
 * 配合本表看<b>为什么</b>变成了规则兜底）。
 *
 * <p><b>status 五态</b>（与 V6 迁移的 CHECK 约束一一对应，不多不少）：
 * <ul>
 *   <li>{@link #STATUS_SUCCESS} —— 调用成功</li>
 *   <li>{@link #STATUS_TIMEOUT} —— 超时（{@link ModelTimeoutException}）</li>
 *   <li>{@link #STATUS_PARSE_ERROR} —— 响应读不懂 / 结构不符 / <b>没有完整生成</b>
 *       （{@code finish_reason=length} 截断、{@code content_filter} 拦截都归这一桶）</li>
 *   <li>{@link #STATUS_HTTP_ERROR} —— 拿到了 HTTP 响应但状态码不可接受（4xx / 5xx）</li>
 *   <li>{@link #STATUS_UNREACHABLE} —— 根本没跟模型说上话（连不上 / 未配置端点 /
 *       入参不合法未发起调用）</li>
 * </ul>
 *
 * <p><b>本类永不抛异常</b>：审计写不进去不能让跨院调阅主路径跟着挂 ——
 * 那是「排障失败」把「业务失败」放大成了「服务不可用」。
 * 写失败只落日志。
 */
@Service
@Profile("!test")
public class AiCallAuditWriter {

    private static final Logger LOG = LoggerFactory.getLogger(AiCallAuditWriter.class);

    /** 调用成功。 */
    public static final String STATUS_SUCCESS = "success";
    /** 调用超时。 */
    public static final String STATUS_TIMEOUT = "timeout";
    /** 响应读不懂 / 结构不符 / 输出不完整。 */
    public static final String STATUS_PARSE_ERROR = "parse_error";
    /** HTTP 状态码不可接受。 */
    public static final String STATUS_HTTP_ERROR = "http_error";
    /** 端点不可达（连不上 / 未配置 / 入参不合法未发起调用）。 */
    public static final String STATUS_UNREACHABLE = "unreachable";

    /** 错误信息写入前的长度上限（列宽有限，也够排障看了；与 V6 列注释的「截断到 4KB」对齐）。 */
    private static final int MAX_ERROR_CHARS = 4096;

    private final AiCallAuditRepository repository;

    public AiCallAuditWriter(AiCallAuditRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("AiCallAuditRepository 不能为 null");
        }
        this.repository = repository;
    }

    /** 记一行成功。 */
    public void recordSuccess(UUID taskId, String kind, String modelName, long latencyMs) {
        write(taskId, kind, modelName, latencyMs, STATUS_SUCCESS, null);
    }

    /** 记一行失败（{@code status} 见本类五态常量）。 */
    public void recordFailure(UUID taskId, String kind, String modelName, long latencyMs,
                              String status, String errorMessage) {
        write(taskId, kind, modelName, latencyMs, status, errorMessage);
    }

    /**
     * 把异常映射成 {@code ai_call_audit.status} 五态之一。
     *
     * <p><b>为什么 {@code finish_reason=length} 归 parse_error</b>：它不是网络问题、
     * 不是鉴权问题，是「模型回话不完整」—— 与响应读不懂同属一类：
     * <b>拿到了东西，但东西不能用</b>。
     */
    public static String classify(Throwable ex) {
        if (ex instanceof ModelTimeoutException) {
            return STATUS_TIMEOUT;
        }
        if (ex instanceof ModelResponseParseException) {
            return STATUS_PARSE_ERROR;
        }
        if (ex instanceof ModelUnreachableException unreachable) {
            // 拿到 HTTP 响应才算 http_error；连响应都没有是 unreachable
            return unreachable.getHttpStatus() > 0 ? STATUS_HTTP_ERROR : STATUS_UNREACHABLE;
        }
        return STATUS_UNREACHABLE;
    }

    private void write(UUID taskId, String kind, String modelName, long latencyMs,
                       String status, String errorMessage) {
        try {
            AiCallAudit row = new AiCallAudit();
            row.setTaskId(taskId);
            row.setKind(kind);
            // V6 契约：model_name 记的是「调用过的模型」；unreachable = 压根没跟模型说上话
            // （连不上 / 未配置 / 入参不合法未发起调用）→ 必须是 NULL。
            // 写上配置里的模型名，只会让「模型没配」的行看起来像是调过模型。
            row.setModelName(STATUS_UNREACHABLE.equals(status) ? null : modelName);
            row.setLatencyMs(toIntMillis(latencyMs));
            row.setStatus(status);
            row.setErrorMessage(truncate(errorMessage));
            repository.save(row);
        } catch (RuntimeException ex) {
            LOG.error("AI 调用审计写入失败（已吞掉，不影响业务主路径）：kind={} status={} msg={}",
                    kind, status, ex.getMessage());
        }
    }

    private static Integer toIntMillis(long latencyMs) {
        if (latencyMs < 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, latencyMs);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR_CHARS
                ? message.substring(0, MAX_ERROR_CHARS) + "...[truncated]"
                : message;
    }
}
