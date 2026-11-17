package com.crosshospital.imaging.api;

import com.crosshospital.imaging.api.dto.AuditQueryRequest;
import com.crosshospital.imaging.api.dto.AuditRowResponse;
import com.crosshospital.imaging.service.AuditQueryCriteria;
import com.crosshospital.imaging.service.AuditQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调阅审计只读查询（task 31）。
 *
 * <p><b>只读是硬约束</b>：本 controller 只暴露 GET，写入路径只有
 * {@code AuditService.record} 一条（append-only + 链式 checksum）。
 * 时间窗左闭右开，条件为空即不筛选；分页默认 20 行、上限 200。
 *
 * <p><b>不暴露校验和字段</b>：响应 DTO 刻意不含 {@code rowChecksum / prevChecksum}，
 * 完整性验真走独立的 {@code AuditChecksumVerifier}。
 */
@RestController
@RequestMapping(value = "/api/imaging/audit", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "audit", description = "调阅审计只读查询（时间窗 / AET / 终态 / 操作人多条件）")
@Validated
@Profile("!test")
public class AuditController {

    private final AuditQueryService queryService;

    public AuditController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @Operation(
            summary = "按时间窗与多条件查询调阅审计",
            description = "left-closed right-open 时间窗；fromAet / toAet / statusTerminal / who 为空即不筛选。"
                    + "按事件时间倒序分页，size 默认 20、上限 200。仅支持 GET。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK，分页返回审计行"),
            @ApiResponse(responseCode = "400", description = "时间格式非法或分页参数越界"),
            @ApiResponse(responseCode = "405", description = "非 GET 方法被拒绝（审计只读）")
    })
    public ResponseEntity<Page<AuditRowResponse>> query(
            // springdoc 默认把 @ModelAttribute 复杂对象压成单个 "request" 参数；
            // 加 @ParameterObject 才会在 /v3/api-docs 展开成 8 个真正的 query parameters。
            @ParameterObject @Valid @ModelAttribute AuditQueryRequest request) {
        AuditQueryCriteria criteria = new AuditQueryCriteria(
                request.fromInstant(), request.toInstant(),
                blankToNull(request.getFromAet()), blankToNull(request.getToAet()),
                blankToNull(request.getStatusTerminal()), blankToNull(request.getWho()));
        Page<AuditRowResponse> page = queryService
                .query(criteria, request.getPage(), request.getSize())
                .map(AuditRowResponse::from);
        return ResponseEntity.ok(page);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
