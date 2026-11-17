package com.crosshospital.imaging.api;

import com.crosshospital.imaging.api.dto.DlqEntryResponse;
import com.crosshospital.imaging.api.dto.DlqResolveRequest;
import com.crosshospital.imaging.service.task.DlqService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调阅任务死信队列 REST API（信息科处置面）。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET  /api/imaging/dlq}                 —— 分页列出<b>未处置</b>死信（可按失败码过滤）</li>
 *   <li>{@code GET  /api/imaging/dlq/{id}}            —— 单条死信详情</li>
 *   <li>{@code POST /api/imaging/dlq/{id}/resolve}    —— 标记已处置（人工处置动作，落审计行）</li>
 * </ul>
 *
 * <p><b>为什么只有 API 没有页面</b>：本产品是常驻无界面服务（交付形态 = HTTP API），
 * 处置界面由院内已有的运维门户 / 监控台消费本 API 呈现；服务自身不引入前端栈，
 * 免得把「一个 DICOM 路由服务」做成一坨要单独部署的前端工程。
 *
 * <p><b>处置人怎么来</b>：{@code X-Employee-Id} 请求头 → {@code ?who=} 查询参数 → {@code system}，
 * 三级降级与 {@code PeersController} 保持一致；生产环境应换成 OAuth2 / JWT 注入。
 */
@RestController
@RequestMapping(value = "/api/imaging/dlq", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "dlq", description = "调阅任务死信队列：未处置列表与人工处置")
@Validated
@Profile("!test")
public class DlqController {

    private static final Logger log = LoggerFactory.getLogger(DlqController.class);

    private final DlqService dlqService;

    public DlqController(DlqService dlqService) {
        this.dlqService = dlqService;
    }

    // ─────── GET /api/imaging/dlq ───────

    @GetMapping
    @Operation(
            summary = "分页列出未处置死信",
            description = "按创建时间倒序返回尚未人工处置的死信；可按失败码过滤"
                    + "（如 connection_refused / tls_mismatch）。响应含任务上下文"
                    + "（外院 AET / 病人主索引 / StudyInstanceUID）与已重试次数。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK，Page<DlqEntryResponse>")
    })
    public ResponseEntity<Page<DlqEntryResponse>> list(
            @Parameter(description = "页码（0 起）", example = "0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "页大小（1..200）", example = "20")
            @RequestParam(name = "size", defaultValue = "20") @Min(1) int size,
            @Parameter(description = "失败码过滤；留空 = 不过滤", example = "connection_refused")
            @RequestParam(name = "failureCode", required = false) String failureCode) {
        return ResponseEntity.ok(dlqService.listUnresolved(page, size, failureCode));
    }

    // ─────── GET /api/imaging/dlq/{id} ───────

    @GetMapping("/{id}")
    @Operation(
            summary = "查看单条死信详情",
            description = "含失败码、失败消息、重试次数、任务上下文与处置状态。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "404", description = "死信 ID 不存在")
    })
    public ResponseEntity<DlqEntryResponse> get(
            @Parameter(description = "死信行 ID", example = "17")
            @PathVariable("id") @Min(1) long id) {
        return ResponseEntity.ok(dlqService.get(id));
    }

    // ─────── POST /api/imaging/dlq/{id}/resolve ───────

    @PostMapping(value = "/{id}/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "标记死信已处置",
            description = "信息科人工处置一条死信：写入处置时间、处置人与处置说明，"
                    + "并在审计表落一行 dlq_resolved。重复处置返回 409。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK，返回处置后的死信"),
            @ApiResponse(responseCode = "400", description = "处置说明超长"),
            @ApiResponse(responseCode = "404", description = "死信 ID 不存在"),
            @ApiResponse(responseCode = "409", description = "该死信已被处置过")
    })
    public ResponseEntity<DlqEntryResponse> resolve(
            @Parameter(description = "死信行 ID", example = "17")
            @PathVariable("id") @Min(1) long id,
            @RequestBody(required = false) @Valid DlqResolveRequest req,
            @RequestHeader(name = "X-Employee-Id", required = false) String employeeId,
            @RequestParam(name = "who", required = false) String whoParam) {
        String who = resolveWho(employeeId, whoParam);
        String note = req == null ? null : req.getNote();
        DlqEntryResponse resolved = dlqService.resolve(id, who, note);
        log.info("dlq resolved id={} by={}", id, who);
        return ResponseEntity.ok(resolved);
    }

    // ─────── helpers ───────

    /**
     * 解析操作者：优先 {@code X-Employee-Id} header，其次 {@code ?who=} query，
     * 最后回退 {@code "system"}。与 {@code PeersController} 同一口径。
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
