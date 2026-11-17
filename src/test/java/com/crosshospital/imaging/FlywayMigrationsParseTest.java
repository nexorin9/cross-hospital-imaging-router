package com.crosshospital.imaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 · Task 3：验证 Flyway 迁移脚本在 classpath:db/migration 下按版本顺序排列、
 * 描述与表名一一对应、SQL 体内含必要的 DDL（包括 V3 的 append-only 触发器与
 * V2 的复合幂等唯一索引）。
 *
 * 不实际连接数据库（开发机无 PostgreSQL 实例），改用 classpath 资源读取做"飞行检查"。
 * 真实落库验证留给集成测试（task 35+）。
 */
@DisplayName("Task 3 — Flyway migrations discoverable & version-ordered")
class FlywayMigrationsParseTest {

    /** 期望的 V 号与 description，与 src/main/resources/db/migration/*.sql 一一对应。 */
    private static final List<String> EXPECTED_VERSIONS =
            Arrays.asList("1", "2", "3", "4", "5", "6", "7");
    private static final List<String> EXPECTED_DESCRIPTIONS = Arrays.asList(
            "imaging_peers",
            "imaging_fetch_tasks",
            "audit_imaging_fetch",
            "ai_summaries",
            "dlq_fetch_tasks",
            "ai_call_audit",
            "route_config");

    /** 每个 description 必须对应的 CREATE TABLE 名。 */
    private static final Map<String, String> EXPECTED_TABLES;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("imaging_peers",          "imaging_peers");
        m.put("imaging_fetch_tasks",    "imaging_fetch_tasks");
        m.put("audit_imaging_fetch",    "audit_imaging_fetch");
        m.put("ai_summaries",           "ai_summaries");
        m.put("dlq_fetch_tasks",        "dlq_fetch_tasks");
        m.put("ai_call_audit",          "ai_call_audit");
        m.put("route_config",           "route_config");
        EXPECTED_TABLES = Collections.unmodifiableMap(m);
    }

    /** 按版本号加载 classpath:db/migration/V{version}__{description}.sql。 */
    private static Map<String, String> loadMigrations() throws IOException {
        Map<String, String> byVersion = new TreeMap<>();
        for (int i = 0; i < EXPECTED_VERSIONS.size(); i++) {
            String version = EXPECTED_VERSIONS.get(i);
            String description = EXPECTED_DESCRIPTIONS.get(i);
            String resourcePath = "db/migration/V" + version + "__" + description + ".sql";
            try (InputStream in = FlywayMigrationsParseTest.class
                    .getClassLoader().getResourceAsStream(resourcePath)) {
                assertNotNull(in, "migration resource not found: " + resourcePath);
                byte[] bytes = in.readAllBytes();
                String sql = new String(bytes, StandardCharsets.UTF_8);
                assertFalse(sql.isBlank(), "migration SQL is blank: " + resourcePath);
                byVersion.put(version, sql);
            }
        }
        return byVersion;
    }

    @Test
    @DisplayName("V1..V7 migrations present on classpath with monotonically increasing versions")
    void migrationsDiscoverable() throws IOException {
        Map<String, String> byVersion = loadMigrations();
        assertEquals(EXPECTED_VERSIONS.size(), byVersion.size(),
                "expected exactly " + EXPECTED_VERSIONS.size() + " migrations, got "
                        + byVersion.keySet());

        // 版本号严格递增 + description 与预期一致
        List<String> versions = new ArrayList<>(byVersion.keySet());
        List<String> expected = new ArrayList<>(EXPECTED_VERSIONS);
        assertEquals(expected, versions, "version order mismatch");

        // 每一份 SQL 至少包含 CREATE TABLE 且 body 非空
        for (int i = 0; i < EXPECTED_VERSIONS.size(); i++) {
            String version = EXPECTED_VERSIONS.get(i);
            String sql = byVersion.get(version);
            String description = EXPECTED_DESCRIPTIONS.get(i);
            assertTrue(sql.contains("CREATE TABLE"),
                    "V" + version + " must contain CREATE TABLE");
            assertTrue(sql.contains(EXPECTED_TABLES.get(description)),
                    "V" + version + " must reference table " + EXPECTED_TABLES.get(description));
            // 不能含 MySQL 方言
            assertFalse(sql.contains("ENGINE=InnoDB"),
                    "V" + version + " must not use MySQL ENGINE=InnoDB");
            assertFalse(sql.contains("`"), "V" + version + " must not use MySQL backticks");
        }
    }

    @Test
    @DisplayName("V1 imaging_peers: AET 长度 / 端口 / 缓存上限 / 传输语法字段齐")
    void imagingPeersColumns() throws IOException {
        String sql = loadMigrations().get("1");
        // 用正则而不是硬编码空白数，避免和实际列宽对不齐
        assertTrue(sql.matches("(?s).*peer_aet\\s+TEXT\\s+NOT NULL UNIQUE.*"),
                "V1 peer_aet should be TEXT NOT NULL UNIQUE");
        assertTrue(sql.matches("(?s).*host\\s+TEXT\\s+NOT NULL.*"),
                "V1 host should be NOT NULL");
        assertTrue(sql.matches("(?s).*port\\s+INT\\s+NOT NULL\\s+DEFAULT 11112.*"),
                "V1 port should default to 11112");
        assertTrue(sql.matches("(?s).*tls_mode\\s+TEXT\\s+NOT NULL\\s+DEFAULT 'none'.*"),
                "V1 tls_mode should default to 'none'");
        assertTrue(sql.contains("present_contexts JSONB"),
                "V1 present_contexts should be JSONB");
        assertTrue(sql.contains("transfer_syntaxes JSONB"),
                "V1 transfer_syntaxes should be JSONB");
        assertTrue(sql.contains("blacklist_aets  JSONB"),
                "V1 blacklist_aets should be JSONB");
        assertTrue(sql.matches("(?s).*max_retries\\s+INT\\s+NOT NULL\\s+DEFAULT 3.*"),
                "V1 max_retries should default to 3");
        assertTrue(sql.contains("CONSTRAINT chk_peer_aet_len CHECK (char_length(peer_aet) BETWEEN 1 AND 16)"),
                "V1 must enforce AET length 1..16");
        assertTrue(sql.contains("CHECK (port BETWEEN 1 AND 65535)"),
                "V1 must enforce port range");
    }

    @Test
    @DisplayName("V2 imaging_fetch_tasks: 五态机状态 + 复合幂等唯一索引")
    void fetchTasksColumns() throws IOException {
        String sql = loadMigrations().get("2");
        assertTrue(sql.matches("(?s).*task_id\\s+UUID\\s+PRIMARY KEY.*"),
                "V2 task_id should be UUID PK");
        assertTrue(sql.matches("(?s).*status\\s+TEXT\\s+NOT NULL\\s+DEFAULT 'queued'.*"),
                "V2 status should default to queued");
        assertTrue(sql.contains("CHECK (status IN ('queued','querying','pulling','stored','notified','failed'))"),
                "V2 status CHECK must include all 6 enum values");
        // 复合幂等唯一索引是数据库层防 race 的硬约束
        assertTrue(sql.contains("CREATE UNIQUE INDEX idx_fetch_tasks_idem"),
                "V2 must create composite unique index idx_fetch_tasks_idem");
        assertTrue(sql.contains("ON imaging_fetch_tasks (patient_mpi, study_uid)"),
                "V2 unique index must be on (patient_mpi, study_uid)");
        assertTrue(sql.contains("WHERE status IN ('queued','querying','pulling','stored')"),
                "V2 unique index must be partial (exclude notified/failed)");
        // 乐观锁版本列：实体 @Version 映射了它，缺列则每一行任务都插不进去（task 23 补齐）
        assertTrue(sql.matches("(?s).*row_version\\s+BIGINT\\s+NOT NULL\\s+DEFAULT 0.*"),
                "V2 must define row_version BIGINT NOT NULL DEFAULT 0 (entity @Version maps it)");
    }

    @Test
    @DisplayName("V3 audit_imaging_fetch: append-only 触发器 + 链式 checksum 字段")
    void auditTableWithTrigger() throws IOException {
        String sql = loadMigrations().get("3");
        assertTrue(sql.matches("(?s).*row_checksum\\s+BYTEA\\s+NOT NULL.*"),
                "V3 row_checksum should be NOT NULL BYTEA");
        assertTrue(sql.matches("(?s).*prev_checksum\\s+BYTEA\\s+NOT NULL.*"),
                "V3 prev_checksum should be NOT NULL BYTEA");
        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION reject_modification"),
                "V3 must define reject_modification() function");
        assertTrue(sql.contains("RAISE EXCEPTION 'audit_imaging_fetch is append-only"),
                "V3 trigger function must raise append-only exception");
        assertTrue(sql.contains("CREATE TRIGGER audit_no_update"),
                "V3 must create audit_no_update trigger");
        assertTrue(sql.contains("BEFORE UPDATE OR DELETE OR TRUNCATE"),
                "V3 trigger must block UPDATE/DELETE/TRUNCATE");
        assertTrue(sql.contains("ON audit_imaging_fetch"),
                "V3 trigger must attach to audit_imaging_fetch");
    }

    @Test
    @DisplayName("V4 ai_summaries: source model/rule_fallback CHECK 区分 AI 真实路径与降级")
    void aiSummariesSourceEnum() throws IOException {
        String sql = loadMigrations().get("4");
        assertTrue(sql.contains("CHECK (kind IN ('report_normalize','feasibility_screener'))"),
                "V4 kind CHECK must enumerate the two AI kinds");
        assertTrue(sql.contains("CHECK (source IN ('model','rule_fallback'))"),
                "V4 source CHECK must distinguish model vs rule_fallback");
        assertTrue(sql.contains("payload     JSONB        NOT NULL"),
                "V4 payload must be JSONB NOT NULL");
    }

    @Test
    @DisplayName("V5 dlq_fetch_tasks: 未处置局部索引 + retry_attempts 约束")
    void dlqFetchTasksSchema() throws IOException {
        String sql = loadMigrations().get("5");
        assertTrue(sql.matches("(?s).*failure_code\\s+TEXT\\s+NOT NULL.*"),
                "V5 failure_code must be NOT NULL");
        assertTrue(sql.matches("(?s).*retry_attempts\\s+INT\\s+NOT NULL\\s+DEFAULT 0.*"),
                "V5 retry_attempts must default to 0");
        assertTrue(sql.matches("(?s).*resolved_at\\s+TIMESTAMPTZ.*"),
                "V5 resolved_at must be TIMESTAMPTZ");
        assertTrue(sql.contains("CREATE INDEX idx_dlq_unresolved"),
                "V5 must have idx_dlq_unresolved for unresolved DLQ listing");
        assertTrue(sql.contains("WHERE resolved_at IS NULL"),
                "V5 idx_dlq_unresolved must be partial on unresolved rows");
    }

    @Test
    @DisplayName("V6 ai_call_audit: 五态 status enum 覆盖 model 失败谱")
    void aiCallAuditStatusEnum() throws IOException {
        String sql = loadMigrations().get("6");
        assertTrue(sql.contains("CHECK (status IN ('success','timeout','parse_error','http_error','unreachable'))"),
                "V6 status CHECK must cover success + 4 failure modes");
    }

    @Test
    @DisplayName("V7 route_config: key-value JSONB 用于运行时配置热更新")
    void routeConfigSchema() throws IOException {
        String sql = loadMigrations().get("7");
        assertTrue(sql.matches("(?s).*key\\s+TEXT\\s+NOT NULL UNIQUE.*"),
                "V7 key must be UNIQUE");
        assertTrue(sql.matches("(?s).*value\\s+JSONB\\s+NOT NULL.*"),
                "V7 value must be JSONB NOT NULL");
    }

    @Test
    @DisplayName("所有 V*.sql 文件 SQL 语句能被 ; 拆分且不含空分号")
    void sqlSyntaxBasicCheck() throws IOException {
        Map<String, String> byVersion = loadMigrations();
        for (Map.Entry<String, String> e : byVersion.entrySet()) {
            String sql = e.getValue();
            // 简单分号分句（触发器函数体内的 ; 同样计数）
            String[] stmts = sql.split(";");
            int nonEmpty = 0;
            for (String s : stmts) {
                if (!s.trim().isEmpty()) nonEmpty++;
            }
            assertTrue(nonEmpty >= 2,
                    "V" + e.getKey() + " should contain multiple SQL statements, got "
                            + nonEmpty);
        }
    }
}
