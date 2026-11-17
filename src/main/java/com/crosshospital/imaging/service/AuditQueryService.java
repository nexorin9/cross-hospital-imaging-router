package com.crosshospital.imaging.service;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.repository.AuditRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 审计查询服务（task 25）。
 *
 * <p><b>它解决的是现场的一个具体问题</b>：飞检或内审要看「某段时间、某个外院节点、
 * 某种终态」的调阅记录时，运维人员过去只能直接连库跑 SQL —— 口径写在各个人的
 * 笔记本里，同一份材料两个人跑出来的行数对不上，评审会上就要吵一轮。
 * 这里把口径固化成代码：一组条件 → 一条 SQL → 分页结果。
 *
 * <p><b>查询口径</b>：
 * <ul>
 *   <li>时间窗左闭右开 {@code [fromTs, toTs)}，避免相邻窗口重复统计同一批事件；</li>
 *   <li>AET / 终态 / 操作人条件为空即不参与过滤（SQL 侧用 {@code :p is null or ...} 兜住）；</li>
 *   <li>默认排序 {@code when_ts desc, id desc} —— 最近的排前面，同时保证同一时刻的行有稳定次序。</li>
 * </ul>
 *
 * <p><b>两个行数上限</b>（两者用途不同，不要混用）：
 * <ul>
 *   <li><b>分页</b>：{@link #query(AuditQueryCriteria, int, int)} 单页最多
 *       {@value #MAX_PAGE_SIZE} 行。审计表只增不减，一次拉几万行会把飞检现场的
 *       数据库拖垮，也会让调用方的浏览器卡死。</li>
 *   <li><b>整取</b>：{@link #queryAll(AuditQueryCriteria, int)} 与三个单维度取行方法
 *       最多返回 {@value #MAX_EXPORT_ROWS} 行 —— 这是「导出飞检材料」那类
 *       <b>一次性取走</b>的场景的护栏，不是分页护栏。</li>
 * </ul>
 *
 * <p><b>整取路径不能被分页上限截断</b>（task 25 核查修掉）：整取方法曾经复用分页方法，
 * 于是单页上限（{@value #MAX_PAGE_SIZE}）把导出也一起夹住了 ——
 * 调用方要 600 行拿到 500 行，<b>不报错、不告警</b>。飞检现场交上去的材料因此就是缺的，
 * 而材料缺几行没人会知道。这正是「静默截断」最坏的一种：它看起来是成功的。
 *
 * <p><b>只读</b>：本服务不提供任何写入方法。审计表的写入只有一条路径
 * （{@link AuditService#record} → {@code AuditChain.append}），
 * 查询侧再开一条写入路径就等于把 append-only 的不变量交给自觉维护。
 *
 * <p>源主路径证据：dcm4che-net-audit 应用层审计模块的「查询/导出」侧语义。
 */
@Service
@Profile("!test")
public class AuditQueryService {

    /** <b>分页</b>查询的单页最大行数。 */
    public static final int MAX_PAGE_SIZE = 500;

    /** <b>整取 / 导出</b>路径的单次最大行数（防止一次把整张审计表拉进内存）。 */
    public static final int MAX_EXPORT_ROWS = 50_000;

    /** 缺省时间窗下界：审计表最早可能的时间（TIMESTAMPTZ 下界，实际不会有更早的行）。 */
    private static final Instant EPOCH = Instant.parse("1970-01-01T00:00:00Z");

    /** 缺省时间窗上界：远期未来，避免用 {@code now} 造成同一次查询两次调用结果不一致。 */
    private static final Instant FAR_FUTURE = Instant.parse("2999-12-31T23:59:59Z");

    private final AuditRepository auditRepository;

    public AuditQueryService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /**
     * 多条件组合分页查询。
     *
     * @param criteria 查询条件（{@code null} 字段表示不筛选）
     * @param page     页码，从 0 开始
     * @param size     每页行数，最大 {@value #MAX_PAGE_SIZE}
     * @return 分页结果（按时间倒序）
     */
    public Page<AuditRow> query(AuditQueryCriteria criteria, int page, int size) {
        return queryPaged(criteria, page, clampPageSize(size));
    }

    /**
     * 内部执行：页大小由调用方给定（<b>调用方自己负责夹上限</b>）。
     *
     * <p>分页路径与整取路径共用这一个实现 —— 两者的差别只在「页大小夹到多少」：
     * 分页夹 {@value #MAX_PAGE_SIZE}，整取夹 {@value #MAX_EXPORT_ROWS}。
     * 条件口径、排序口径、时间窗默认值全在这里，只有一份，不会分叉。
     */
    private Page<AuditRow> queryPaged(AuditQueryCriteria criteria, int page, int size) {
        AuditQueryCriteria effective = (criteria == null
                ? AuditQueryCriteria.ofTimeWindow(EPOCH, FAR_FUTURE)
                : criteria).validate();

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                size,
                Sort.by(Sort.Order.desc("whenTs"), Sort.Order.desc("id")));

        return auditRepository.queryByTimeWindow(
                effective.fromTs() == null ? EPOCH : effective.fromTs(),
                effective.toTs() == null ? FAR_FUTURE : effective.toTs(),
                effective.fromAet(),
                effective.toAet(),
                effective.statusTerminal(),
                effective.who(),
                pageable);
    }

    /**
     * 多条件组合查询（不分页，取前 {@code limit} 行）。
     *
     * <p>给「导出飞检材料」用：一页一页翻页在导出场景里没有意义。
     *
     * <p>上限是 {@value #MAX_EXPORT_ROWS}（不是分页的 {@value #MAX_PAGE_SIZE}）。
     * {@code limit=0} 表示「一行也不要」，返回空列表 —— 不是 1 行。
     *
     * @param criteria 查询条件
     * @param limit    最多返回多少行
     */
    public List<AuditRow> queryAll(AuditQueryCriteria criteria, int limit) {
        int bounded = clampExportLimit(limit);
        if (bounded == 0) {
            return List.of();
        }
        return queryPaged(criteria, 0, bounded).getContent();
    }

    /**
     * 时间窗 + 多条件组合查询（一次返回全部匹配行，不分页）。
     *
     * <p>对应 {@code AuditService.queryByTimeWindow} 的语义。
     */
    public List<AuditRow> queryByTimeWindow(Instant fromTs, Instant toTs,
                                            String fromAet, String toAet,
                                            String statusTerminal) {
        AuditQueryCriteria criteria = new AuditQueryCriteria(
                fromTs, toTs, fromAet, toAet, statusTerminal, null);
        return queryAll(criteria, MAX_EXPORT_ROWS);
    }

    /** 按源 AET 取行（我方发起的那一侧）。 */
    public List<AuditRow> findByFromAet(String fromAet, int limit) {
        int bounded = clampExportLimit(limit);
        if (bounded == 0) {
            return List.of();
        }
        return auditRepository.findByFromAet(fromAet, PageRequest.of(0, bounded));
    }

    /** 按目标 AET 取行（外院节点 / 本院 PACS 那一侧）。 */
    public List<AuditRow> findByToAet(String toAet, int limit) {
        int bounded = clampExportLimit(limit);
        if (bounded == 0) {
            return List.of();
        }
        return auditRepository.findByToAet(toAet, PageRequest.of(0, bounded));
    }

    /** 按终态标签取行。 */
    public List<AuditRow> findByStatusTerminal(String statusTerminal, int limit) {
        int bounded = clampExportLimit(limit);
        if (bounded == 0) {
            return List.of();
        }
        return auditRepository.findByStatusTerminal(
                statusTerminal, PageRequest.of(0, bounded));
    }

    /** 时间窗内的总行数（做「这段时间一共发生了多少次调阅」这类口径核对）。 */
    public long countByTimeWindow(Instant fromTs, Instant toTs) {
        return auditRepository.countByTimeWindow(
                fromTs == null ? EPOCH : fromTs,
                toTs == null ? FAR_FUTURE : toTs);
    }

    /** 链尾行（最新写入的一行）。 */
    public Optional<AuditRow> findLatest() {
        return auditRepository.findLatest();
    }

    private int clampPageSize(int size) {
        if (size <= 0) {
            return 1;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 整取 / 导出行数上限：{@code limit=0} → 0（返回空列表）；负数按 0 算；
     * 超过 {@value #MAX_EXPORT_ROWS} 夹到该值。
     *
     * <p>与 {@link #clampPageSize(int)} 分开是刻意的：分页上限 500 是给
     * 「翻页浏览」用的护栏，导出行数上限 50000 是给「一次性取走」用的护栏。
     * 早先整取路径复用了分页路径，500 这个护栏把导出也夹住了 ——
     * 调用方要多少行就得真的拿到多少行，否则「导出成功」是假的。
     */
    private int clampExportLimit(int limit) {
        return Math.min(Math.max(limit, 0), MAX_EXPORT_ROWS);
    }
}
