package com.crosshospital.imaging.api;

import com.crosshospital.imaging.api.dto.HandshakeResponse;
import com.crosshospital.imaging.exception.HandshakeException;
import com.crosshospital.imaging.exception.HandshakeException.FailureCode;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.dicom.CompatibleHandshakeService;
import com.crosshospital.imaging.service.dicom.HandshakeResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外院 DICOM 节点握手健康检查 RPC。
 *
 * <p>端点：{@code POST /api/imaging/peers/{aet}/health}
 *
 * <p><b>HTTP 语义</b>：握手失败与成功在 HTTP 层都是 200 OK —
 * "健康检查 RPC 被执行"是一致事实，{@code compatible} 字段才是结果。
 * 这样调用方不必解析非 2xx 响应即可拿到详细失败码（
 * {@code lastErrorCode} + {@code lastErrorMessage} + {@code retryable}）。
 *
 * <p><b>审计联动</b>：
 * <ul>
 *   <li>成功 / 协议层失败 / 黑名单命中 / TCP 失败 → {@code ImagingConnectionMonitor}
 *       已经过 audit_row 写 {@code status_terminal} ∈
 *       {@code handshake_established / handshake_rejected / handshake_blacklisted_rejected},
 *       本 controller 不再写审计以免双计。</li>
 *   <li>空 {@code peerAet} 与 {@code PEER_NOT_FOUND}（peer 入准表前不存在）
 *       ——{@code CompatibleHandshakeService.handshake()} 在调 monitor 之前抛
 *       {@link HandshakeException}，故本 controller 显式补一行 audit
 *       （{@code status_terminal=handshake_rejected}）。</li>
 * </ul>
 *
 * <p><b>调用方语义</b>：
 * <ul>
 *   <li>信息科首次接入新节点 → POST 跑握手；返回 JSON 即诊断结果</li>
 *   <li>影像科定期体检 → 每月跑一次所有启用节点；audit 表自动累计</li>
 *   <li>对接巡检脚本 → 重试由调用方决定（{@code retryable=true} 表示可重试）</li>
 * </ul>
 *
 * <p>源主路径：dcm4che {@code Connection.isCompatible()} 协议族 + TLS + cipher
 * 三层校验 + TCP 探活 → 改写为 REST 形态返回结构化结果。
 */
@RestController
@RequestMapping(value = "/api/imaging/peers", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "peers", description = "外院 DICOM 节点准入表与握手健康检查 RPC")
@Validated
@Profile("!test")
public class PeersHealthController {

    private static final Logger log = LoggerFactory.getLogger(PeersHealthController.class);

    private final CompatibleHandshakeService handshakeService;
    private final AuditService auditService;

    public PeersHealthController(CompatibleHandshakeService handshakeService,
                                 AuditService auditService) {
        this.handshakeService = handshakeService;
        this.auditService = auditService;
    }

    // ─────── POST /api/imaging/peers/{aet}/health ───────

    @PostMapping("/{aet}/health")
    @Operation(
            summary = "对外院节点做一次握手健康检查",
            description = "触发协议族 + TLS + cipher + 传输语法 + SOP Class 5 层预校验 +"
                    + " TCP 探活；返回结构化结果。HTTP 200 OK 即使握手失败，调用方读"
                    + " `compatible` 字段。失败码进 `lastErrorCode`。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "OK（无论握手成功或失败）— 响应体的 `compatible` 标志位")
    })
    public ResponseEntity<HandshakeResponse> handshake(
            @Parameter(description = "AE Title（1..16 字符）", example = "PEER_HZ_CITY_HOSP")
            @PathVariable("aet")
            @NotBlank
            @Size(min = 1, max = 16, message = "AET length must be 1..16")
            String aet,
            @RequestHeader(name = "X-Employee-Id", required = false) String employeeId,
            @RequestParam(name = "who", required = false) String whoParam) {

        String who = resolveWho(employeeId, whoParam);
        log.info("health RPC requested: aet={} by={}", aet, who);

        try {
            HandshakeResult result = handshakeService.handshake(aet);
            // 协议层 / 黑名单 / TCP 失败路径已由 ImagingConnectionMonitor 落审计
            // 这里把"结果视角"转换为 REST DTO 即可
            HandshakeResponse body = HandshakeResponse.fromResult(aet, who, result);
            log.info("health RPC completed: aet={} compatible={} lastErrorCode={} by={}",
                    aet, body.isCompatible(), body.getLastErrorCode(), who);
            return ResponseEntity.ok(body);
        } catch (HandshakeException ex) {
            // 异常路径：PEER_NOT_FOUND（未入准表）/ 空 AET → monitor 未触达 → 由 controller 补审计
            // BLACKLISTED / 其它 → monitor 已落 audit；为避免双计，这里只对
            // PEER_NOT_FOUND（含空 AET）写 audit；其余进入下一条逻辑直接返回响应。
            FailureCode code = ex.getFailureCode();
            boolean monitorDidNotFire = code == FailureCode.PEER_NOT_FOUND;
            if (monitorDidNotFire) {
                auditService.recordHandshake(aet, "handshake_rejected", who,
                        code == null ? null : code.name());
                log.warn("health RPC rejected (monitor not fired): aet={} code={} who={}",
                        aet, code, who);
            } else {
                log.warn("health RPC rejected (already audited by monitor): aet={} code={} who={}",
                        aet, code, who);
            }

            HandshakeResponse body = HandshakeResponse.fromException(
                    aet, who, code, ex.getMessage(), ex.isRetryable());
            return ResponseEntity.ok(body);
        }
    }

    // ─────── helpers ───────

    /**
     * 解析操作者：优先 {@code X-Employee-Id} header，其次 {@code ?who=} query，
     * 最后回退 {@code "system"}。生产环境应改为 OAuth2/JWT。
     */
    private static String resolveWho(String employeeId, String whoParam) {
        if (employeeId != null && !employeeId.isBlank()) {
            return employeeId.trim();
        }
        if (whoParam != null && !whoParam.isBlank()) {
            return whoParam.trim();
        }
        return "system";
    }
}
