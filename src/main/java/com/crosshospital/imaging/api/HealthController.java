package com.crosshospital.imaging.api;

import com.crosshospital.imaging.config.AppInfoProperties;
import com.crosshospital.imaging.service.hl7.MllpTriggerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查 + build info 控制器。
 *
 * <p>暴露在 {@code /actuator/health}（走 spring-boot-starter-actuator 自定义 bean）
 * 与 {@code /actuator/info} 两个端点；返回 JSON 含组件级 UP/DOWN 状态与版本信息。
 *
 * <p>DB / DICOM / 模型 组件当前为占位结构（后续 task 注入 {@code HealthIndicator}），
 * 保证开箱即可返回 200；<b>HL7 组件是真实的</b>：task 17 起由 {@link MllpTriggerService}
 * 汇报触发守护的绑定端口、释放模式与累计帧数（见 {@link #hl7Status()}）。
 */
@RestController
@Tag(name = "health", description = "健康检查 + build info")
public class HealthController {

    private final AppInfoProperties appInfo;
    private final ObjectProvider<MllpTriggerService> mllpTriggerService;

    @Autowired
    public HealthController(AppInfoProperties appInfo, ObjectProvider<MllpTriggerService> mllpTriggerService) {
        this.appInfo = appInfo;
        this.mllpTriggerService = mllpTriggerService;
    }

    /**
     * 自定义聚合 health 端点。Spring Boot Actuator 已默认提供 {@code /actuator/health}，
     * 此处额外暴露 {@code /api/imaging/health} 便于 OpenAPI 文档化（swagger-ui 可直接看到）。
     */
    @GetMapping(value = "/api/imaging/health", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "聚合健康检查", description = "返回 db / dicom / hl7 / model 四组件 UP/DOWN 状态。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK，status 为聚合状态，components 为各组件明细")
    })
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("db", mapStatus("UP", null));
        components.put("dicom", mapStatus("UP", null));
        components.put("hl7", hl7Status());
        components.put("model", mapStatus("DOWN", "未配置 MODEL_BASE_URL（仅占位，task 26+ 实现）"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("components", components);
        return ResponseEntity.ok(body);
    }

    /**
     * HL7 触发通道的真实状态。
     *
     * <p>守护 bean 存在时按 {@code isRunning()} 报 UP/DOWN，并附一行运行摘要（端口 / 模式 / 累计帧数）；
     * bean 不存在（冒烟测试 profile 不加载 TCP 守护）时报 UP 并说明原因，不谎称「正在监听」。
     */
    private Map<String, Object> hl7Status() {
        MllpTriggerService svc = mllpTriggerService.getIfAvailable();
        if (svc == null) {
            return mapStatus("UP", "当前 profile 未装配 MLLP 触发守护（不监听端口）");
        }
        return mapStatus(svc.isRunning() ? "UP" : "DOWN", svc.describe());
    }

    /**
     * build info 端点。
     */
    @GetMapping(value = "/api/imaging/info", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "build info", description = "返回应用名 / 版本 / 构建时间 / git commit。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK，app 段为构建元信息")
    })
    public ResponseEntity<Map<String, Object>> info() {
        Map<String, Object> build = new LinkedHashMap<>();
        build.put("name", appInfo.getName());
        build.put("version", appInfo.getVersion());
        build.put("buildTime", appInfo.getBuildTime());
        build.put("gitCommit", appInfo.getGitCommit());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app", build);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> mapStatus(String status, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        if (detail != null) {
            m.put("detail", detail);
        }
        return m;
    }
}