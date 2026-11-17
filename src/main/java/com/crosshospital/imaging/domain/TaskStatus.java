package com.crosshospital.imaging.domain;

import java.util.Map;
import java.util.Set;

/**
 * 跨院影像调阅任务五态机（+ failed 终态分支）。
 *
 * <p>状态跃迁图（V2 CHECK 与数据库约束一致）：
 * <pre>
 *   queued ──▶ querying ──▶ pulling ──▶ stored ──▶ notified
 *      │           │           │           │
 *      └───────────┴───────────┴───────────┴──▶ failed
 * </pre>
 *
 * <p>{@code notified} 与 {@code failed} 都是终态（terminal），不允许再回到查询列表/拉取阶段。
 * 数据库 UNIQUE INDEX {@code idx_fetch_tasks_idem} 也在 {@code notified}/{@code failed}
 * 之外做部分唯一约束，防止同一 (patient_mpi, study_uid) 重复入队。
 *
 * <p>源主路径证据：dcm4che-net-audit 应用层审计 + 路由表 + MPI 寻址（融合增量）。
 */
public enum TaskStatus {

    /** 任务已被 MLLP/API 接收，等待调度（DB 唯一约束起始态）。 */
    QUEUED,

    /** C-FIND 正在查询对端 PACS（按 study_uid 拉取研究元数据）。 */
    QUERYING,

    /** C-MOVE/WADO-RS 正在拉回 DICOM 对象到本服务缓存。 */
    PULLING,

    /** DICOM 对象已写入本院 PACS（C-STORE 终态，缓存生效）。 */
    STORED,

    /** HIS webhook 已推送 / AI 草稿已落库，任务对外可见终结。 */
    NOTIFIED,

    /** 失败终态分支（重试耗尽 / 黑白名单命中 / 协议不兼容）。 */
    FAILED;

    /** 是否终态（不允许再跃迁）。 */
    public boolean isTerminal() {
        return this == NOTIFIED || this == FAILED;
    }

    /**
     * 处于「未终结但已入业务流」的幂等块状态：DB UNIQUE INDEX
     * {@code idx_fetch_tasks_idem} 仅在这些状态生效。
     */
    public boolean isIdempotentBlock() {
        return this == QUEUED || this == QUERYING || this == PULLING || this == STORED;
    }

    /**
     * 合法跃迁表。任意不在表内的跃迁将被视为非法，由
     * {@link com.crosshospital.imaging.service.task.TaskStateMachine} 拒绝。
     *
     * <p>允许跃迁：
     * <ul>
     *   <li>QUEUED → QUERYING</li>
     *   <li>QUEUED → FAILED（入队即拒：黑白名单命中 / 字段缺失）</li>
     *   <li>QUERYING → PULLING</li>
     *   <li>QUERYING → FAILED（C-FIND 失败 / association rejected）</li>
     *   <li>PULLING → STORED</li>
     *   <li>PULLING → FAILED（C-MOVE 失败 / sub-op 失败）</li>
     *   <li>STORED → NOTIFIED</li>
     *   <li>STORED → FAILED（C-STORE 失败 / 本院 PACS 拒接）</li>
     * </ul>
     */
    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = Map.of(
                    QUEUED, Set.of(QUERYING, FAILED),
                    QUERYING, Set.of(PULLING, FAILED),
                    PULLING, Set.of(STORED, FAILED),
                    STORED, Set.of(NOTIFIED, FAILED),
                    NOTIFIED, Set.of(),
                    FAILED, Set.of());

    /**
     * 本状态允许的后继状态集合（不可变）。
     *
     * <p>给错误信息与排障输出用：非法跃迁时直接把「合法的后继」报出来，
     * 排障的人一眼能看出是推早了还是推晚了。
     */
    public Set<TaskStatus> allowedNextStates() {
        return ALLOWED.getOrDefault(this, Set.of());
    }

    /**
     * 当前状态是否可合法跃迁到 {@code next}。
     *
     * @param next 期望的下一状态；不能为 {@code null}
     * @return true 当且仅当 {@code next} 出现在 {@link #ALLOWED} 的合法集内
     */
    public boolean canTransitionTo(TaskStatus next) {
        if (next == null) return false;
        return ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }

    /**
     * 字符串 ↔ 枚举的安全反向：从数据库或外部 API 接收的字符串值，
     * 解析失败抛 {@link IllegalArgumentException}，由调用方决定是进 DLQ 还是
     * 触发 fallback。
     */
    public static TaskStatus fromDbValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("TaskStatus string is null");
        }
        return TaskStatus.valueOf(value.toUpperCase());
    }

    /** 序列化为数据库 TEXT 列存的值（小写，与 V2 CHECK 字符串一致）。 */
    public String toDbValue() {
        return name().toLowerCase();
    }
}