package com.crosshospital.imaging.domain;

import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真 PostgreSQL 回归：{@code imaging_peers} 的三个 JSONB 列。
 *
 * <p><b>为什么必须上真库</b>：V1 迁移把 present_contexts / transfer_syntaxes /
 * blacklist_aets 建成 {@code jsonb}，实体侧是 {@link String}。缺
 * {@code @JdbcTypeCode(SqlTypes.JSON)} 时 Hibernate 按 varchar 绑参，
 * PG 直接抛 {@code 42804}。Mockito 替身与 H2 都不执行 PG 的类型检查，
 * 只有真库会拒绝 —— task 39 采集真实运行截图时，POST /api/imaging/peers
 * 真的返回了 500，才把这条漏网之鱼捞出来。
 *
 * <p>本测试锁住：①能写入；②列类型确实是 jsonb 而不是 text；③按 JSON 路径能读回原值。
 */
@EnabledIf("com.crosshospital.imaging.support.EmbeddedPostgresSupport#isAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "hl7.trigger.port=0",
        "routing.local-move-dest-port=0",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@DisplayName("真 PostgreSQL：imaging_peers 的 JSONB 列写入与读回")
class PeerJsonbPostgresTest {

    @Autowired
    private PeerRepository peers;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedPostgresSupport::jdbcUrl);
        registry.add("spring.datasource.username", EmbeddedPostgresSupport::username);
        registry.add("spring.datasource.password", EmbeddedPostgresSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    @DisplayName("三个 JSONB 数组列能落库、列类型是 jsonb、按 JSON 路径可读回")
    void jsonbColumnsRoundTrip() {
        String aet = "PGJSON_" + Math.abs(System.nanoTime() % 100000L);
        Peer peer = new Peer();
        peer.setPeerAet(aet);
        peer.setHost("127.0.0.1");
        peer.setPort(11112);
        peer.setPresentContexts("[\"1.2.840.10008.5.1.4.1.1.2\"]");
        peer.setTransferSyntaxes("[\"1.2.840.10008.1.2.1\"]");
        peer.setBlacklistAets("[\"BAD_AET\"]");

        Peer saved = peers.save(peer);

        assertTrue(saved.getId() != null, "保存后应有主键");
        assertEquals("jsonb", jdbc.queryForObject(
                "select pg_typeof(present_contexts)::text from imaging_peers where peer_aet = ?",
                String.class, aet));
        assertEquals("1.2.840.10008.5.1.4.1.1.2", jdbc.queryForObject(
                "select present_contexts ->> 0 from imaging_peers where peer_aet = ?",
                String.class, aet));
        assertEquals("BAD_AET", jdbc.queryForObject(
                "select blacklist_aets ->> 0 from imaging_peers where peer_aet = ?",
                String.class, aet));

        Peer reloaded = peers.findByPeerAet(aet).orElseThrow();
        assertEquals("[\"1.2.840.10008.5.1.4.1.1.2\"]", reloaded.getPresentContexts());
        assertEquals("[\"1.2.840.10008.1.2.1\"]", reloaded.getTransferSyntaxes());
        assertEquals("[\"BAD_AET\"]", reloaded.getBlacklistAets());
    }
}
