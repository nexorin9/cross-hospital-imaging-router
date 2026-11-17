package com.crosshospital.imaging.api;

import com.crosshospital.imaging.api.dto.PeerCreateRequest;
import com.crosshospital.imaging.api.dto.PeerResponse;
import com.crosshospital.imaging.api.dto.PeerUpdateRequest;
import com.crosshospital.imaging.service.PeerRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 外院 DICOM 节点准入表 REST API。
 *
 * <p>端点（全部走 {@code /api/imaging/peers} 前缀，由 springdoc-openapi 自动文档化）：
 * <ul>
 *   <li>{@code POST   /api/imaging/peers}              —— 录入新节点（AET 唯一）</li>
 *   <li>{@code GET    /api/imaging/peers?page=&size=} —— 分页列出启用节点</li>
 *   <li>{@code GET    /api/imaging/peers/{aet}}       —— 按 AE Title 取单条</li>
 *   <li>{@code PUT    /api/imaging/peers/{aet}}       —— 部分字段更新</li>
 *   <li>{@code DELETE /api/imaging/peers/{aet}}       —— 删除节点</li>
 * </ul>
 *
 * <p>所有写操作触发 {@code PeerRegistry} 业务层，并在事务内落审计行
 * （{@code status_terminal} ∈ {create_peer / update_peer / delete_peer}）。
 *
 * <p>{@code who} 字段：本轮 stub 由 {@code X-Employee-Id} header 或
 * {@code ?who=} query 参数透传；生产环境应改为 OAuth2 / JWT 注入。
 *
 * <p>源主路径：HTTP API + springdoc-openapi 自动文档（{@code @Operation} +
 * {@code @Tag} + {@code @ApiResponses}）。
 */
@RestController
@RequestMapping(value = "/api/imaging/peers", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "peers", description = "外院 DICOM 节点准入表 CRUD")
@Validated
@Profile("!test")
public class PeersController {

    private static final Logger log = LoggerFactory.getLogger(PeersController.class);

    private final PeerRegistry peerRegistry;

    public PeersController(PeerRegistry peerRegistry) {
        this.peerRegistry = peerRegistry;
    }

    // ─────── POST /api/imaging/peers ───────

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "录入外院 DICOM 节点",
            description = "在准入表中新增一条节点；peer_aet 全局唯一，1..16 字符 [A-Z0-9_-]。"
                    + "每次写入在事务内落审计行。")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created，响应体含 PeerResponse"),
            @ApiResponse(responseCode = "400", description = "字段格式非法 / SOP Class UID 不合规"),
            @ApiResponse(responseCode = "409", description = "peer_aet 已存在")
    })
    public ResponseEntity<PeerResponse> create(
            @Valid @RequestBody PeerCreateRequest req,
            @RequestHeader(name = "X-Employee-Id", required = false) String employeeId,
            @RequestParam(name = "who", required = false) String whoParam) {
        String who = resolveWho(employeeId, whoParam);
        PeerResponse created2 = peerRegistry.createPeer(req, who);
        URI location = URI.create("/api/imaging/peers/" + created2.getPeerAet());
        return ResponseEntity.created(location).body(created2);
    }

    // ─────── GET /api/imaging/peers ───────

    @GetMapping
    @Operation(
            summary = "分页列出启用节点",
            description = "仅返回 enabled=true 的节点；size 上限 200，page 从 0 起。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK，Page<PeerResponse>")
    })
    public ResponseEntity<Page<PeerResponse>> list(
            @Parameter(description = "页码（0 起）", example = "0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "页大小（1..200）", example = "20")
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return ResponseEntity.ok(peerRegistry.listPeers(page, size));
    }

    // ─────── GET /api/imaging/peers/{aet} ───────

    @GetMapping("/{aet}")
    @Operation(
            summary = "按 AE Title 查询节点",
            description = "唯一键查询；AET 不存在时返回 404。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "404", description = "AET 不存在")
    })
    public ResponseEntity<PeerResponse> get(
            @PathVariable("aet")
            @NotBlank
            @Size(min = 1, max = 16,
                    message = "AET length must be 1..16")
            String aet) {
        return ResponseEntity.ok(peerRegistry.getPeer(aet));
    }

    // ─────── PUT /api/imaging/peers/{aet} ───────

    @PutMapping(value = "/{aet}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "部分字段更新节点",
            description = "字段均可选；null 字段不替换。写入触发 update_peer 审计行。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "400", description = "字段格式非法"),
            @ApiResponse(responseCode = "404", description = "AET 不存在")
    })
    public ResponseEntity<PeerResponse> update(
            @PathVariable("aet")
            @NotBlank
            @Size(min = 1, max = 16,
                    message = "AET length must be 1..16")
            String aet,
            @Valid @RequestBody PeerUpdateRequest req,
            @RequestHeader(name = "X-Employee-Id", required = false) String employeeId,
            @RequestParam(name = "who", required = false) String whoParam) {
        String who = resolveWho(employeeId, whoParam);
        return ResponseEntity.ok(peerRegistry.updatePeer(aet, req, who));
    }

    // ─────── DELETE /api/imaging/peers/{aet} ───────

    @DeleteMapping("/{aet}")
    @Operation(
            summary = "删除节点",
            description = "删除前先落 delete_peer 审计行（append-only），然后实体删除。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "404", description = "AET 不存在")
    })
    public ResponseEntity<Void> delete(
            @PathVariable("aet")
            @NotBlank
            @Size(min = 1, max = 16,
                    message = "AET length must be 1..16")
            String aet,
            @RequestHeader(name = "X-Employee-Id", required = false) String employeeId,
            @RequestParam(name = "who", required = false) String whoParam) {
        String who = resolveWho(employeeId, whoParam);
        peerRegistry.deletePeer(aet, who);
        log.info("peer deleted via REST aet={} by={}", aet, who);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
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