package com.crosshospital.imaging;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.repository.AiCallAuditRepository;
import com.crosshospital.imaging.repository.AiSummaryRepository;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.repository.DlqRepository;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.repository.RouteConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase 4 · Task 4: 验证 7 张表的 JPA 实体 + Spring Data Repository.
 *
 * <p>覆盖:
 * <ul>
 *   <li>7 个 Repository 接口均存在并继承 Spring Data {@code Repository} 标记接口</li>
 *   <li>AuditRepository 编译期不存在任何 delete-asterisk / update 方法
 *       (仅继承 {@link Repository} 标记, 不继承 {@code JpaRepository})</li>
 *   <li>AuditRepository 源文件 grep 不到 deleteById / deleteAll / update 关键字</li>
 *   <li>TaskStatus 六态机的合法/非法跃迁与 V2 CHECK 字符串对齐</li>
 *   <li>实体类的关键字段默认值与 V*.sql DEFAULT 对齐</li>
 * </ul>
 *
 * <p>开发机无 PostgreSQL / Docker (task 3 已说明), 故本测试与 Task 3 一致
 * 采用 classpath 资源 + 反射做"飞行检查"; 真实持久化验证 (UNIQUE 约束、
 * trigger 拦截、JOIN 查询、@DataJpaTest 注入等) 由 task 35 集成测试用
 * TestContainers 完成.
 */
@DisplayName("Task 4 — JPA entities + Spring Data repositories (flight-check)")
class JpaEntitiesAndRepositoriesTest {

    /** 期望存在的 7 个 Repository 类型（与 V1–V7 表一一对应）。 */
    private static final List<Class<?>> EXPECTED_REPOS = List.of(
            PeerRepository.class,
            FetchTaskRepository.class,
            AuditRepository.class,
            AiSummaryRepository.class,
            DlqRepository.class,
            AiCallAuditRepository.class,
            RouteConfigRepository.class);

    // ──────── Repository 接口存在性与 Spring Data 标记 ────────

    @Test
    @DisplayName("7 个 Repository 接口均存在并继承 Spring Data Repository 标记")
    void allSevenRepositoriesExist() {
        for (Class<?> repo : EXPECTED_REPOS) {
            assertNotNull(repo, "Repository class must exist");
            assertTrue(Repository.class.isAssignableFrom(repo),
                    repo.getSimpleName() + " should extend Spring Data Repository");
        }
        assertEquals(7, EXPECTED_REPOS.size(), "exactly 7 repositories expected");
    }

    @Test
    @DisplayName("PeerRepository 暴露按 AET 查找方法（domain 字段直读）")
    void peerRepositoryExposesAetLookup() {
        // findByPeerAet(String) 必须存在；这是 DicomRoutingService 路由必备
        boolean found = Arrays.stream(PeerRepository.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("findByPeerAet")
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].equals(String.class));
        assertTrue(found, "PeerRepository must expose findByPeerAet(String)");
    }

    @Test
    @DisplayName("FetchTaskRepository 暴露幂等查询 + 卡死任务查询方法")
    void fetchTaskRepositoryExposesIdempotencyAndStuckMethods() {
        List<String> methodNames = Arrays.stream(FetchTaskRepository.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toList());
        assertTrue(methodNames.contains("findActiveByPatientMpiAndStudyUid"),
                "FetchTaskRepository must expose findActiveByPatientMpiAndStudyUid");
        assertTrue(methodNames.contains("findStuckTasks"),
                "FetchTaskRepository must expose findStuckTasks for StuckTaskDetector");
        assertTrue(methodNames.contains("aggregateByStatusBetween"),
                "FetchTaskRepository must expose aggregateByStatusBetween for ops reporting");
    }

    @Test
    @DisplayName("DlqRepository 暴露未处置列表查询 + 标记已处置查询方法")
    void dlqRepositoryExposesUnresolvedMethods() {
        List<String> methodNames = Arrays.stream(DlqRepository.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toList());
        assertTrue(methodNames.contains("findUnresolved"),
                "DlqRepository must expose findUnresolved for unresolved DLQ listing");
        assertTrue(methodNames.contains("findResolvedBefore"),
                "DnQRepository must expose findResolvedBefore for retention cleanup");
    }

    @Test
    @DisplayName("AiCallAuditRepository 暴露按状态聚合 + 平均延迟查询")
    void aiCallAuditRepositoryExposesAggregation() {
        List<String> methodNames = Arrays.stream(AiCallAuditRepository.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toList());
        assertTrue(methodNames.contains("aggregateByStatusBetween"),
                "AiCallAuditRepository must expose aggregateByStatusBetween");
        assertTrue(methodNames.contains("averageLatencyBySource"),
                "AiCallAuditRepository must expose averageLatencyBySource");
    }

    @Test
    @DisplayName("RouteConfigRepository 暴露按 key 查找（运行时热更新配置）")
    void routeConfigRepositoryExposesKeyLookup() {
        boolean found = Arrays.stream(RouteConfigRepository.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("findByKey")
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].equals(String.class));
        assertTrue(found, "RouteConfigRepository must expose findByKey(String)");
    }

    // ──────── AuditRepository 编译期不暴露 delete/update ────────

    @Test
    @DisplayName("AuditRepository 仅继承 Repository 标记接口（不继承 JpaRepository）")
    void auditRepositoryIsAppendOnly() {
        Class<?>[] interfaces = AuditRepository.class.getInterfaces();
        Set<Class<?>> directParents = Arrays.stream(interfaces).collect(Collectors.toSet());
        assertEquals(1, directParents.size(),
                "AuditRepository should extend exactly one Spring Data interface, got: "
                        + directParents.stream().map(Class::getSimpleName)
                                .collect(Collectors.joining(",")));
        assertSame(Repository.class, directParents.iterator().next(),
                "AuditRepository should extend the marker interface Repository, "
                        + "not JpaRepository (which would expose delete* methods)");
    }

    @Test
    @DisplayName("AuditRepository 反射枚举所有方法，无任何 delete*/update* 签名")
    void auditRepositoryExposesNoMutateMethods() {
        List<String> publicMethods = Arrays.stream(AuditRepository.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toList());
        // 任何包含 "delete" 或 "update" 的方法名都不该出现
        List<String> banned = publicMethods.stream()
                .filter(name -> name.toLowerCase().contains("delete")
                        || name.toLowerCase().contains("update"))
                .collect(Collectors.toList());
        assertTrue(banned.isEmpty(),
                "AuditRepository must not expose delete*/update* methods, found: " + banned);
    }

    @Test
    @DisplayName("AuditRepository 源文件 grep 无 deleteById/deleteAll/update 关键字")
    void auditRepositorySourceHasNoBannedKeywords() throws IOException {
        Path sourcePath = locateSourceFile("AuditRepository.java");
        assertNotNull(sourcePath, "AuditRepository.java source must exist");
        String content = new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);
        // 删除多行注释和单行注释，避免被注释里的关键字误导
        String noComments = content.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//.*", "");
        assertFalse(noComments.matches("(?s).*\\bdeleteById\\b.*"),
                "AuditRepository must not contain deleteById");
        assertFalse(noComments.matches("(?s).*\\bdeleteAll\\b.*"),
                "AuditRepository must not contain deleteAll");
        assertFalse(noComments.matches("(?s).*\\bdelete\\b\\s*\\(.*"),
                "AuditRepository must not declare a delete(...) method");
        // 注意：update() 是 JpaRepository 自带的"全量替换"语义；append-only 表不该暴露
        assertFalse(noComments.matches("(?s).*\\bupdate\\b\\s*\\(.*"),
                "AuditRepository must not declare an update(...) method");
    }

    @Test
    @DisplayName("AuditRepository 唯一允许的写操作是 save(AuditRow)")
    void auditRepositoryOnlyWriteIsSave() {
        // 反射：找所有返回 AuditRow / void 且形参含 AuditRow 的方法，应该只有 save
        List<String> writers = Arrays.stream(AuditRepository.class.getDeclaredMethods())
                .filter(m -> Arrays.asList(m.getParameterTypes()).contains(AuditRow.class))
                .map(Method::getName)
                .collect(Collectors.toList());
        assertEquals(List.of("save"), writers,
                "AuditRepository should only allow save(AuditRow) as a write operation");
    }

    // ──────── TaskStatus 五态机正确性 ────────

    @Test
    @DisplayName("TaskStatus 六态与 V2 CHECK 字符串完全对齐")
    void taskStatusEnumMatchesDbCheck() {
        String sql;
        try {
            sql = new String(Files.readAllBytes(Paths.get(
                    "src", "main", "resources", "db", "migration",
                    "V2__imaging_fetch_tasks.sql")), StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("V2 migration file not readable: " + e.getMessage());
            return;
        }
        // V2 必须声明 6 个状态
        assertTrue(sql.contains(
                "CHECK (status IN ('queued','querying','pulling','stored','notified','failed'))"),
                "V2 must enumerate all six TaskStatus values");

        for (TaskStatus s : TaskStatus.values()) {
            assertTrue(sql.contains("'" + s.toDbValue() + "'"),
                    "V2 must enumerate status '" + s.toDbValue() + "' (from enum " + s + ")");
        }
    }

    @Test
    @DisplayName("TaskStatus.canTransitionTo 合法跃迁全过")
    void taskStatusLegalTransitions() {
        assertTrue(TaskStatus.QUEUED.canTransitionTo(TaskStatus.QUERYING));
        assertTrue(TaskStatus.QUEUED.canTransitionTo(TaskStatus.FAILED));
        assertTrue(TaskStatus.QUERYING.canTransitionTo(TaskStatus.PULLING));
        assertTrue(TaskStatus.QUERYING.canTransitionTo(TaskStatus.FAILED));
        assertTrue(TaskStatus.PULLING.canTransitionTo(TaskStatus.STORED));
        assertTrue(TaskStatus.PULLING.canTransitionTo(TaskStatus.FAILED));
        assertTrue(TaskStatus.STORED.canTransitionTo(TaskStatus.NOTIFIED));
        assertTrue(TaskStatus.STORED.canTransitionTo(TaskStatus.FAILED));
    }

    @Test
    @DisplayName("TaskStatus.canTransitionTo 非法跃迁被拒")
    void taskStatusIllegalTransitions() {
        // stored → querying 不允许（已写入 PACS 不应回退查询）
        assertFalse(TaskStatus.STORED.canTransitionTo(TaskStatus.QUERYING));
        // notified 是终态，不能再去任何状态
        assertFalse(TaskStatus.NOTIFIED.canTransitionTo(TaskStatus.QUERYING));
        assertFalse(TaskStatus.NOTIFIED.canTransitionTo(TaskStatus.STORED));
        assertFalse(TaskStatus.NOTIFIED.canTransitionTo(TaskStatus.FAILED));
        // failed 是终态
        assertFalse(TaskStatus.FAILED.canTransitionTo(TaskStatus.QUEUED));
        assertFalse(TaskStatus.FAILED.canTransitionTo(TaskStatus.QUERYING));
        // querying 跳到 stored（漏 pulling）不允许
        assertFalse(TaskStatus.QUERYING.canTransitionTo(TaskStatus.STORED));
        // queued 跳到 pulling 不允许（漏 querying）
        assertFalse(TaskStatus.QUEUED.canTransitionTo(TaskStatus.PULLING));
        // null 不允许
        assertFalse(TaskStatus.QUEUED.canTransitionTo(null));
    }

    @Test
    @DisplayName("TaskStatus.isTerminal: notified / failed 终态；其他非终态")
    void taskStatusIsTerminal() {
        EnumSet<TaskStatus> expectedTerminal = EnumSet.of(TaskStatus.NOTIFIED, TaskStatus.FAILED);
        for (TaskStatus s : TaskStatus.values()) {
            assertEquals(expectedTerminal.contains(s), s.isTerminal(),
                    "isTerminal mismatch on " + s);
        }
    }

    @Test
    @DisplayName("TaskStatus.isIdempotentBlock: queued/querying/pulling/stored；notified/failed 不在块内")
    void taskStatusIsIdempotentBlock() {
        EnumSet<TaskStatus> expectedBlock = EnumSet.of(
                TaskStatus.QUEUED, TaskStatus.QUERYING, TaskStatus.PULLING, TaskStatus.STORED);
        for (TaskStatus s : TaskStatus.values()) {
            assertEquals(expectedBlock.contains(s), s.isIdempotentBlock(),
                    "isIdempotentBlock mismatch on " + s);
        }
    }

    @Test
    @DisplayName("TaskStatus.fromDbValue 接受小写并抛 IllegalArgumentException on null/unknown")
    void taskStatusFromDbValue() {
        assertSame(TaskStatus.QUEUED, TaskStatus.fromDbValue("queued"));
        assertSame(TaskStatus.STORED, TaskStatus.fromDbValue("stored"));
        try {
            TaskStatus.fromDbValue(null);
            fail("expected IllegalArgumentException for null input");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            TaskStatus.fromDbValue("unknown");
            fail("expected IllegalArgumentException for unknown value");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ──────── 实体字段对齐 DDL ────────

    @Test
    @DisplayName("Peer 实体默认值与 V1 DEFAULT 对齐（max_retries=3 / backoff=1000 / ttl=24h / cache=5120MB）")
    void peerEntityDefaultValuesMatchV1() {
        Peer p = new Peer();
        p.setPeerAet("TEST_AET");
        p.setHost("10.0.0.1");
        p.setPort(11112);
        assertEquals("TEST_AET", p.getPeerAet());
        assertEquals("10.0.0.1", p.getHost());
        assertEquals(Integer.valueOf(11112), p.getPort());
        // 默认值与 V1 DEFAULT 对齐
        assertEquals(Integer.valueOf(3), p.getMaxRetries());
        assertEquals(Integer.valueOf(1000), p.getBackoffMs());
        assertEquals(Integer.valueOf(24), p.getCacheTtlHours());
        assertEquals(Long.valueOf(5120L), p.getMaxCacheMb());
        assertEquals("none", p.getTlsMode());
        assertEquals("[]", p.getPresentContexts());
        assertEquals("[]", p.getTransferSyntaxes());
        assertEquals("[]", p.getBlacklistAets());
        assertEquals(Boolean.TRUE, p.getEnabled());
    }

    @Test
    @DisplayName("FetchTask 实体五态机字段 + UUID 主键默认值")
    void fetchTaskEntityHasStateMachineAndUuid() {
        FetchTask t = new FetchTask();
        t.setTaskId(UUID.randomUUID());
        t.setPatientMpi("MPI-001");
        t.setStudyUid("<EXAMPLE_STUDY_UID>");
        t.setPeerAet("PEER_A");
        t.setInitiatedBy("employee_001");
        assertEquals(TaskStatus.QUEUED, t.getStatus(), "default status should be queued");
        assertEquals(Integer.valueOf(0), t.getRetryCount());
        assertEquals("api", t.getInitiatedSrc(), "default initiated_src should be api");
    }

    @Test
    @DisplayName("AuditRow 实体 append-only 防御：仅 getter + with* 链式写入，无 setter")
    void auditRowEntityIsAppendOnly() {
        // 反射枚举所有声明的方法，按 setter 前缀过滤；不暴露 setter
        List<String> setters = Arrays.stream(AuditRow.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("set"))
                .collect(Collectors.toList());
        assertTrue(setters.isEmpty(),
                "AuditRow must not expose setters (append-only), found: " + setters);
    }

    // ──────── 辅助 ────────

    /**
     * 在 Maven 模块的 source tree 中定位名为 {@code fileName} 的源文件。
     * 兼容多种工作目录（项目根/路径/子目录）。
     */
    private static Path locateSourceFile(String fileName) throws IOException {
        try (Stream<Path> walk = Files.walk(Paths.get("."))) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .filter(p -> p.toString().contains("src/main/java"))
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            fileName + " not found under src/main/java"));
        }
    }
}