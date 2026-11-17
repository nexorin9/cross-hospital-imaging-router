package com.crosshospital.imaging.service;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.exception.ValidationException;
import com.crosshospital.imaging.security.AuditChecksum;
import com.crosshospital.imaging.security.AuditChecksumVerifier;
import com.crosshospital.imaging.support.AuditTestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 25 单测：审计查询服务（多条件组合 + 分页口径）。
 *
 * <p>与 {@code AuditAppendOnlyPostgresTest} 的分工：那个类在<b>真 PostgreSQL</b> 上验
 * 「数据库拒不拒绝改写」；本类把<b>查询口径</b>钉死 —— 同样的条件，不同人跑出来的
 * 行数必须一样，这是飞检现场最常吵的问题。
 */
class AuditQueryServiceTest {

    private final AuditTestFixtures.InMemoryAuditRepository repository =
            new AuditTestFixtures.InMemoryAuditRepository();
    private final AuditService auditService = new AuditService(repository);
    private final AuditQueryService queryService = new AuditQueryService(repository);

    private static final Instant BASE = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    @DisplayName("连续写入 100 行：链首 prev 为 32 字节 0，逐行接续，末行等于链式期望值")
    void hundredRowsFormAValidChain() {
        for (int i = 0; i < 100; i++) {
            auditService.record(AuditTestFixtures.row(
                    "employee_" + String.format("%03d", i),
                    BASE.plusSeconds(i),
                    "local",
                    "PEER_HZ",
                    "<EXAMPLE_STUDY_UID>",
                    i % 5 == 0 ? "failed" : "stored",
                    i % 5 == 0 ? "TIMEOUT" : null,
                    i % 3));
        }

        List<AuditRow> rows = repository.all();
        assertEquals(100, rows.size());

        assertArrayEquals(AuditChecksum.emptyChecksum(), rows.get(0).getPrevChecksum(),
                "链首 prev_checksum 应为 32 字节全 0");
        for (int i = 1; i < rows.size(); i++) {
            assertArrayEquals(rows.get(i - 1).getRowChecksum(), rows.get(i).getPrevChecksum(),
                    "第 " + (i + 1) + " 行未接上上一行");
        }
        assertArrayEquals(AuditTestFixtures.referenceChecksum(rows.get(98).getRowChecksum(),
                rows.get(99)), rows.get(99).getRowChecksum(),
                "末行校验和与按规格独立重算的结果不一致");
        assertTrue(new AuditChecksumVerifier().verifyChain(rows));
    }

    @Test
    @DisplayName("多条件组合查询：条件之间是 AND，返回预期行数")
    void multiConditionQueryReturnsExpectedRowCount() {
        seedRows();

        // local → PEER_HZ 且 stored → 4 行
        List<AuditRow> stored = queryService.queryByTimeWindow(
                BASE.minusSeconds(1), BASE.plusSeconds(3600), "local", "PEER_HZ", "stored");
        assertEquals(4, stored.size());

        // local → PEER_HZ 且 failed → 2 行
        List<AuditRow> failed = queryService.queryByTimeWindow(
                BASE.minusSeconds(1), BASE.plusSeconds(3600), "local", "PEER_HZ", "failed");
        assertEquals(2, failed.size());

        // 换一个目标节点 → 3 行
        List<AuditRow> otherNode = queryService.queryByTimeWindow(
                BASE.minusSeconds(1), BASE.plusSeconds(3600), null, "PEER_NB", null);
        assertEquals(3, otherNode.size());

        // 源 AET 不匹配 → 0 行（证明不是只取最后一个条件）
        assertEquals(0, queryService.queryByTimeWindow(
                BASE.minusSeconds(1), BASE.plusSeconds(3600), "PEER_NOT_US", "PEER_HZ", null).size());
    }

    @Test
    @DisplayName("时间窗左闭右开：窗口外的行不计入")
    void timeWindowIsHalfOpen() {
        seedRows();

        // BASE 起的 6 行落在 [BASE, BASE+3600) 内
        assertEquals(9, queryService.countByTimeWindow(BASE, BASE.plusSeconds(3600)));

        // 起点含、终点不含：把起点推到 BASE+1s 后只剩 8 行
        assertEquals(8, queryService.countByTimeWindow(BASE.plusSeconds(1), BASE.plusSeconds(3600)));

        // 终点不含：到 BASE+4s 为止是 4 行（BASE, +1, +2, +3）
        assertEquals(4, queryService.countByTimeWindow(BASE, BASE.plusSeconds(4)));
    }

    @Test
    @DisplayName("分页：总数不变、页面之间不重不漏")
    void paginationKeepsTotalAndDoesNotOverlap() {
        seedRows();

        var page0 = queryService.query(AuditQueryCriteria.ofTimeWindow(BASE, BASE.plusSeconds(3600)),
                0, 4);
        var page1 = queryService.query(AuditQueryCriteria.ofTimeWindow(BASE, BASE.plusSeconds(3600)),
                1, 4);
        var page2 = queryService.query(AuditQueryCriteria.ofTimeWindow(BASE, BASE.plusSeconds(3600)),
                2, 4);

        assertEquals(9, page0.getTotalElements());
        assertEquals(4, page0.getContent().size());
        assertEquals(4, page1.getContent().size());
        assertEquals(1, page2.getContent().size());

        List<AuditRow> all = new java.util.ArrayList<>();
        all.addAll(page0.getContent());
        all.addAll(page1.getContent());
        all.addAll(page2.getContent());
        assertEquals(9, all.stream().distinct().count(), "分页结果不应重复");
    }

    @Test
    @DisplayName("单页上限 500 行：超限请求被夹住，不会把整张表拉出来")
    void pageSizeIsClampedToMax() {
        var page = queryService.query(AuditQueryCriteria.ofTimeWindow(BASE, BASE.plusSeconds(3600)),
                0, 100_000);
        assertEquals(AuditQueryService.MAX_PAGE_SIZE, page.getSize());
    }

    @Test
    @DisplayName("查询条件非法时报错（时间窗倒置 / 过滤值传空串）")
    void invalidCriteriaIsRejected() {
        ValidationException inverted = assertThrows(ValidationException.class,
                () -> queryService.query(AuditQueryCriteria.ofTimeWindow(BASE, BASE.minusSeconds(1)),
                        0, 10));
        assertTrue(inverted.getMessage().contains("fromTs"));

        assertThrows(ValidationException.class,
                () -> queryService.query(AuditQueryCriteria.ofTimeWindow(BASE, BASE.plusSeconds(1))
                        .withToAet("  "), 0, 10));
    }

    @Test
    @DisplayName("按源/目标 AET、按终态标签分别取行")
    void singleDimensionFinders() {
        seedRows();

        assertEquals(6, queryService.findByFromAet("local", 100).size());
        assertEquals(3, queryService.findByToAet("PEER_NB", 100).size());
        // stored = PEER_HZ 的 4 行 + PEER_NB 的 3 行
        assertEquals(7, queryService.findByStatusTerminal("stored", 100).size());
        assertEquals(2, queryService.findByStatusTerminal("failed", 100).size());
    }

    @Test
    @DisplayName("链尾行可查：最后写入的那一行")
    void chainTailIsQueryable() {
        seedRows();
        AuditRow latest = queryService.findLatest().orElseThrow();
        assertEquals(BASE.plusSeconds(8), latest.getWhenTs());
        assertEquals("employee_8", latest.getWho());
    }

    @Test
    @DisplayName("AuditService.queryByTimeWindow 与查询服务口径一致")
    void auditServiceDelegatesToQueryService() {
        seedRows();

        List<AuditRow> viaService = auditService.queryByTimeWindow(
                BASE.minusSeconds(1), BASE.plusSeconds(3600), "local", "PEER_HZ", "stored");
        List<AuditRow> viaQueryService = queryService.queryByTimeWindow(
                BASE.minusSeconds(1), BASE.plusSeconds(3600), "local", "PEER_HZ", "stored");

        assertEquals(viaQueryService.size(), viaService.size());
        assertEquals(4, viaService.size());
    }

    @Test
    @DisplayName("审计链被篡改时，查询结果仍然拿得出来，但校验会失败（查询不承担完整性判断）")
    void queryDoesNotPretendToJudgeIntegrity() {
        seedRows();
        repository.all().get(3).withStatusTerminal("stored_but_tampered");

        assertFalse(new AuditChecksumVerifier().verifyChain(repository.all()),
                "篡改后整链校验必须失败");
        assertEquals(6, queryService.findByStatusTerminal("stored", 100).size(),
                "查询侧只负责取行，完整性由校验器负责");
    }

    @Test
    @DisplayName("导出行数按 MAX_EXPORT_ROWS 生效：不被单页上限 500 静默截断")
    void exportIsNotSilentlyTruncatedAtOnePageSize() {
        int total = AuditQueryService.MAX_PAGE_SIZE + 100;
        seedRows(total);

        List<AuditRow> exported = queryService.queryAll(
                AuditQueryCriteria.ofTimeWindow(BASE, BASE.plusSeconds(3600)), total);
        assertEquals(total, exported.size(),
                "导出行数不应被单页上限（" + AuditQueryService.MAX_PAGE_SIZE + "）截断 —— "
                        + "飞检取材料时静默少给行，等于交上去的材料就是缺的");
    }

    @Test
    @DisplayName("limit=0 表示一行也不要，不被夹成 1 行")
    void zeroLimitReturnsNoRows() {
        seedRows(3);

        assertEquals(0, queryService.queryAll(
                AuditQueryCriteria.ofTimeWindow(BASE, BASE.plusSeconds(3600)), 0).size(),
                "limit=0 应返回空列表");
        assertEquals(0, queryService.findByStatusTerminal("stored", 0).size(),
                "limit=0 应返回空列表");
    }

    @Test
    @DisplayName("单维度取行按请求的行数返回，不被单页上限截断")
    void singleDimensionFindersHonourRequestedLimit() {
        int total = AuditQueryService.MAX_PAGE_SIZE + 100;
        seedRows(total);

        assertEquals(total,
                queryService.findByStatusTerminal("stored", total).size(),
                "单维度取行不应被单页上限截断");
    }

    /** 固定样本：9 行，覆盖 3 个目标节点 / 2 种终态 / 2 个源 AET。 */
    private void seedRows() {
        for (int i = 0; i < 6; i++) {
            auditService.record(AuditTestFixtures.row("employee_" + i, BASE.plusSeconds(i),
                    "local", "PEER_HZ", "<EXAMPLE_STUDY_UID>",
                    i < 4 ? "stored" : "failed", i < 4 ? null : "TIMEOUT", 0));
        }
        for (int i = 6; i < 9; i++) {
            auditService.record(AuditTestFixtures.row("employee_" + i, BASE.plusSeconds(i),
                    "PEER_NB", "PEER_NB", "<EXAMPLE_STUDY_UID>",
                    "stored", null, 0));
        }
    }

    /**
     * 批量播种 {@code count} 行（同一目标节点 / 同一终态），用于验证导出行数上限。
     */
    private void seedRows(int count) {
        for (int i = 0; i < count; i++) {
            auditService.record(AuditTestFixtures.row("employee_bulk", BASE.plusSeconds(i),
                    "local", "PEER_HZ", "<EXAMPLE_STUDY_UID>", "stored", null, 0));
        }
    }
}
