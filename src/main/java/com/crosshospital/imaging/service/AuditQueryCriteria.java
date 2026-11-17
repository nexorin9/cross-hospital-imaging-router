package com.crosshospital.imaging.service;

import com.crosshospital.imaging.exception.ValidationException;

import java.time.Instant;

/**
 * 审计查询条件（task 25）。
 *
 * <p><b>为什么是「全可空」的条件对象</b>：审计查询在现场有三种典型用法，
 * 条件个数都不一样 ——
 * <ol>
 *   <li>飞检要材料：「这一周所有调阅事件」→ 只有时间窗；</li>
 *   <li>科室自查：「我们院作为发起方发出去过哪些」→ 时间窗 + 源 AET；</li>
 *   <li>排障：「某个外院节点最近失败的都是什么」→ 时间窗 + 目标 AET + 终态。</li>
 * </ol>
 * 写成「全可空 + 一个条件对象」比写 8 个重载方法好维护：
 * 组合查询的 SQL 只有一条，条件为空即不参与过滤。
 *
 * <p><b>时间窗语义</b>：左闭右开 {@code [fromTs, toTs)}。审计行的时间戳精度是微秒，
 * 用开区间可以避免「同一批事件被两个相邻窗口重复统计」。
 *
 * <p>记录不可变（record），所有 {@code withXxx} 返回新实例，避免查询条件在
 * 传给下层之后被悄悄改掉 —— 查询条件一旦被改，返回的行数与调用方预期就对不上了。
 */
public record AuditQueryCriteria(
        Instant fromTs,
        Instant toTs,
        String fromAet,
        String toAet,
        String statusTerminal,
        String who) {

    /**
     * 只按时间窗查询（飞检材料最常用的一种）。
     */
    public static AuditQueryCriteria ofTimeWindow(Instant fromTs, Instant toTs) {
        return new AuditQueryCriteria(fromTs, toTs, null, null, null, null);
    }

    public AuditQueryCriteria withFromAet(String value) {
        return new AuditQueryCriteria(fromTs, toTs, value, toAet, statusTerminal, who);
    }

    public AuditQueryCriteria withToAet(String value) {
        return new AuditQueryCriteria(fromTs, toTs, fromAet, value, statusTerminal, who);
    }

    public AuditQueryCriteria withStatusTerminal(String value) {
        return new AuditQueryCriteria(fromTs, toTs, fromAet, toAet, value, who);
    }

    public AuditQueryCriteria withWho(String value) {
        return new AuditQueryCriteria(fromTs, toTs, fromAet, toAet, statusTerminal, value);
    }

    /**
     * 条件自检：时间窗必须非负长度；AET / 状态标签非空（传空串等同不筛选）。
     *
     * @throws ValidationException 条件非法
     */
    public AuditQueryCriteria validate() {
        if (fromTs != null && toTs != null && !fromTs.isBefore(toTs)) {
            throw new ValidationException("查询起始时间必须早于结束时间（fromTs < toTs）");
        }
        if (isBlank(fromAet) || isBlank(toAet) || isBlank(statusTerminal) || isBlank(who)) {
            throw new ValidationException("审计查询条件不能是空字符串（不筛选请传 null）");
        }
        return this;
    }

    /** 是否带了至少一个非时间条件。 */
    public boolean hasNonTimeFilter() {
        return notBlank(fromAet) || notBlank(toAet) || notBlank(statusTerminal) || notBlank(who);
    }

    private static boolean isBlank(String value) {
        return value != null && value.isBlank();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
