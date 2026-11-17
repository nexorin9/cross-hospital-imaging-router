package com.crosshospital.imaging.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * 内嵌 PostgreSQL 支撑（task 25）。
 *
 * <p><b>为什么需要它</b>：审计表的 append-only 是靠 PostgreSQL 触发器
 * {@code audit_no_update} 兜底的 —— 那是本项目里唯一一处「不靠应用代码、
 * 靠数据库本身拒绝修改」的防线。用内存表（H2 / Mockito 替身）验证不了这条防线：
 * <ul>
 *   <li>H2 与 PostgreSQL 方言不兼容，跑不了 {@code plpgsql} 触发器；</li>
 *   <li>替身表里「拒绝修改」只是我们自己在内存里写的一个 if，
 *       证明不了生产库上那条 {@code RAISE EXCEPTION} 真的会触发。</li>
 * </ul>
 * 所以这里起一个<b>真 PostgreSQL</b>（进程内、无需安装、无需 Docker），
 * 让 V1–V8 迁移脚本原样跑一遍，再验证触发器行为。
 *
 * <p><b>不是产品依赖</b>：本类只存在于 test scope，生产包不含内嵌数据库；
 * 生产环境连的是院内 PostgreSQL（{@code DATABASE_URL} 环境变量位）。
 *
 * <p><b>平台限制</b>：embedded-postgres 自带各平台二进制。ARM 平台需要额外的
 * 平台包（见 {@code pom.xml} 的 {@code embedded-postgres-*-arm64} profile）。
 * 若当前平台拿不到二进制，{@link #isAvailable()} 返回 {@code false}，
 * 依赖它的测试用 {@code @EnabledIf} 整类跳过 —— <b>跳过而不是假装通过</b>。
 */
public final class EmbeddedPostgresSupport {

    private static final EmbeddedPostgres INSTANCE;
    private static final Throwable FAILURE;

    static {
        EmbeddedPostgres started = null;
        Throwable failure = null;
        try {
            started = EmbeddedPostgres.builder()
                    .setPGStartupWait(java.time.Duration.ofSeconds(60))
                    .start();
        } catch (Throwable t) {
            failure = t;
        }
        INSTANCE = started;
        FAILURE = failure;
    }

    private EmbeddedPostgresSupport() {
    }

    /** 内嵌 PostgreSQL 是否可用（当前平台能拿到二进制且启动成功）。 */
    public static boolean isAvailable() {
        return INSTANCE != null;
    }

    /** 启动失败原因（{@link #isAvailable()} 为 false 时用于报告）。 */
    public static String failureMessage() {
        if (FAILURE == null) {
            return "";
        }
        Throwable root = FAILURE;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    /** JDBC 连接串（库名 {@code postgres}，用户 {@code postgres}）。 */
    public static String jdbcUrl() {
        requireAvailable();
        return INSTANCE.getJdbcUrl("postgres", "postgres");
    }

    public static String username() {
        return "postgres";
    }

    /** 密码位：内嵌实例以 {@code trust} 认证启动，这里填占位值即可连上。 */
    public static String password() {
        return "postgres";
    }

    /** 供 {@code @AfterAll} 关闭实例。 */
    public static void close() {
        if (INSTANCE != null) {
            try {
                INSTANCE.close();
            } catch (Exception ignored) {
                // 关闭失败不影响测试结论（进程退出时一并回收）
            }
        }
    }

    private static void requireAvailable() {
        if (INSTANCE == null) {
            throw new IllegalStateException("内嵌 PostgreSQL 不可用：" + failureMessage());
        }
    }
}
