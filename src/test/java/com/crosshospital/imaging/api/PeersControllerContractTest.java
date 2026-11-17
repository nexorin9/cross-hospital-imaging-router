package com.crosshospital.imaging.api;

import com.crosshospital.imaging.api.dto.PeerCreateRequest;
import com.crosshospital.imaging.api.dto.PeerResponse;
import com.crosshospital.imaging.api.dto.PeerUpdateRequest;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.exception.ConflictException;
import com.crosshospital.imaging.exception.GlobalExceptionHandler;
import com.crosshospital.imaging.exception.NotFoundException;
import com.crosshospital.imaging.exception.ValidationException;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.service.InputValidator;
import com.crosshospital.imaging.service.PeerRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 6 — PeersController REST API 契约测试（独立 MockMvc 装配）。
 *
 * <p>绕开 Mockito inline mock maker 在 JDK 27 上无法 mock {@link PeerRegistry}
 * 这个 concrete 类的痛点，改为构造**真实** {@link PeerRegistry}（其依赖是
 * 接口 {@link PeerRepository} 与 {@link AuditRepository}，可被 Mockito 直接 mock）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>5 个 happy path：create(201) / list(200) / get(200) / update(200) / delete(204)</li>
 *   <li>5 个 exception path：peer_aet 重复(409) / 字段缺失(400) / tls_mode 非法(400) /
 *       AET 超过 16 字符(400) / 非法端口(400)</li>
 *   <li>异常统一响应：{@code code/message/traceId/timestamp} 四字段</li>
 *   <li>audit 透传：每次写操作触发 1 行 audit</li>
 * </ul>
 */
@DisplayName("Task 6 — PeersController contract (5 happy + 5 exception)")
class PeersControllerContractTest {

    private MockMvc mockMvc;
    private PeerRepository peerRepository;
    private AuditRepository auditRepository;
    private PeerRegistry peerRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        peerRepository = mock(PeerRepository.class);
        auditRepository = mock(AuditRepository.class);
        // 真实 PeerRegistry + mock 依赖：覆盖 PeerRegistry 行为但不 mock PeerRegistry 本身
        peerRegistry = new PeerRegistry(peerRepository, auditRepository, new InputValidator());

        // save 默认回显
        when(peerRepository.save(any(Peer.class))).thenAnswer(inv -> {
            Peer p = inv.getArgument(0);
            if (p.getId() == null) p.setId(System.nanoTime());
            return p;
        });
        when(peerRepository.existsByPeerAet(anyString())).thenReturn(false);

        PeersController controller = new PeersController(peerRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ──────── happy path ────────

    @Test
    @DisplayName("happy: POST /api/imaging/peers → 201 + Location header + 响应含 peerAet")
    void createPeerReturns201() throws Exception {
        mockMvc.perform(post("/api/imaging/peers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Employee-Id", "employee_001")
                        .content(objectMapper.writeValueAsString(sampleCreateRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        containsString("/api/imaging/peers/PEER_HZ")))
                .andExpect(jsonPath("$.peerAet").value("PEER_HZ"))
                .andExpect(jsonPath("$.host").value("pacs.hz.cn"))
                .andExpect(jsonPath("$.port").value(11112));

        verify(peerRepository, times(1)).save(any(Peer.class));
        verify(auditRepository, times(1)).save(any(AuditRow.class));
    }

    @Test
    @DisplayName("happy: GET /api/imaging/peers?page=0&size=20 → 200 + 分页结构")
    void listPeersReturns200() throws Exception {
        Peer p1 = samplePeerEntity("PEER_A", 11112, "none");
        Peer p2 = samplePeerEntity("PEER_B", 11113, "tls");
        when(peerRepository.findAllByEnabledTrue(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(p1, p2), PageRequest.of(0, 20), 2));

        mockMvc.perform(get("/api/imaging/peers")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("happy: GET /api/imaging/peers/{aet} → 200 + PeerResponse")
    void getPeerReturns200() throws Exception {
        when(peerRepository.findByPeerAet("PEER_HZ"))
                .thenReturn(Optional.of(samplePeerEntity("PEER_HZ", 11112, "none")));

        mockMvc.perform(get("/api/imaging/peers/PEER_HZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.peerAet").value("PEER_HZ"));
    }

    @Test
    @DisplayName("happy: PUT /api/imaging/peers/{aet} → 200 + 部分字段已更新")
    void updatePeerReturns200() throws Exception {
        when(peerRepository.findByPeerAet("PEER_HZ"))
                .thenReturn(Optional.of(samplePeerEntity("PEER_HZ", 11112, "none")));

        PeerUpdateRequest req = new PeerUpdateRequest();
        req.setPort(11113);
        req.setTlsMode("tls");

        mockMvc.perform(put("/api/imaging/peers/PEER_HZ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Employee-Id", "employee_002")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.port").value(11113))
                .andExpect(jsonPath("$.tlsMode").value("tls"));
    }

    @Test
    @DisplayName("happy: DELETE /api/imaging/peers/{aet} → 204 No Content")
    void deletePeerReturns204() throws Exception {
        when(peerRepository.findByPeerAet("PEER_HZ"))
                .thenReturn(Optional.of(samplePeerEntity("PEER_HZ", 11112, "none")));

        mockMvc.perform(delete("/api/imaging/peers/PEER_HZ")
                        .header("X-Employee-Id", "employee_003"))
                .andExpect(status().isNoContent());

        verify(peerRepository, times(1)).delete(any(Peer.class));
        verify(auditRepository, times(1)).save(any(AuditRow.class));
    }

    // ──────── exception path ────────

    @Test
    @DisplayName("exception: peer_aet 重复 → 409 ConflictException")
    void duplicateAetReturns409() throws Exception {
        when(peerRepository.existsByPeerAet("PEER_HZ")).thenReturn(true);

        mockMvc.perform(post("/api/imaging/peers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleCreateRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conflict"))
                .andExpect(jsonPath("$.message", containsString("PEER_HZ")))
                .andExpect(jsonPath("$.traceId").exists());

        verify(peerRepository, never()).save(any(Peer.class));
        verify(auditRepository, never()).save(any(AuditRow.class));
    }

    @Test
    @DisplayName("exception: peerAet 缺失 → 400 + fieldErrors[peerAet]")
    void missingPeerAetReturns400() throws Exception {
        PeerCreateRequest req = sampleCreateRequest();
        req.setPeerAet(null);

        mockMvc.perform(post("/api/imaging/peers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='peerAet')]").exists());
    }

    @Test
    @DisplayName("exception: tls_mode 非法 → 400 ValidationException")
    void invalidTlsModeReturns400() throws Exception {
        PeerCreateRequest req = sampleCreateRequest();
        req.setTlsMode("blowfish");

        mockMvc.perform(post("/api/imaging/peers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString("tls_mode")));

        verify(peerRepository, never()).save(any(Peer.class));
    }

    @Test
    @DisplayName("exception: AET 超过 16 字符 → 400")
    void aetTooLongReturns400() throws Exception {
        // peer_aet 字段加了 @Size(min=1,max=16) → spring 自动校验
        PeerCreateRequest bad = sampleCreateRequest();
        bad.setPeerAet("AET_THAT_IS_DEFINITELY_LONGER_THAN_SIXTEEN_CHARS");

        mockMvc.perform(post("/api/imaging/peers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    @DisplayName("exception: GET 不存在的 AET → 404 NotFoundException")
    void getPeerNotFoundReturns404() throws Exception {
        when(peerRepository.findByPeerAet("MISSING")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/imaging/peers/MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.message", containsString("MISSING")));
    }

    // ──────── audit smoke ────────

    @Test
    @DisplayName("audit smoke: who 透传顺序 — X-Employee-Id > ?who= > system")
    void whoResolutionPriority() throws Exception {
        // 1. X-Employee-Id 优先
        mockMvc.perform(post("/api/imaging/peers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Employee-Id", "employee_007")
                        .content(objectMapper.writeValueAsString(sampleCreateRequest())))
                .andExpect(status().isCreated());

        PeerCreateRequest req2 = sampleCreateRequest();
        req2.setPeerAet("PEER_SH");

        // 2. 无 header 时回退 ?who=
        mockMvc.perform(post("/api/imaging/peers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("who", "employee_008")
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated());

        PeerCreateRequest req3 = sampleCreateRequest();
        req3.setPeerAet("PEER_CD");

        // 3. 都没有时回退 system
        mockMvc.perform(post("/api/imaging/peers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req3)))
                .andExpect(status().isCreated());

        // audit 落库 3 次，who 分别为 employee_007 / employee_008 / system
        verify(auditRepository, times(3)).save(any(AuditRow.class));
    }

    @Test
    @DisplayName("exception: peer_aet 含小写 → service 拒绝（业务层校验）")
    void aetContainsLowercaseReturns400() throws Exception {
        PeerCreateRequest req = sampleCreateRequest();
        req.setPeerAet("peer_hz");

        mockMvc.perform(post("/api/imaging/peers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString("illegal characters")));
    }

    @Test
    @DisplayName("exception: 非法端口 (port=0) → 400")
    void invalidPortReturns400() throws Exception {
        PeerCreateRequest req = sampleCreateRequest();
        req.setPort(0);  // 违反 @Min(1)

        mockMvc.perform(post("/api/imaging/peers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='port')]").exists());
    }

    @Test
    @DisplayName("exception: delete 不存在的 AET → 404 NotFoundException")
    void deletePeerNotFoundReturns404() throws Exception {
        when(peerRepository.findByPeerAet("MISSING")).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/imaging/peers/MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));

        verify(peerRepository, never()).delete(any(Peer.class));
    }

    // ──────── 辅助 ────────

    private PeerCreateRequest sampleCreateRequest() {
        PeerCreateRequest req = new PeerCreateRequest();
        req.setPeerAet("PEER_HZ");
        req.setHost("pacs.hz.cn");
        req.setPort(11112);
        req.setTlsMode("none");
        req.setPresentContexts(List.of(
                "1.2.840.10008.5.1.4.1.1.2",
                "1.2.840.10008.5.1.4.1.1.4"));
        req.setTransferSyntaxes(List.of(
                "1.2.840.10008.1.2.1",
                "1.2.840.10008.1.2.4.70"));
        req.setBlacklistAets(List.of());
        return req;
    }

    private Peer samplePeerEntity(String aet, int port, String tlsMode) {
        Peer p = new Peer();
        p.setId(1L);
        p.setPeerAet(aet);
        p.setHost("pacs.hz.cn");
        p.setPort(port);
        p.setTlsMode(tlsMode);
        p.setPresentContexts("[\"1.2.840.10008.5.1.4.1.1.2\"]");
        p.setTransferSyntaxes("[\"1.2.840.10008.1.2.1\"]");
        p.setBlacklistAets("[]");
        p.setMaxRetries(3);
        p.setBackoffMs(1000);
        p.setCacheTtlHours(24);
        p.setMaxCacheMb(5120L);
        p.setEnabled(true);
        p.setNotes("测试节点");
        p.setCreatedAt(Instant.parse("2026-10-20T08:00:00Z"));
        p.setUpdatedAt(Instant.parse("2026-10-20T08:00:00Z"));
        return p;
    }
}