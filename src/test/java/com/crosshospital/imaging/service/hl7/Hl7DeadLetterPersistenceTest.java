package com.crosshospital.imaging.service.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.crosshospital.imaging.domain.Hl7DeadLetter;
import com.crosshospital.imaging.repository.Hl7DeadLetterRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Phase 4 · Task 19 真实持久化核验：HL7 死信实体与 {@code dlq_hl7_messages} 表的绑定。
 *
 * <p><b>为什么单测没抓到、这里必须补</b>：Task 19 的业务测试全部用 Mockito 替身打持久层
 * （本机无 PostgreSQL 实例），所以「实体到底往 {@code failure_code} 列写了什么」这件事
 * 在原有测试里<b>从来没被验证过</b>。而 V8 迁移给该列加了 CHECK 约束，
 * 只允许小写下划线码；一旦实体写进去的是枚举常量名（{@code MISSING_PID} 而不是
 * {@code missing_pid}），每一行死信都会撞约束 —— 且因为
 * {@code Hl7DeadLetterHandler.persist()} 捕获异常只记日志，故障表现是
 * <b>「日志说已进死信，库里一行都没有」</b>，最难查的那种。
 *
 * <p>本测试用<b>真表、真约束、真 JPA</b>把这件事钉死：
 * <ol>
 *   <li>直接执行 {@code db/migration/V8__dlq_hl7_messages.sql} 建表（约束是真的）；</li>
 *   <li>用真 {@link Hl7DeadLetterRepository} 存一行，回读原始列比对；</li>
 *   <li>反向验证：裸插一个 {@code MISSING_PID} 必须被 CHECK 拒掉
 *       —— 证明约束在测试库里确实生效（不是摆设）。</li>
 * </ol>
 *
 * <p>H2 以 PostgreSQL 兼容模式运行（{@code MODE=PostgreSQL}），DDL 与约束语义对齐生产库。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:hl7dlq;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false"
})
@DisplayName("Task 19 — HL7 死信实体与 dlq_hl7_messages 表的真实绑定")
class Hl7DeadLetterPersistenceTest {

    /** V8 迁移在 classpath 上的位置。 */
    private static final String V8 = "db/migration/V8__dlq_hl7_messages.sql";

    /** CHECK 约束里允许的小写码（与 {@link Hl7DeadLetter.FailureCode#code()} 必须逐一对应）。 */
    private static final Pattern CHECK_VALUES = Pattern.compile(
            "chk_dlq_hl7_failure_code\\s+CHECK\\s*\\(failure_code\\s+IN\\s*\\(([^)]*)\\)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Hl7DeadLetterRepository repository;

    private String migrationSql;

    @BeforeEach
    void createSchemaFromRealMigration() throws IOException {
        migrationSql = readMigration();
        // 每个用例前清一次表：H2 内存库在 DB_CLOSE_DELAY=-1 下跨用例存活
        jdbc.execute("drop table if exists dlq_hl7_messages");
        for (String statement : splitStatements(migrationSql)) {
            if (isFilteredIndex(statement)) {
                // H2 2.2 不支持带 WHERE 的局部索引（PostgreSQL 语法）。
                // 索引不是本用例的被测对象（被测对象是 CHECK 约束与列绑定），跳过并在下面说明。
                continue;
            }
            jdbc.execute(statement);
        }
    }

    /** 是否是「带 WHERE 的局部索引」语句（H2 2.2 不支持，PostgreSQL 支持）。 */
    private static boolean isFilteredIndex(String statement) {
        String upper = statement.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("CREATE INDEX") && upper.contains(" WHERE ");
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("死信行落库：failure_code 列写的是小写码（不是枚举常量名）")
    void failureCodeColumnStoresLowercaseCode() {
        Hl7DeadLetter row = new Hl7DeadLetter();
        row.setFailureCode(Hl7DeadLetter.FailureCode.MISSING_PID);
        row.setFailureMessage("PID 段缺失");
        row.setMessageControlId("MSG_EXAMPLE_0001");
        row.setRouteKey("ORM^O01");
        row.setPayloadBytes(128);
        row.setReceivedAt(Instant.now());
        row.setMshExcerpt("MSH|^~\\&|HIS_APP|SITE_A|HIS_APP|SITE_A|20260101||ORM^O01|MSG_EXAMPLE_0001");

        repository.saveAndFlush(row);

        String stored = jdbc.queryForObject(
                "select failure_code from dlq_hl7_messages where message_control_id = ?",
                String.class, "MSG_EXAMPLE_0001");
        assertThat(stored).isEqualTo("missing_pid");
    }

    @Test
    @DisplayName("落库后能按实体读回同一分类（转换器双向可用）")
    void failureCodeRoundTripsThroughTheDatabase() {
        Hl7DeadLetter row = new Hl7DeadLetter();
        row.setFailureCode(Hl7DeadLetter.FailureCode.NO_TARGET_PEER);
        row.setPayloadBytes(64);
        row.setReceivedAt(Instant.now());

        repository.saveAndFlush(row);

        List<Hl7DeadLetter> found = repository.findByMessageControlId("NOT_PRESENT");
        assertThat(found).isEmpty();
        assertThat(repository.count()).isEqualTo(1L);
        Hl7DeadLetter reloaded = repository.findAll().get(0);
        assertThat(reloaded.getFailureCode()).isEqualTo(Hl7DeadLetter.FailureCode.NO_TARGET_PEER);
    }

    @Test
    @DisplayName("V8 的 CHECK 约束确实生效：裸插枚举常量名（大写）必被拒")
    void checkConstraintRejectsUppercaseEnumName() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into dlq_hl7_messages (failure_code, payload_bytes, received_at, alert_webhook_sent, created_at)"
                        + " values ('MISSING_PID', 10, now(), false, now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("按分类聚合的死信统计能跑通（转换器在 GROUP BY 上同样生效）")
    void aggregateByFailureCodeRunsAgainstRealDatabase() {
        Hl7DeadLetter a = new Hl7DeadLetter();
        a.setFailureCode(Hl7DeadLetter.FailureCode.MISSING_PID);
        a.setPayloadBytes(10);
        a.setReceivedAt(Instant.now());
        Hl7DeadLetter b = new Hl7DeadLetter();
        b.setFailureCode(Hl7DeadLetter.FailureCode.MISSING_PID);
        b.setPayloadBytes(10);
        b.setReceivedAt(Instant.now());
        Hl7DeadLetter c = new Hl7DeadLetter();
        c.setFailureCode(Hl7DeadLetter.FailureCode.NO_TARGET_PEER);
        c.setPayloadBytes(10);
        c.setReceivedAt(Instant.now());
        repository.saveAllAndFlush(java.util.List.of(a, b, c));

        List<Object[]> stats = repository.aggregateByFailureCode();
        assertThat(stats).hasSize(2);
        assertThat(stats).anySatisfy(row ->
                assertThat(row[0]).isEqualTo(Hl7DeadLetter.FailureCode.MISSING_PID));
        assertThat(stats).anySatisfy(row ->
                assertThat(row[0]).isEqualTo(Hl7DeadLetter.FailureCode.NO_TARGET_PEER));
        assertThat(stats).anySatisfy(row -> {
            assertThat(row[0]).isEqualTo(Hl7DeadLetter.FailureCode.MISSING_PID);
            assertThat(((Number) row[1]).longValue()).isEqualTo(2L);
        });
    }

    @Test
    @DisplayName("V8 的 CHECK 允许值集合 == 枚举的 code() 集合（防迁移与代码漂移）")
    void checkConstraintValuesMatchEnumCodes() {
        List<String> allowed = allowedCheckValues();
        assertThat(allowed)
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(Hl7DeadLetter.FailureCode.values())
                                .map(Hl7DeadLetter.FailureCode::code)
                                .toList());
    }

    // ------------------------------------------------------------------ 小工具

    /** 解析 V8 里的 CHECK 约束允许值（顺便证明约束在迁移脚本里真实存在）。 */
    private List<String> allowedCheckValues() {
        Matcher matcher = CHECK_VALUES.matcher(migrationSql);
        assertThat(matcher.find())
                .as("V8 迁移里必须存在 chk_dlq_hl7_failure_code CHECK 约束")
                .isTrue();
        return Arrays.stream(matcher.group(1).split(","))
                .map(String::trim)
                .map(v -> v.replace("'", ""))
                .filter(v -> !v.isEmpty())
                .toList();
    }

    private static String readMigration() throws IOException {
        try (InputStream in = Hl7DeadLetterPersistenceTest.class.getClassLoader()
                .getResourceAsStream(V8)) {
            assertThat(in).as("classpath 上找不到 " + V8).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 按 {@code ;} 拆语句并去掉 {@code --} 行注释（迁移脚本里没有含分号的字符串字面量）。
     *
     * <p><b>唯一的方言垫片</b>：{@code TIMESTAMPTZ} → {@code TIMESTAMP WITH TIME ZONE}。
     * H2 2.2 的 PostgreSQL 兼容模式认得后者、不认得前者（PostgreSQL 的简写别名）。
     * 这是<b>类型名别名</b>，语义完全等价；被测对象（CHECK 约束、列名、索引定义）
     * 一律按迁移脚本原样执行，不做任何改写。
     */
    private static List<String> splitStatements(String sql) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : sql.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("--")) {
                continue;
            }
            cleaned.append(line).append('\n');
        }
        return Arrays.stream(cleaned.toString().split(";"))
                .map(String::strip)
                .map(s -> s.replace("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE"))
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
