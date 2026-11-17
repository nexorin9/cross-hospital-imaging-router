package com.crosshospital.imaging.service.task;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crosshospital.imaging.domain.FetchTask;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 23 — 任务实体的映射列 与 V2 迁移定义的列 必须<b>逐个对得上</b>。
 *
 * <p><b>为什么这道关卡守在幂等这一轮</b>：入队（INSERT）是幂等链路的<b>第一环</b> ——
 * 幂等键就是靠 {@code imaging_fetch_tasks} 这一行的
 * {@code (patient_mpi, study_uid, status)} 三列表达出来的。
 * 实体多映射一列而迁移里没有这一列时，<b>每一行任务都插不进去</b>：
 * <ul>
 *   <li>单元测试与 H2 集成测试全都绿 —— 因为 Hibernate 的 {@code ddl-auto=create-drop}
 *       是<b>照着实体</b>建表的，缺列的事根本不会发生；</li>
 *   <li>真实 PostgreSQL 上 Flyway 按<b>迁移脚本</b>建表，缺的那列不在表里 ——
 *       INSERT 直接报 {@code column "xxx" does not exist}；</li>
 *   <li>而入队失败会被上层记成一行日志，HIS 侧却已经收到 ACK ——
 *       现象是「调阅永远不发生，但系统看起来一切正常」。</li>
 * </ul>
 * 这正是本仓库此前在 {@code status} 列上踩过的同一个坑
 * （枚举常量名 vs 小写码，见 {@code FetchTaskStatusDbContractTest}）：
 * <b>Hibernate 建的表和 Flyway 建的表不是同一张表</b>，测试环境永远发现不了。
 *
 * <p>本测试把两张表拉到同一张纸上对账：实体映射出的列集合 ⊆ V2 迁移定义的列集合。
 */
@DisplayName("Task 23 — 任务实体列 与 V2 迁移列对账")
class FetchTaskSchemaColumnContractTest {

    /** V2 里 {@code imaging_fetch_tasks} 的建表语句。 */
    private static final String V2_MIGRATION = "db/migration/V2__imaging_fetch_tasks.sql";

    /** 建表体里的列定义行（列名小写开头，后面跟类型）。 */
    private static final Pattern COLUMN_LINE = Pattern.compile("^\\s*([a-z_][a-z0-9_]*)\\s+[A-Z]");

    @Test
    @DisplayName("实体映射的每一列在 V2 迁移里都存在（缺一列 = 生产上每一行任务都插不进去）")
    void everyMappedColumnExistsInMigration() throws IOException {
        Set<String> migrationColumns = readMigrationColumns();
        Set<String> entityColumns = readEntityColumns();

        assertTrue(migrationColumns.containsAll(entityColumns),
                "实体映射了迁移里没有的列：" + difference(entityColumns, migrationColumns)
                        + " —— 真实 PostgreSQL 上 INSERT 会报 column does not exist，"
                        + "入队全线失败而测试环境（Hibernate 自动建表）看不出来");
    }

    @Test
    @DisplayName("幂等键涉及的三列都在迁移里（patient_mpi / study_uid / status）")
    void idempotencyKeyColumnsExistInMigration() throws IOException {
        Set<String> migrationColumns = readMigrationColumns();

        for (String column : new String[] {"patient_mpi", "study_uid", "status"}) {
            assertTrue(migrationColumns.contains(column),
                    "幂等键依赖的列 " + column + " 不在 V2 迁移里：" + migrationColumns);
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 读实体上所有 {@code @Column} / {@code @Id} 映射出来的列名。 */
    private static Set<String> readEntityColumns() {
        Set<String> columns = new LinkedHashSet<>();
        for (Field field : FetchTask.class.getDeclaredFields()) {
            jakarta.persistence.Column column = field.getAnnotation(jakarta.persistence.Column.class);
            if (column != null && !column.name().isBlank()) {
                columns.add(column.name().toLowerCase(java.util.Locale.ROOT));
                continue;
            }
            if (field.getAnnotation(jakarta.persistence.Id.class) != null) {
                columns.add(field.getName().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return columns;
    }

    /** 读 V2 迁移里 {@code CREATE TABLE imaging_fetch_tasks} 的列名清单。 */
    private static Set<String> readMigrationColumns() throws IOException {
        String sql;
        try (InputStream in = FetchTaskSchemaColumnContractTest.class.getClassLoader()
                .getResourceAsStream(V2_MIGRATION)) {
            if (in == null) {
                throw new IllegalStateException("找不到迁移脚本 " + V2_MIGRATION);
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("读取迁移脚本失败", ex);
        }

        int start = sql.indexOf("CREATE TABLE imaging_fetch_tasks");
        assertTrue(start >= 0, "V2 迁移里找不到 CREATE TABLE imaging_fetch_tasks");
        int end = sql.indexOf(");", start);
        assertTrue(end > start, "V2 迁移里建表语句没有正常收尾");
        String body = sql.substring(start, end);

        Set<String> columns = new LinkedHashSet<>();
        for (String line : body.split("\\r?\\n")) {
            Matcher matcher = COLUMN_LINE.matcher(line);
            if (matcher.find()) {
                columns.add(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
            }
        }
        assertTrue(columns.contains("task_id"), "解析 V2 列名失败，解析到：" + columns);
        return columns;
    }

    /** 差集（用于报错文案）。 */
    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> out = new LinkedHashSet<>(left);
        out.removeAll(right);
        return out;
    }
}
