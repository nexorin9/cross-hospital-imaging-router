package com.crosshospital.imaging.service;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.security.AuditChecksum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;


/**
 * 审计服务（task 9 骨架；task 24–25 完整化）。
 *
 * <p>当前 task 暴露 {@code record} 与 {@code recordHandshake} 两个方法，
 * 供 {@code ImagingConnectionMonitor} 五态回调、{@code PeerRegistry} 准入审计、
 * {@code WadoRsAuditWriter} 失败审计与各路由失败处理器使用；
 * task 24 起 {@code row_checksum} 已由 {@code byte[32]} 占位替换为
 * SHA-256 链式校验和（{@link AuditChecksum}）；task 25 将补多条件分页查询与
 * {@code AuditQueryService}。
 *
 * <p><b>append-only 写入约定</b>：
 * <ul>
 *   <li>每行 row_checksum = SHA-256(prev_checksum + 字段拼接) —— task 24</li>
 *   <li>空表 prev_checksum = 32 字节 0</li>
 *   <li>PostgreSQL 触发器 {@code audit_no_update} 拦截任何 UPDATE/DELETE/TRUNCATE</li>
 *   <li>实体层 {@code @Immutable} 注解 + 编译期不暴露 setter 双重防御</li>
 * </ul>
 *
 * <p>源主路径证据：dcm4che-net-audit 应用层审计模块 + 区块链式校验和模式。
 */
@Service
@Profile("!test")
public class AuditService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository auditRepository;

    /** 查询侧入口（task 25）：多条件组合 + 分页口径集中在这里。 */
    private AuditQueryService auditQueryService;

    /**
     * 单参构造：给单元测试用（测试里用内存版审计表，不需要 Spring 容器）。
     *
     * <p>生产环境里 {@link AuditQueryService} 会由容器注入（见
     * {@link #setAuditQueryService}）；容器里没有这个 bean 时（例如手工注册
     * {@code AuditService} 的装配测试），退回一个自建实例 —— 查询口径只有一份实现，
     * 不存在「注入的能查、自建的查不出来」这种分叉。
     */
    public AuditService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
        this.auditQueryService = new AuditQueryService(auditRepository);
    }

    /**
     * 注入容器里的查询服务（可选）。
     *
     * <p>用 setter 而不是构造器：{@code AuditService} 的构造器只依赖
     * {@link AuditRepository}，手工装配它的测试不必再多注册一个 bean。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuditQueryService(AuditQueryService auditQueryService) {
        if (auditQueryService != null) {
            this.auditQueryService = auditQueryService;
        }
    }

    /**
     * append-only 写入审计行（task 24 起为链式写入的唯一入口）。
     *
     * <p>写入流程：
     * <ol>
     *   <li>取当前表最后一行的 {@code row_checksum} 作为本行的 {@code prev_checksum}
     *       （空表则用 32 字节全 0）；</li>
     *   <li>{@link AuditChecksum#stamp(AuditRow, byte[])} 补齐时间戳并计算本行
     *       {@code row_checksum = SHA-256(prev_checksum + 字段拼接)}；</li>
     *   <li>INSERT（{@code AuditRepository.save} 只暴露 INSERT 语义，
     *       数据库触发器 {@code audit_no_update} 拒任何 UPDATE/DELETE）。</li>
     * </ol>
     *
     * <p><b>并发说明</b>：读 prev → 算 row → 落库这三步由
     * {@link AuditChain#append} 串成不可分割的一步，并且锁活到本事务提交
     * （JPA 的 INSERT 提交前对别的连接不可见，锁提前放开照样会分叉）。
     * 本类与 {@code PeerRegistry} / {@code WadoRsAuditWriter} 三条写入路径
     * 共用同一把锁 —— 此前只有本类加锁，另外两条裸写，实测能把链分叉，
     * 表现为「审计链断裂」的<b>误报</b>。多实例部署仍需数据库侧咨询锁
     * （task 25 的事务边界收口 + task 35 的真库验证）。
     *
     * @param row 待写入的 {@code AuditRow}（id 字段为空，依赖数据库自增）
     * @return 已持久化的审计行（含数据库分配的 id）
     */
    @Transactional
    public AuditRow record(AuditRow row) {
        if (row == null) {
            throw new IllegalArgumentException("审计行不能为空");
        }
        AuditRow saved = AuditChain.append(auditRepository, row);
        LOG.debug("audit recorded id={} who={} status_terminal={} failure_code={}",
                saved.getId(), saved.getWho(),
                saved.getStatusTerminal(), saved.getFailureCode());
        return saved;
    }

    /**
     * {@code ConnectionMonitor} 五态回调的快捷写入接口。
     *
     * <p>{@code statusTerminal} 形如：
     * <ul>
     *   <li>{@code handshake_established} —— onConnectionEstablished</li>
     *   <li>{@code handshake_accepted} —— onConnectionAccepted</li>
     *   <li>{@code handshake_failed} —— onConnectionFailed（网络瞬态，含 e.class）</li>
     *   <li>{@code handshake_rejected} —— onConnectionRejected（协议层拒绝）</li>
     *   <li>{@code handshake_blacklisted_rejected} —— onConnectionRejectedBlacklisted</li>
     * </ul>
     *
     * @param peerAet        远端 AE Title（to_aet）
     * @param statusTerminal 终态枚举字符串
     * @param who            操作员工号（{@code system} = 系统级 / 握手健康检查）
     * @param failureCode    失败码（{@code null} 表示成功）
     * @return 已持久化的审计行
     */
    @Transactional
    public AuditRow recordHandshake(String peerAet, String statusTerminal,
                                     String who, String failureCode) {
        AuditRow row = new AuditRow()
                .withWho(who == null || who.isBlank() ? "system" : who)
                .withFromAet("local")
                .withToAet(peerAet)
                .withRetryCount(0)
                .withStatusTerminal(statusTerminal)
                .withFailureCode(failureCode);
        return record(row);
    }

    /**
     * 时间窗 + 多条件组合查询（task 25）。
     *
     * <p>审计表的<b>读</b>侧入口。写只有 {@link #record} 一条路径，读可以有多种口径 ——
     * 飞检要「这一周所有调阅」、科室自查要「我们院发出去过哪些」、
     * 排障要「某个外院最近失败的」，都从这里进。
     *
     * @param fromTs        起始时间（含）；{@code null} 表示不限
     * @param toTs          结束时间（不含）；{@code null} 表示不限
     * @param fromAet       源 AET 过滤；{@code null} 表示不限
     * @param toAet         目标 AET 过滤；{@code null} 表示不限
     * @param statusTerminal 终态标签过滤；{@code null} 表示不限
     * @return 匹配行（按时间倒序）；本方法是<b>整取</b>路径，一次最多
     *         {@link AuditQueryService#MAX_EXPORT_ROWS} 行 —— 需要翻页浏览请走
     *         {@code AuditQueryService.query(...)}（单页 ≤ {@link AuditQueryService#MAX_PAGE_SIZE} 行）
     */
    public List<AuditRow> queryByTimeWindow(Instant fromTs,
                                            Instant toTs,
                                            String fromAet,
                                            String toAet,
                                            String statusTerminal) {
        return auditQueryService.queryByTimeWindow(fromTs, toTs, fromAet, toAet, statusTerminal);
    }
}
