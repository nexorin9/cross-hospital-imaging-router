package com.crosshospital.imaging.api;

import com.crosshospital.imaging.api.dto.FetchApiDto;
import com.crosshospital.imaging.api.dto.FetchRequest;
import com.crosshospital.imaging.api.dto.FetchResponse;
import com.crosshospital.imaging.api.dto.TaskStatusResponse;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.exception.NotFoundException;
import com.crosshospital.imaging.exception.ValidationException;
import com.crosshospital.imaging.service.routing.PeerRoutingResolver;
import com.crosshospital.imaging.service.task.FetchTaskEnqueueRequest;
import com.crosshospital.imaging.service.task.FetchTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
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

import java.net.URI;
import java.util.UUID;

/**
 * 跨院影像调阅入口（task 30）。
 *
 * <p><b>异步受理，不假装同步完成</b>：{@code POST /studies/fetch} 落一行
 * {@code status=queued} 的任务后立刻回 202 + Location；后续五态推进由队列消费，
 * 调用方按 {@code GET /tasks/{taskId}} 查终值。同步等待一次 C-MOVE 会把 HTTP 连接
 * 挂几分钟，HIS 侧早就超时重试了。
 *
 * <p><b>外院节点自动定位</b>：请求没给 {@code peerAet} 时，按患者主索引的命名空间
 * 查运行期路由配置（{@link PeerRoutingResolver}）；定位不到直接 400，
 * 不猜一个节点去连 —— 猜错节点会把别人的检查拉回本院 PACS。
 *
 * <p><b>幂等</b>：同一 {@code (patientMpi, studyUid)} 在未终结期内重复提交，
 * 由 {@code FetchTaskService} 返回既有 taskId，不会拉第二份影像。
 */
@RestController
@RequestMapping(value = "/api/imaging", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "fetch", description = "跨院影像调阅：异步受理 + 任务状态查询")
@Validated
@Profile("!test")
public class FetchController {

    private static final Logger LOG = LoggerFactory.getLogger(FetchController.class);

    private final FetchTaskService tasks;
    private final PeerRoutingResolver resolver;

    public FetchController(FetchTaskService tasks, PeerRoutingResolver resolver) {
        this.tasks = tasks;
        this.resolver = resolver;
    }

    @PostMapping(value = "/studies/fetch", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "一键发起跨院影像调阅",
            description = "按患者主索引定位外院节点，落一行 queued 任务后异步执行"
                    + " C-FIND / C-MOVE / WADO-RS / C-STORE 并回推 HIS。返回 202 + Location。")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "已受理，响应体含 taskId 与状态查询链接",
                    content = @Content(schema = @Schema(implementation = FetchResponse.class),
                            examples = @ExampleObject(value = FetchApiDto.EXAMPLE_ACCEPTED))),
            @ApiResponse(responseCode = "400", description = "缺 patientMpi / studyUid，或无法按主索引定位外院节点"),
            @ApiResponse(responseCode = "409", description = "同一检查已有未终结任务（由幂等键复用处理，正常不出现）")
    })
    public ResponseEntity<FetchResponse> fetch(
            @Valid @RequestBody FetchRequest req,
            @Parameter(description = "操作人工号（生产环境由 OAuth2 / JWT 注入）")
            @RequestHeader(name = "X-Employee-Id", required = false) String employeeId,
            @RequestParam(name = "who", required = false) String whoParam) {
        String who = resolveWho(employeeId, whoParam);
        String peerAet = resolvePeer(req);
        UUID taskId = tasks.enqueue(new FetchTaskEnqueueRequest(
                req.getPatientMpi(), req.getStudyUid(), peerAet, who,
                FetchTaskEnqueueRequest.SRC_API, req.getAccessionNumber(), req.getSopClassUid()));
        LOG.info("跨院调阅已受理：taskId={} studyUid={} peer={} who={}", taskId, req.getStudyUid(), peerAet, who);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/imaging/tasks/" + taskId))
                .body(FetchResponse.accepted(taskId));
    }

    @GetMapping("/tasks/{taskId}")
    @Operation(
            summary = "查询调阅任务状态",
            description = "返回五态机当前状态与失败码；任务不存在返回 404。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK，返回 TaskStatusResponse",
                    content = @Content(schema = @Schema(implementation = TaskStatusResponse.class),
                            examples = @ExampleObject(value = FetchApiDto.EXAMPLE_STATUS))),
            @ApiResponse(responseCode = "404", description = "task_id 不存在")
    })
    public ResponseEntity<TaskStatusResponse> getTask(
            @Parameter(description = "任务 UUID", example = "3f8a2f16-9a5e-4c11-9c53-2b9d1e0a7c44")
            @PathVariable UUID taskId) {
        FetchTask task = tasks.findByTaskId(taskId)
                .orElseThrow(() -> new NotFoundException("调阅任务不存在: " + taskId));
        return ResponseEntity.ok(TaskStatusResponse.from(task));
    }

    /** 显式指定优先；否则按主索引命名空间定位，且映射到的 AET 必须已在准入表登记。 */
    private String resolvePeer(FetchRequest req) {
        if (req.getPeerAet() != null && !req.getPeerAet().isBlank()) {
            return req.getPeerAet();
        }
        PeerRoutingResolver.PeerRoutingResult result = resolver.resolve(req.getPatientMpi());
        if (!result.resolved() || result.peerAetOptional().isEmpty()) {
            throw new ValidationException("无法按患者主索引定位外院节点：" + result.reason());
        }
        return result.peerAetOptional().get();
    }

    private static String resolveWho(String employeeId, String whoParam) {
        if (employeeId != null && !employeeId.isBlank()) {
            return employeeId;
        }
        if (whoParam != null && !whoParam.isBlank()) {
            return whoParam;
        }
        return "system";
    }
}
