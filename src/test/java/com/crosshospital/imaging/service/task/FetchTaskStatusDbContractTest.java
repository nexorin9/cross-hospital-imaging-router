package com.crosshospital.imaging.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.repository.FetchTaskRepository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Task 20 — 五态机状态列的<b>数据库契约</b>测试。
 *
 * <p><b>为什么单开一个测试</b>：{@link FetchTaskContextBootTest} 用
 * {@code ddl-auto=create-drop} 建表，Hibernate 生成的表<b>没有</b> V2 迁移里那条
 * {@code CHECK (status IN ('queued',...,'failed'))} 约束。所以「实体写进去的值
 * 能不能满足真实库表的约束」这件事，在那个测试里是看不见的。
 *
 * <p><b>这个测试守的是什么</b>：实体写进 {@code imaging_fetch_tasks.status} 列的
 * 字面量，必须是 V2 迁移 CHECK 清单里的<b>小写码</b>。
 *
 * <p>踩过的坑（本测试就是为它而留）：JPA 的 {@code @Enumerated(EnumType.STRING)}
 * 写的是枚举<b>常量名</b>（{@code QUEUED}），而 V2 的 CHECK 只允许小写
 * （{@code queued}）。两者对不上时，真实 PostgreSQL 上<b>每一行任务都插不进去</b>
 * —— 而 {@code Hl7DeadLetterHandler.persist()} 捕获异常只记一行日志，
 * 「表是空的，日志说已入队」，排障时看到的现象与真相完全相反。
 * 本仓库 {@code Hl7DeadLetter.failureCode} 已经踩过同一个坑并用
 * {@code AttributeConverter} 修好；任务状态列必须守同样的口径。
 *
 * <p>本测试用三道关守住它：
 * <ol>
 *   <li>原始列值（绕开实体映射，用 JdbcTemplate 直接读）必须等于
 *       {@link TaskStatus#toDbValue()}；</li>
 *   <li>六个状态的原始列值必须逐个出现在 V2 迁移文件解析出的 CHECK 清单里；</li>
 *   <li>把原始列值真的插进一张<b>带 V2 原文 CHECK 表达式</b>的探针表 ——
 *       插不进去就是违约，让数据库自己判。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:fetchtaskstatuscontract;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "hl7.trigger.port=0",
        "routing.local-move-dest-port=0"
})
@DisplayName("Task 20 — 状态列与 V2 CHECK 约束的数据库契约")
class FetchTaskStatusDbContractTest {

    /** V2 迁移里的 CHECK 表达式（原文抓取，用于建探针表）。 */
    private static final Pattern STATUS_CHECK = Pattern.compile(
            "CHECK\\s*\\(\\s*status\\s+IN\\s*\\(([^)]*)\\)\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    /** V2 迁移里的状态清单（原文抓取）。 */
    private static final List<String> MIGRATION_ALLOWED = readMigrationAllowedValues();

    @Autowired
    private FetchTaskRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 协议子步用替身：本测试只验状态列的数据库口径。 */
    @MockBean
    private FetchTaskStepExecutor stepExecutor;

    @BeforeEach
    void resetExecutor() {
        org.mockito.Mockito.when(stepExecutor.supports(org.mockito.ArgumentMatchers.any(TaskStatus.class)))
                .thenReturn(false);
    }

    @Test
    @DisplayName("V2 迁移的 CHECK 清单抓得到（6 个状态码）")
    void migrationCheckListIsReadable() {
        assertEquals(6, MIGRATION_ALLOWED.size(), "V2 CHECK 清单应含 6 个状态码：" + MIGRATION_ALLOWED);
        assertTrue(MIGRATION_ALLOWED.containsAll(List.of(
                        "queued", "querying", "pulling", "stored", "notified", "failed")),
                "V2 CHECK 清单应覆盖五态 + failed：" + MIGRATION_ALLOWED);
    }

    @Test
    @DisplayName("六个状态写进库里的原始列值都是小写码（不是枚举常量名）")
    void rawColumnValueIsLowercaseCode() {
        for (TaskStatus status : TaskStatus.values()) {
            UUID taskId = persistAndFlush(status);

            String raw = jdbcTemplate.queryForObject(
                    "select status from imaging_fetch_tasks where task_id = ?",
                    String.class, taskId);

            assertEquals(status.toDbValue(), raw,
                    "状态 " + status + " 写进库里的原始值应是小写码 " + status.toDbValue()
                            + "，实际是 " + raw + "（枚举常量名会让 V2 的 CHECK 违约）");
        }
    }

    @Test
    @DisplayName("六个状态的原始列值逐个落在 V2 的 CHECK 清单里")
    void rawColumnValueSatisfiesMigrationCheckList() {
        for (TaskStatus status : TaskStatus.values()) {
            UUID taskId = persistAndFlush(status);
            String raw = jdbcTemplate.queryForObject(
                    "select status from imaging_fetch_tasks where task_id = ?",
                    String.class, taskId);

            assertTrue(MIGRATION_ALLOWED.contains(raw),
                    "状态 " + status + " 写入的原始值 " + raw
                            + " 不在 V2 的 CHECK 清单 " + MIGRATION_ALLOWED + " 里");
        }
    }

    @Test
    @DisplayName("原始列值能真的插进带 V2 原文 CHECK 的表（让数据库自己判）")
    void rawColumnValuePassesRealCheckConstraint() {
        jdbcTemplate.execute("drop table if exists status_constraint_probe");
        jdbcTemplate.execute("create table status_constraint_probe ("
                + "status text not null "
                + "check (status in ('queued','querying','pulling','stored','notified','failed')))");

        for (TaskStatus status : TaskStatus.values()) {
            UUID taskId = persistAndFlush(status);
            String raw = jdbcTemplate.queryForObject(
                    "select status from imaging_fetch_tasks where task_id = ?",
                    String.class, taskId);

            // 若 raw 违反 CHECK，这里会抛 DataIntegrityViolationException —— 那正是
            // 真实 PostgreSQL 上「每一行任务都插不进去」的样子。
            jdbcTemplate.update("insert into status_constraint_probe (status) values (?)", raw);
        }

        Integer count = jdbcTemplate.queryForObject("select count(*) from status_constraint_probe", Integer.class);
        assertEquals(TaskStatus.values().length, count, "六个状态都应能过 CHECK 约束");
    }

    @Test
    @DisplayName("状态列能原样读回（写进去什么，实体读出来还是什么）")
    void statusRoundTripsThroughRepository() {
        for (TaskStatus status : TaskStatus.values()) {
            UUID taskId = persistAndFlush(status);

            FetchTask reloaded = repository.findByTaskId(taskId).orElseThrow();
            assertEquals(status, reloaded.getStatus(), "状态 " + status + " 读回后应不变");
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 落一行任务并立即 flush，返回 task_id。 */
    private UUID persistAndFlush(TaskStatus status) {
        FetchTask task = new FetchTask();
        task.setTaskId(UUID.randomUUID());
        task.setPatientMpi("PAT_MPI_EXAMPLE");
        task.setStudyUid("STUDY_EXAMPLE_CONTRACT_" + status.toDbValue());
        task.setPeerAet("PEER_HZ");
        task.setAccessionNo("ACC_EXAMPLE");
        task.setStatus(status);
        task.setRetryCount(0);
        task.setInitiatedBy("employee_007");
        task.setInitiatedSrc("api");
        return repository.saveAndFlush(task).getTaskId();
    }

    /**
     * 从 V2 迁移原文里解析出 CHECK 允许的状态码清单。
     *
     * <p>解析的是<b>迁移文件本身</b>，不是测试里抄一遍的常量 —— 迁移改了清单，
     * 这个测试跟着改，不会出现「测试里的清单和迁移里的清单各说各话」。
     */
    private static List<String> readMigrationAllowedValues() {
        String sql;
        try (InputStream in = FetchTaskStatusDbContractTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V2__imaging_fetch_tasks.sql")) {
            if (in == null) {
                throw new IllegalStateException("找不到 V2 迁移脚本");
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("读取 V2 迁移脚本失败", ex);
        }
        Matcher m = STATUS_CHECK.matcher(sql);
        if (!m.find()) {
            throw new IllegalStateException("V2 迁移里找不到 status 的 CHECK 约束");
        }
        List<String> values = new ArrayList<>();
        for (String part : m.group(1).split(",")) {
            String v = part.trim().replace("'", "");
            if (!v.isEmpty()) {
                values.add(v);
            }
        }
        return values;
    }
}
