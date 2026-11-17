package com.crosshospital.imaging.service.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.crosshospital.imaging.domain.RouteConfig;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.repository.RouteConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 4 · Task 19：外院节点定位（主索引命名空间 → 外院 AET）的规则测试。
 *
 * <p>这一层是「HIS 触发的调阅到底该去哪家外院拉」的决策点。规则必须<b>确定</b>、
 * <b>可解释</b>、<b>失败时不崩</b>：配错了不能让触发通道跟着挂。
 */
@DisplayName("Task 19 — 外院节点定位（主索引命名空间 → AET）")
class PeerRoutingResolverTest {

    private final RouteConfigRepository routeConfigRepository = mock(RouteConfigRepository.class);
    private final PeerRepository peerRepository = mock(PeerRepository.class);

    private PeerRoutingResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PeerRoutingResolver(routeConfigRepository, peerRepository, new ObjectMapper());
    }

    private void givenNamespaces(String json) {
        RouteConfig cfg = new RouteConfig();
        cfg.setKey(PeerRoutingResolver.CONFIG_MPI_NAMESPACES);
        cfg.setValue(json);
        when(routeConfigRepository.findByKey(PeerRoutingResolver.CONFIG_MPI_NAMESPACES))
                .thenReturn(Optional.of(cfg));
    }

    private void givenDefaultPeer(String json) {
        RouteConfig cfg = new RouteConfig();
        cfg.setKey(PeerRoutingResolver.CONFIG_DEFAULT_PEER);
        cfg.setValue(json);
        when(routeConfigRepository.findByKey(PeerRoutingResolver.CONFIG_DEFAULT_PEER))
                .thenReturn(Optional.of(cfg));
    }

    // ------------------------------------------------------------------ 命名空间提取

    @Test
    @DisplayName("命名空间 = 主索引开头的连续字母；纯数字主索引没有命名空间")
    void namespaceExtraction() {
        assertThat(PeerRoutingResolver.namespaceOf("HZH0012345")).isEqualTo("HZH");
        assertThat(PeerRoutingResolver.namespaceOf("PAT_MPI_EXAMPLE")).isEqualTo("PAT");
        assertThat(PeerRoutingResolver.namespaceOf("12345")).isNull();
        assertThat(PeerRoutingResolver.namespaceOf("  HZH0012345  ")).isEqualTo("HZH");
        assertThat(PeerRoutingResolver.namespaceOf(null)).isNull();
    }

    @Test
    @DisplayName("主索引是 HL7 复合字段时只取第一个分量再取命名空间")
    void namespaceOfCompositePatientIdentifier() {
        assertThat(PeerRoutingResolver.namespaceOf("PAT_MPI_EXAMPLE^^^HIS_MPI^MR")).isEqualTo("PAT");
        assertThat(PeerRoutingResolver.namespaceOf("HZH0012345^^^HOSP_A^MR~OTHER001^^^HOSP_B^MR"))
                .isEqualTo("HZH");
    }

    // ------------------------------------------------------------------ 定位规则

    @Test
    @DisplayName("命名空间命中 → 返回对应外院 AET")
    void namespaceMatchResolvesPeer() {
        givenNamespaces("{\"HZH\": \"PEER_HZ\", \"BJ\": \"PEER_BJ\"}");
        when(peerRepository.existsByPeerAet("PEER_HZ")).thenReturn(true);

        PeerRoutingResolver.PeerRoutingResult result = resolver.resolve("HZH0012345");

        assertThat(result.resolved()).isTrue();
        assertThat(result.peerAet()).isEqualTo("PEER_HZ");
        assertThat(result.peerAetOptional()).contains("PEER_HZ");
        assertThat(result.reason()).contains("HZH");
    }

    @Test
    @DisplayName("主索引整体命中优先于命名空间命中（特例患者）")
    void exactPatientMatchWinsOverNamespace() {
        givenNamespaces("{\"HZH\": \"PEER_HZ\", \"HZH0012345\": \"PEER_SPECIAL\"}");
        when(peerRepository.existsByPeerAet("PEER_SPECIAL")).thenReturn(true);
        when(peerRepository.existsByPeerAet("PEER_HZ")).thenReturn(true);

        assertThat(resolver.resolve("HZH0012345").peerAet()).isEqualTo("PEER_SPECIAL");
    }

    @Test
    @DisplayName("命名空间未命中 → 用兜底默认节点")
    void fallbackDefaultPeerIsUsed() {
        givenNamespaces("{\"HZH\": \"PEER_HZ\"}");
        givenDefaultPeer("\"PEER_DEFAULT\"");
        when(peerRepository.existsByPeerAet("PEER_DEFAULT")).thenReturn(true);

        PeerRoutingResolver.PeerRoutingResult result = resolver.resolve("XX9999");

        assertThat(result.resolved()).isTrue();
        assertThat(result.peerAet()).isEqualTo("PEER_DEFAULT");
    }

    @Test
    @DisplayName("映射到的节点未在准入表登记 → 按未定位处理（不排注定建联失败的任务）")
    void unregisteredPeerIsUnresolved() {
        givenNamespaces("{\"HZH\": \"PEER_HZ\"}");
        when(peerRepository.existsByPeerAet("PEER_HZ")).thenReturn(false);

        PeerRoutingResolver.PeerRoutingResult result = resolver.resolve("HZH0012345");

        assertThat(result.resolved()).isFalse();
        assertThat(result.peerAet()).isNull();
        assertThat(result.peerAetOptional()).isEmpty();
        assertThat(result.reason()).contains("PEER_HZ").contains("未在准入表");
    }

    @Test
    @DisplayName("没配映射 → 未定位且给出可照着补配置的说明（不抛异常）")
    void missingConfigIsUnresolvedWithActionableReason() {
        when(routeConfigRepository.findByKey(anyString())).thenReturn(Optional.empty());

        PeerRoutingResolver.PeerRoutingResult result = resolver.resolve("HZH0012345");

        assertThat(result.resolved()).isFalse();
        assertThat(result.reason()).contains(PeerRoutingResolver.CONFIG_MPI_NAMESPACES);
    }

    @Test
    @DisplayName("映射 JSON 坏了 → 按未定位处理并记日志（不抛异常带崩触发通道）")
    void brokenJsonIsTreatedAsUnresolved() {
        givenNamespaces("{not-json");

        PeerRoutingResolver.PeerRoutingResult result = resolver.resolve("HZH0012345");

        assertThat(result.resolved()).isFalse();
        assertThat(result.reason()).contains(PeerRoutingResolver.CONFIG_MPI_NAMESPACES);
    }

    @Test
    @DisplayName("兜底节点 JSON 坏了 → 视为未配置兜底")
    void brokenDefaultPeerJsonIsTreatedAsMissing() {
        givenNamespaces("{\"HZH\": \"PEER_HZ\"}");
        givenDefaultPeer("\"unterminated");

        PeerRoutingResolver.PeerRoutingResult result = resolver.resolve("XX9999");

        assertThat(result.resolved()).isFalse();
        assertThat(result.reason()).contains(PeerRoutingResolver.CONFIG_DEFAULT_PEER);
    }

    @Test
    @DisplayName("映射键大小写不敏感（不同 HIS 的主索引前缀大小写不一致很常见）")
    void namespaceLookupIsCaseInsensitive() {
        givenNamespaces("{\"hzh\": \"PEER_HZ\"}");
        when(peerRepository.existsByPeerAet("PEER_HZ")).thenReturn(true);

        assertThat(resolver.resolve("HZH0012345").peerAet()).isEqualTo("PEER_HZ");
        assertThat(resolver.resolve("hzh0012345").peerAet()).isEqualTo("PEER_HZ");
    }

    @Test
    @DisplayName("空主索引 → 未定位，不查配置")
    void blankPatientMpiIsUnresolved() {
        assertThat(resolver.resolve(null).resolved()).isFalse();
        assertThat(resolver.resolve("  ").resolved()).isFalse();
    }
}
