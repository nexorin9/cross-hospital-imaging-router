package com.crosshospital.imaging.service.wado;

import com.crosshospital.imaging.domain.AiCallAudit;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.exception.WadoRsHttpException;
import com.crosshospital.imaging.exception.WadoRsParseException;
import com.crosshospital.imaging.repository.AiCallAuditRepository;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.service.AuditChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * WADO-RS 失败审计落库（task 12）。
 *
 * <p>每次 WADO-RS 调用失败（HTTP 5xx / 4xx / 传输失败 / multipart 解析失败）都会落两行：
 * <ol>
 *   <li>{@code audit_imaging_fetch} 一行 —— 标注 {@code status_terminal=wado_fetch_failed}，
 *       {@code retry_count} 反映重试耗尽后的最终尝试次数。</li>
 *   <li>{@code ai_call_audit} 一行 —— 复用 task 26 设计的失败谱（{@code http_error} /
 *       {@code parse_error} / {@code unreachable} / {@code timeout}），方便 task 26
 *       ModelGateway 与 WADO-RS 共用一张失败谱做运营统计。</li>
 * </ol>
 *
 * <p><b>不写库的安全路径</b>：当调用方未提供 {@code taskId} 时只落审计行；
 * 当审计 Repository 不可达（如测试替身）时吞掉异常并 WARN —— 失败审计是辅助能力，
 * 不能让审计本身的故障让外发调用者再失败一次。
 *
 * <p><b>人工确认点</b>：{@code audit_imaging_fetch} 是 append-only，触发器拒任何
 * UPDATE / DELETE；{@code ai_call_audit} 允许运维按 created_at 滚动归档。
 *
 * <p>源主路径证据：dcm4che-net-audit 应用层审计模块（任务级对齐）；
 * task 24 起本类通过 {@link AuditChain#append} 写入审计链 —— 与握手/调阅审计
 * 共用同一把锁，既保证链不分叉，也保证 {@code row_checksum} / {@code prev_checksum}
 * 两列被盖上戳（库里两列都是 {@code NOT NULL}）。
 */
@Component
@Profile("!test")
public class WadoRsAuditWriter {

    private static final Logger LOG = LoggerFactory.getLogger(WadoRsAuditWriter.class);

    /** {@code audit_imaging_fetch.status_terminal} 的命名空间，避免与握手 / 路由主路径冲突。 */
    static final String STATUS_TERMINAL = "wado_fetch_failed";

    private final AuditRepository auditRepository;
    private final AiCallAuditRepository aiCallAuditRepository;

    public WadoRsAuditWriter(AuditRepository auditRepository,
                             AiCallAuditRepository aiCallAuditRepository) {
        this.auditRepository = auditRepository;
        this.aiCallAuditRepository = aiCallAuditRepository;
    }

    /**
     * 把一次 WADO-RS 失败写到两张审计表。
     *
     * @param peerAet      外院 AET（写入 audit_imaging_fetch.to_aet）
     * @param studyUid     StudyInstanceUID
     * @param failure      失败异常（WadoRsHttpException / WadoRsParseException / 其他）
     * @param retryCount   已尝试（含首次失败）
     * @param who          操作员工号（{@code system} 表示任务级自动重试）
     * @param taskId       关联的 FetchTask UUID（可为空，仅做串联字段）
     * @param elapsedMs    总耗时（毫秒；用于 ai_call_audit.latency_ms）
     */
    public void recordFailure(String peerAet, String studyUid, Throwable failure,
                              int retryCount, String who, UUID taskId, long elapsedMs) {
        String failureCode = classifyFailure(failure);
        int safeRetry = Math.max(0, retryCount);

        try {
            AuditRow row = new AuditRow()
                    .withWho(who == null || who.isBlank() ? "system" : who)
                    .withFromAet("local")
                    .withToAet(peerAet)
                    .withStudyUid(studyUid)
                    .withRetryCount(safeRetry)
                    .withStatusTerminal(STATUS_TERMINAL)
                    .withFailureCode(failureCode);
            // task 24：走审计链唯一写入入口 —— 盖链式校验和后再 INSERT
            // （两列在库里都是 NOT NULL，不盖戳会被数据库拒绝；且必须与其他写入路径
            //   共用同一把锁，否则并发写会把审计链分叉成「像被篡改」的断链误报）
            AuditChain.append(auditRepository, row);
        } catch (RuntimeException ex) {
            LOG.warn("audit_imaging_fetch 写入失败 peerAet={} studyUid={} code={} cause={}",
                    peerAet, studyUid, failureCode, ex.toString());
        }

        try {
            AiCallAudit ai = new AiCallAudit();
            ai.setTaskId(taskId);
            ai.setKind("wado_fetch");
            ai.setModelName("wado-rs");
            ai.setLatencyMs(Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(0, elapsedMs))));
            ai.setStatus(failureCode);
            ai.setErrorMessage(summarizeFailure(failure));
            aiCallAuditRepository.save(ai);
        } catch (RuntimeException ex) {
            LOG.warn("ai_call_audit 写入失败 peerAet={} studyUid={} cause={}",
                    peerAet, studyUid, ex.toString());
        }
    }

    /**
     * 把异常归类到 {@code ai_call_audit.status} 的五个枚举之一。
     */
    public String classifyFailure(Throwable failure) {
        if (failure instanceof WadoRsParseException parse) {
            return switch (parse.getParseError()) {
                case TRUNCATED_MULTIPART, MALFORMED_MULTIPART,
                        MISSING_BOUNDARY, INVALID_CONTENT_TYPE, EMPTY_MULTIPART,
                        INVALID_DICOM_PREAMBLE -> "parse_error";
                case MULTIPART_TOO_LARGE -> "parse_error";
            };
        }
        if (failure instanceof WadoRsHttpException http) {
            int sc = http.getStatusCode();
            if (sc == WadoRsHttpException.STATUS_TRANSPORT_FAILURE) {
                return "unreachable";
            }
            if (sc == 408 || sc == 504) {
                return "timeout";
            }
            if (sc >= 500) {
                return "http_error";
            }
            return "http_error";
        }
        return "unreachable";
    }

    /**
     * 把异常消息压缩到不超过 1000 字符（避免数据库 text 字段被撑爆）。
     */
    public String summarizeFailure(Throwable failure) {
        if (failure == null) return "";
        String msg = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        if (msg.length() > 1000) {
            msg = msg.substring(0, 997) + "...";
        }
        return msg;
    }
}