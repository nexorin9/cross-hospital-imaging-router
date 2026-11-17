package com.crosshospital.imaging.service;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.security.AuditChecksum;
import com.crosshospital.imaging.security.AuditChecksumVerifier;
import com.crosshospital.imaging.support.AuditTestFixtures;
import com.crosshospital.imaging.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 25 — <b>真实 PostgreSQL</b> 下的 append-only 审计链核验。
 *
 * <p><b>这个测试类存在的理由</b>：审计链的两条防线必须分别在真环境里验过 ——
 * <ol>
 *   <li><b>应用层</b>：链式 {@code row_checksum}（{@code prev_checksum} 接上一行的
 *       {@code row_checksum}）—— 由 {@link AuditService} 保证，task 24 已用内存表验证；</li>
 *   <li><b>数据库层</b>：{@code audit_no_update} 触发器拒绝任何 UPDATE / DELETE /
 *       TRUNCATE —— 这一条<b>只在真 PostgreSQL 上验得到</b>。H2 跑不了 plpgsql 触发器，
 *       内存替身里那句「拒绝修改」只是我们自己写的 if，证明不了生产库上那条
 *       {@code RAISE EXCEPTION} 真的会触发。</li>
 * </ol>
 *
 * <p><b>测试库</b>：进程内嵌 PostgreSQL（{@link EmbeddedPostgresSupport}），
 * Flyway 的 V1–V8 迁移脚本原样执行 —— 与生产环境走的是同一份 SQL。
 *
 * <p><b>平台限制</b>：当前平台拿不到内嵌 PostgreSQL 二进制时，本类用
 * {@code @EnabledIf} <b>整类跳过</b>（跳过 ≠ 通过，会在报告里显式标出）。
 */
@EnabledIf("com.crosshospital.imaging.support.EmbeddedPostgresSupport#isAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "hl7.trigger.port=0",
        "routing.local-move-dest-port=0",
        // schema 由 Flyway 独占（V1–V8），Hibernate 不参与 DDL
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@DisplayName("Task 25 — 真实 PostgreSQL：append-only 触发器 + 链式校验和 + 多条件查询")
class AuditAppendOnlyPostgresTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private AuditQueryService auditQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedPostgresSupport::jdbcUrl);
        registry.add("spring.datasource.username", EmbeddedPostgresSupport::username);
        registry.add("spring.datasource.password", EmbeddedPostgresSupport::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    @DisplayName("迁移脚本建出了审计表与 audit_no_update 触发器")
    void migrationCreatesAuditTableAndAppendOnlyTrigger() {
        Integer tables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = 'public' and table_name = 'audit_imaging_fetch'",
                Integer.class);
        assertEquals(1, tables, "V3 迁移应建出 audit_imaging_fetch 表");

        Integer triggers = jdbcTemplate.queryForObject(
                "select count(*) from pg_trigger "
                        + "where tgname = 'audit_no_update' "
                        + "and tgrelid = 'audit_imaging_fetch'::regclass",
                Integer.class);
        assertEquals(1, triggers, "audit_no_update 触发器应存在于 pg_trigger");

        // 触发器覆盖 UPDATE / DELETE / TRUNCATE 三种改写动作
        Integer events = jdbcTemplate.queryForObject(
                "select count(distinct tgtype) from pg_trigger "
                        + "where tgname = 'audit_no_update' "
                        + "and tgrelid = 'audit_imaging_fetch'::regclass",
                Integer.class);
        assertEquals(1, events);
    }

    @Test
    @DisplayName("连续写入 100 行：真库里的链接得上，末行校验和等于链式期望值")
    void hundredRowsFormVerifiableChainInRealDatabase() {
        String marker = "PEER_CHAIN_100";
        Instant base = Instant.parse("2026-11-01T02:00:00Z");

        // 审计表只增不减（触发器连 TRUNCATE 都拒），所以本批之前表里通常已经有别的用例写的行。
        // 本批第一行的 prev_checksum 应当接上「批前链尾」的 row_checksum ——
        // 表空时批前链尾不存在，prev 就是 32 字节全 0。
        AuditRow tailBefore = auditRepository.findLatest().orElse(null);
        byte[] expectedPrev = tailBefore == null
                ? AuditChecksum.emptyChecksum()
                : tailBefore.getRowChecksum();

        for (int i = 0; i < 100; i++) {
            auditService.record(AuditTestFixtures.row(
                    "employee_" + String.format("%03d", i),
                    base.plusSeconds(i),
                    "local",
                    marker,
                    "<EXAMPLE_STUDY_UID>",
                    i % 5 == 0 ? "failed" : "stored",
                    i % 5 == 0 ? "TIMEOUT" : null,
                    i % 3));
        }

        List<AuditRow> rows = readAscendingByMarker(marker);
        assertEquals(100, rows.size(), "本轮写入的行数应为 100");

        // ① 本批第一行接上批前链尾（批前为空时为 32 字节全 0）
        assertArrayEquals(expectedPrev, rows.get(0).getPrevChecksum(),
                "本批第一行的 prev_checksum 没有接上批前链尾");

        // ② 每一行的 prev_checksum 都等于上一行的 row_checksum
        for (int i = 1; i < rows.size(); i++) {
            assertArrayEquals(rows.get(i - 1).getRowChecksum(), rows.get(i).getPrevChecksum(),
                    "第 " + (i + 1) + " 行没有接上上一行");
        }

        // ③ 末行 row_checksum = 独立参考实现算出的链式期望值
        byte[] expectedLast = AuditTestFixtures.referenceChecksum(
                rows.get(98).getRowChecksum(), rows.get(99));
        assertArrayEquals(expectedLast, rows.get(99).getRowChecksum(),
                "末行校验和与按规格独立重算的结果不一致");

        // ④ 本段链校验通过（真库读回的值，不是内存对象）
        assertTrue(new AuditChecksumVerifier().verifyChain(rows, expectedPrev),
                "真库中的审计链应当完整一致");
    }

    @Test
    @DisplayName("UPDATE 被数据库拒绝：审计行内容保持原样")
    void updateIsRejectedByDatabaseTrigger() {
        AuditRow row = seed("PEER_UPDATE_GUARD", "stored", null);

        DataAccessException thrown = assertThrows(DataAccessException.class,
                () -> jdbcTemplate.update(
                        "update audit_imaging_fetch set status_terminal = 'tampered' where id = ?",
                        row.getId()));
        assertTrue(String.valueOf(thrown.getMessage()).contains("append-only"),
                "拒绝原因应来自 audit_no_update 触发器，实际：" + thrown.getMessage());

        AuditRow reloaded = auditRepository.findById(row.getId()).orElseThrow();
        assertEquals("stored", reloaded.getStatusTerminal(), "被拒绝的 UPDATE 不应改动任何字段");
    }

    @Test
    @DisplayName("DELETE 被数据库拒绝：审计行仍在")
    void deleteIsRejectedByDatabaseTrigger() {
        AuditRow row = seed("PEER_DELETE_GUARD", "stored", null);

        DataAccessException thrown = assertThrows(DataAccessException.class,
                () -> jdbcTemplate.update("delete from audit_imaging_fetch where id = ?", row.getId()));
        assertTrue(String.valueOf(thrown.getMessage()).contains("append-only"),
                "拒绝原因应来自 audit_no_update 触发器，实际：" + thrown.getMessage());

        assertTrue(auditRepository.findById(row.getId()).isPresent(), "被拒绝的 DELETE 不应删掉行");
    }

    @Test
    @DisplayName("TRUNCATE 被数据库拒绝：整表清空是改写审计的最狠一种")
    void truncateIsRejectedByDatabaseTrigger() {
        seed("PEER_TRUNCATE_GUARD", "stored", null);

        DataAccessException thrown = assertThrows(DataAccessException.class,
                () -> jdbcTemplate.execute("truncate table audit_imaging_fetch"));
        assertTrue(String.valueOf(thrown.getMessage()).contains("append-only"),
                "拒绝原因应来自 audit_no_update 触发器，实际：" + thrown.getMessage());
    }

    @Test
    @DisplayName("多条件组合查询在真库上返回预期行数（时间窗 + 源/目标 AET + 终态）")
    void multiConditionQueryReturnsExpectedRowCount() {
        String marker = "PEER_QUERY_GUARD";
        Instant base = Instant.parse("2026-11-02T02:00:00Z");
        // 10 行 stored + 10 行 failed，全部同一节点
        for (int i = 0; i < 10; i++) {
            auditService.record(AuditTestFixtures.row("employee_q", base.plusSeconds(i), "local",
                    marker, "<EXAMPLE_STUDY_UID>", "stored", null, 0));
        }
        for (int i = 0; i < 10; i++) {
            auditService.record(AuditTestFixtures.row("employee_q", base.plusSeconds(100 + i), "local",
                    marker, "<EXAMPLE_STUDY_UID>", "failed", "TIMEOUT", 1));
        }

        long totalForNode = auditQueryService.countByTimeWindow(base.minusSeconds(60),
                base.plusSeconds(3600));
        assertTrue(totalForNode >= 20, "时间窗内应至少查到本轮写入的 20 行，实际 " + totalForNode);

        // 时间窗 + 目标 AET + 终态 = failed → 10 行
        List<AuditRow> failed = auditQueryService.queryByTimeWindow(
                base.minusSeconds(60), base.plusSeconds(3600), null, marker, "failed");
        assertEquals(10, failed.size(), "failed 终态应恰好 10 行");

        // 时间窗 + 目标 AET + 终态 = stored → 10 行
        List<AuditRow> stored = auditQueryService.queryByTimeWindow(
                base.minusSeconds(60), base.plusSeconds(3600), null, marker, "stored");
        assertEquals(10, stored.size(), "stored 终态应恰好 10 行");

        // 源 AET 不匹配时一行都没有（证明多条件是真 AND，不是只取最后一个条件）
        List<AuditRow> wrongFrom = auditQueryService.queryByTimeWindow(
                base.minusSeconds(60), base.plusSeconds(3600), "PEER_NOT_US", marker, "stored");
        assertEquals(0, wrongFrom.size(), "条件之间必须是 AND 关系");

        // 分页：按节点查全量（stored + failed = 20 行），两页各 10 行
        Page<AuditRow> page0 = auditQueryService.query(
                AuditQueryCriteria.ofTimeWindow(base.minusSeconds(60), base.plusSeconds(3600))
                        .withToAet(marker), 0, 10);
        Page<AuditRow> page1 = auditQueryService.query(
                AuditQueryCriteria.ofTimeWindow(base.minusSeconds(60), base.plusSeconds(3600))
                        .withToAet(marker), 1, 10);
        assertEquals(20, page0.getTotalElements(), "该节点在窗口内共 20 行");
        assertEquals(10, page0.getContent().size());
        assertEquals(10, page1.getContent().size());
        assertTrue(page0.getContent().stream().noneMatch(
                        r -> page1.getContent().contains(r)),
                "两页之间不应出现重复行");
    }

    @Test
    @DisplayName("整取路径在真库上不被单页上限截断；分页路径仍受单页上限保护")
    void exportPathIsNotTruncatedAtOnePageSize() {
        String marker = "PEER_EXPORT_GUARD";
        Instant base = Instant.parse("2026-11-03T02:00:00Z");
        int total = AuditQueryService.MAX_PAGE_SIZE + 100;   // 600 > 500

        for (int i = 0; i < total; i++) {
            auditService.record(AuditTestFixtures.row("employee_export", base.plusSeconds(i),
                    "local", marker, "<EXAMPLE_STUDY_UID>", "stored", null, 0));
        }

        // ① 整取（导出飞检材料）要 600 行就得真的拿到 600 行
        List<AuditRow> exported = auditQueryService.queryAll(
                AuditQueryCriteria.ofTimeWindow(base.minusSeconds(60), base.plusSeconds(3600))
                        .withToAet(marker), total);
        assertEquals(total, exported.size(),
                "整取路径不应被单页上限（" + AuditQueryService.MAX_PAGE_SIZE + "）截断 —— "
                        + "导出少给行而不报错，等于交上去的飞检材料是缺的");

        // ② 分页路径仍受单页上限保护：两个上限各管各的，不串味
        Page<AuditRow> page = auditQueryService.query(
                AuditQueryCriteria.ofTimeWindow(base.minusSeconds(60), base.plusSeconds(3600))
                        .withToAet(marker), 0, 100_000);
        assertEquals(AuditQueryService.MAX_PAGE_SIZE, page.getSize(),
                "分页路径的单页上限不应被整取路径放宽");
        assertEquals(total, page.getTotalElements(), "总数不受分页上限影响");
        assertEquals(AuditQueryService.MAX_PAGE_SIZE, page.getContent().size());
    }

    @Test
    @DisplayName("链尾行可查（运维自检「链头到链尾是否接得上」）")
    void chainTailIsQueryable() {
        AuditRow row = seed("PEER_TAIL_GUARD", "stored", null);
        AuditRow latest = auditQueryService.findLatest().orElseThrow();
        assertNotNull(latest.getId());
        assertTrue(latest.getId() >= row.getId(), "链尾不应早于刚写入的行");
    }

    private AuditRow seed(String toAet, String statusTerminal, String failureCode) {
        return auditService.record(AuditTestFixtures.row("employee_guard",
                Instant.now(), "local", toAet, "<EXAMPLE_STUDY_UID>",
                statusTerminal, failureCode, 0));
    }

    /** 按 id 升序读回本轮写入的行（真实数据库读取，非内存对象）。 */
    private List<AuditRow> readAscendingByMarker(String toAet) {
        List<AuditRow> desc = auditRepository.findLatestRows(Pageable.ofSize(500));
        List<AuditRow> asc = new ArrayList<>();
        for (int i = desc.size() - 1; i >= 0; i--) {
            if (toAet.equals(desc.get(i).getToAet())) {
                asc.add(desc.get(i));
            }
        }
        return asc;
    }
}
