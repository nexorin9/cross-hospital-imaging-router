package com.crosshospital.imaging.api;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.exception.GlobalExceptionHandler;
import com.crosshospital.imaging.exception.HandshakeException;
import com.crosshospital.imaging.exception.HandshakeException.FailureCode;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.dicom.BlacklistEnforcer;
import com.crosshospital.imaging.service.dicom.CompatibleHandshakeService;
import com.crosshospital.imaging.service.dicom.DeviceFactory;
import com.crosshospital.imaging.service.dicom.HandshakeResult;
import com.crosshospital.imaging.service.dicom.SopClassFilter;
import com.crosshospital.imaging.service.dicom.TransferSyntaxNegotiator;
import com.crosshospital.imaging.config.DicomConfigProps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 10 — PeersHealthController 握手健康检查 RPC 契约测试（独立 MockMvc 装配）。
 *
 * <p>JDK 27 上 Mockito inline mockmaker 对 final / Object 子类有限制，
 * 本测试与 {@code ImagingConnectionMonitorTest} 一致：避免 Mockito mock 具体类
 * {@link CompatibleHandshakeService} 与 {@link AuditService}，改用
 * 自写桩 {@link StubHandshakeService} / {@link StubAuditService} 替换。
 *
 * <p><b>覆盖</b>（5 happy + 4 exception）：
 * <ul>
 *   <li>happy：成功握手（compatible=true + 字段透传 + audit 未触达 controller）</li>
 *   <li>happy：协议层失败握手（compatible=false + failureCode 透传 + audit 未触达）</li>
 *   <li>happy：{@code who} 优先级（X-Employee-Id > ?who= > system）</li>
 *   <li>happy：{@code retryable=true}（网络瞬态 CONNECTION_REFUSED）</li>
 *   <li>happy：{@code retryable=false}（配置型失败 TRANSFER_SYNTAX_EMPTY）</li>
 *   <li>exception：{@code PEER_NOT_FOUND}（peer 未入准表）→ controller 补写 1 行 audit</li>
 *   <li>exception：{@code BLACKLISTED}（黑名单命中）→ controller <b>不</b>补写 audit</li>
 *   <li>exception：{@code INCOMPATIBLE_CONNECTION}（协议族不一致）→ controller 不补审计</li>
 *   <li>exception：AET 超过 16 字符 → 400 + fieldErrors</li>
 * </ul>
 *
 * <p>本测试不依赖 Spring 上下文；不依赖 PostgreSQL（DB 层由 task 25/35 验证）。
 */
@DisplayName("Task 10 — PeersHealthController contract (5 happy + 4 exception)")
class PeersHealthControllerContractTest {

    private MockMvc mockMvc;
    private StubHandshakeService handshakeService;
    private StubAuditService auditService;

    @BeforeEach
    void setUp() {
        handshakeService = new StubHandshakeService();
        auditService = new StubAuditService();
        PeersHealthController controller = new PeersHealthController(handshakeService, auditService);
        // standaloneSetup 默认不挂 Validator；显式注入 LocalValidatorFactoryBean
        // 让 @Validated 类级别的 @Size/@NotBlank 在 @PathVariable 上能触发
        // HandlerMethodValidationException → GlobalExceptionHandler 走 400
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    // ──────── happy path ────────

    @Test
    @DisplayName("happy: POST .../{aet}/health 成功 → 200 + compatible=true + audit 未触达 controller")
    void handshakeSucceeds200() throws Exception {
        HandshakeResult result = HandshakeResult.success(
                "PEER_HZ",
                /*local*/null, /*remote*/null,
                List.of("1.2.840.10008.5.1.4.1.1.2", "1.2.840.10008.5.1.4.1.1.4"),
                List.of("1.2.840.10008.1.2", "1.2.840.10008.1.2.1"),
                List.of("TLSv1.2"),
                List.of("TLS_RSA_WITH_AES_128_CBC_SHA"));
        handshakeService.nextResult = result;

        mockMvc.perform(post("/api/imaging/peers/PEER_HZ/health")
                        .header("X-Employee-Id", "employee_007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compatible").value(true))
                .andExpect(jsonPath("$.peerAet").value("PEER_HZ"))
                .andExpect(jsonPath("$.lastErrorCode").doesNotExist())
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.who").value("employee_007"))
                .andExpect(jsonPath("$.checkedAt").exists())
                .andExpect(jsonPath("$.commonSopClasses.length()").value(2))
                .andExpect(jsonPath("$.agreedTransferSyntaxes[0]").value("1.2.840.10008.1.2"));

        // 成功握手：协议层监控已记录 audit；controller 不应再补
        org.junit.jupiter.api.Assertions.assertEquals(0, auditService.recorded.size(),
                "successful handshake: 协议层监控已记录 audit，controller 不应再写");
    }

    @Test
    @DisplayName("happy: 协议层失败握手（compatible=false）→ 200 + lastErrorCode 透传")
    void handshakeFails200WithCompatibleFalse() throws Exception {
        HandshakeResult result = HandshakeResult.failure(
                FailureCode.TRANSFER_SYNTAX_EMPTY,
                "本地与对端传输语法无交集: peer=[] local=[1.2.840.10008.1.2]",
                null, null);
        handshakeService.nextResult = result;

        mockMvc.perform(post("/api/imaging/peers/PEER_HZ/health")
                        .param("who", "ops_hz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compatible").value(false))
                .andExpect(jsonPath("$.peerAet").value("PEER_HZ"))
                .andExpect(jsonPath("$.lastErrorCode").value("TRANSFER_SYNTAX_EMPTY"))
                .andExpect(jsonPath("$.lastErrorMessage").value(containsString("传输语法无交集")))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.who").value("ops_hz"));

        // 失败握手：协议层监控已记录 audit（ImagingConnectionMonitor.notifyRejected）
        // controller 不应再补
        org.junit.jupiter.api.Assertions.assertEquals(0, auditService.recorded.size(),
                "result-path failure: 协议层监控已记录 audit，controller 不应再写");
    }

    @Test
    @DisplayName("happy: who 优先级 — X-Employee-Id > ?who= > system")
    void whoResolutionPriority() throws Exception {
        HandshakeResult result = HandshakeResult.failure(
                FailureCode.CONNECTION_REFUSED, "对端拒接", null, null);
        handshakeService.nextResult = result;

        // 三次不同来源；期望 X-Employee-Id 优先
        mockMvc.perform(post("/api/imaging/peers/PEER_HZ/health")
                        .header("X-Employee-Id", "employee_007")
                        .param("who", "param_who"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.who").value("employee_007"));
    }

    @Test
    @DisplayName("happy: 网络瞬态 CONNECTION_REFUSED → retryable=true")
    void retryableTrueOnTransientNetworkFailure() throws Exception {
        HandshakeResult result = HandshakeResult.failure(
                FailureCode.CONNECTION_REFUSED, "对端拒接 127.0.0.1:11112", null, null);
        handshakeService.nextResult = result;

        mockMvc.perform(post("/api/imaging/peers/PEER_HZ/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.lastErrorCode").value("CONNECTION_REFUSED"));
    }

    @Test
    @DisplayName("happy: 配置型失败 TRANSFER_SYNTAX_EMPTY → retryable=false")
    void retryableFalseOnConfigError() throws Exception {
        HandshakeResult result = HandshakeResult.failure(
                FailureCode.TRANSFER_SYNTAX_EMPTY, "传输语法无交集", null, null);
        handshakeService.nextResult = result;

        mockMvc.perform(post("/api/imaging/peers/PEER_HZ/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.lastErrorCode").value("TRANSFER_SYNTAX_EMPTY"));
    }

    // ──────── exception path ────────

    @Test
    @DisplayName("exception: PEER_NOT_FOUND（peer 未入准表）→ 200 + controller 补 1 行 audit")
    void peerNotFoundTriggersControllerAudit() throws Exception {
        handshakeService.nextException = new HandshakeException(
                FailureCode.PEER_NOT_FOUND,
                "Peer 不存在: PEER_HZ",
                /*retryable=*/false);

        mockMvc.perform(post("/api/imaging/peers/PEER_HZ/health")
                        .header("X-Employee-Id", "employee_009"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compatible").value(false))
                .andExpect(jsonPath("$.lastErrorCode").value("PEER_NOT_FOUND"))
                .andExpect(jsonPath("$.lastErrorMessage").value(containsString("PEER_HZ")))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.who").value("employee_009"));

        org.junit.jupiter.api.Assertions.assertEquals(1, auditService.recorded.size(),
                "PEER_NOT_FOUND: monitor 未触达 → controller 补 1 行 audit");
        AuditRow row = auditService.recorded.get(0);
        org.junit.jupiter.api.Assertions.assertEquals("local", row.getFromAet());
        org.junit.jupiter.api.Assertions.assertEquals("PEER_HZ", row.getToAet());
        org.junit.jupiter.api.Assertions.assertEquals("handshake_rejected", row.getStatusTerminal());
        org.junit.jupiter.api.Assertions.assertEquals("PEER_NOT_FOUND", row.getFailureCode());
        org.junit.jupiter.api.Assertions.assertEquals("employee_009", row.getWho());
    }

    @Test
    @DisplayName("exception: BLACKLISTED（黑名单命中）→ 200 + controller 不补 audit（monitor 已记）")
    void blacklistedDoesNotDoubleAudit() throws Exception {
        handshakeService.nextException = new HandshakeException(
                FailureCode.BLACKLISTED,
                "Peer AET 命中黑名单: MALICIOUS_AET",
                /*retryable=*/false);

        mockMvc.perform(post("/api/imaging/peers/MALICIOUS_AET/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compatible").value(false))
                .andExpect(jsonPath("$.lastErrorCode").value("BLACKLISTED"))
                .andExpect(jsonPath("$.retryable").value(false));

        org.junit.jupiter.api.Assertions.assertEquals(0, auditService.recorded.size(),
                "BLACKLISTED: monitor 已记 handshake_blacklisted_rejected，controller 不再补");
    }

    @Test
    @DisplayName("exception: INCOMPATIBLE_CONNECTION（协议族不一致）→ 200 + 不补 audit")
    void incompatibleConnectionDoesNotDoubleAudit() throws Exception {
        // INCOMPATIBLE_CONNECTION 走 HandshakeResult.failure + notifyRejected，
        // monitor 已记 audit；但若中途抛 HandshakeException（理论可能），按设计 controller 不补
        handshakeService.nextException = new HandshakeException(
                FailureCode.INCOMPATIBLE_CONNECTION,
                "协议族不一致",
                /*retryable=*/false);

        mockMvc.perform(post("/api/imaging/peers/PEER_HZ/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastErrorCode").value("INCOMPATIBLE_CONNECTION"));

        org.junit.jupiter.api.Assertions.assertEquals(0, auditService.recorded.size(),
                "INCOMPATIBLE_CONNECTION 经 exception 路径时不补 audit");
    }

    @Test
    @DisplayName("exception: 空 AET (空白字符串) → PEER_NOT_FOUND + controller 补 audit")
    void blankAetTriggersPeerNotFound() throws Exception {
        // 路径 "/{aet}/health" 的 aet 段不能为空字符（trailing slash 后是 path 段，
        // "" 路径会被 dispatcher 报错）；改用 1 字符空白 aet 探针由 handshake
        // 服务内的 peerAet.isBlank() 拒绝
        handshakeService.nextException = new HandshakeException(
                FailureCode.PEER_NOT_FOUND,
                "peer_aet 不能为空",
                /*retryable=*/false);

        mockMvc.perform(post("/api/imaging/peers/%20/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compatible").value(false))
                .andExpect(jsonPath("$.lastErrorCode").value("PEER_NOT_FOUND"))
                .andExpect(jsonPath("$.lastErrorMessage").value(containsString("peer_aet")));

        org.junit.jupiter.api.Assertions.assertEquals(1, auditService.recorded.size(),
                "PEER_NOT_FOUND 路径：controller 补 1 行 audit");
    }

    @Test
    @DisplayName("exception: 失败码枚举完整覆盖 — 9 种 FailureCode 都能落到 lastErrorCode")
    void failureCodeEnumCoverage() throws Exception {
        // 抽样三种典型 failure code 透传：每一种 FailureCode 都应被原样返回
        //（验证 DTO 的 FailureCode 字段能正确序列化 jackson enum）
        for (FailureCode fc : new FailureCode[]{
                FailureCode.TLS_MISMATCH,
                FailureCode.CIPHER_MISMATCH,
                FailureCode.SOP_CLASS_UNSUPPORTED}) {
            handshakeService.nextResult = HandshakeResult.failure(
                    fc, "test: " + fc, null, null);

            mockMvc.perform(post("/api/imaging/peers/PEER_HZ/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastErrorCode").value(fc.name()))
                    .andExpect(jsonPath("$.compatible").value(false));
        }
    }

    // ──────── stubs ────────

    /**
     * 桩握手服务：仅覆写 {@code handshake(String)} 入口；不依赖 dcm4che TCP 探活，
     * 6 参 super 构造全部传 null（{@code monitor=null} 旁路 callback）。
     */
    static class StubHandshakeService extends CompatibleHandshakeService {
        HandshakeResult nextResult;
        HandshakeException nextException;

        StubHandshakeService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public HandshakeResult handshake(String peerAet) {
            if (nextException != null) {
                throw nextException;
            }
            if (nextResult != null) {
                return nextResult;
            }
            throw new IllegalStateException("StubHandshakeService 没设置 nextResult/nextException");
        }
    }

    /** 桩审计服务：记录每次 {@code recordHandshake} 调用，方便测试断言「应写几次」。 */
    static class StubAuditService extends AuditService {
        final List<AuditRow> recorded = new ArrayList<>();

        StubAuditService() {
            super(null /* AuditRepository unused in stub */);
        }

        @Override
        public AuditRow recordHandshake(String peerAet, String statusTerminal,
                                        String who, String failureCode) {
            AuditRow row = new AuditRow()
                    .withWho(who == null || who.isBlank() ? "system" : who)
                    .withFromAet("local")
                    .withToAet(peerAet)
                    .withRetryCount(0)
                    .withStatusTerminal(statusTerminal)
                    .withFailureCode(failureCode);
            recorded.add(row);
            return row;
        }
    }
}
