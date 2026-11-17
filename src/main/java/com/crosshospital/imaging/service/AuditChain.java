package com.crosshospital.imaging.service;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.security.AuditChecksum;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 审计链的<b>唯一写入入口</b>（task 24 修复）。
 *
 * <p><b>为什么要有这一层</b>：{@code audit_imaging_fetch} 是一条链，
 * 「本行的 {@code prev_checksum}」必须等于「上一行的 {@code row_checksum}」。
 * 只要存在<b>两条以上</b>的写入路径，各自做「读最新 prev → 盖戳 → INSERT」，
 * 两个并发写就会读到<b>同一个</b> prev，于是链分叉 —— 而分叉在校验器眼里
 * 与「有人篡改了记录」<b>完全同形</b>（{@code PREV_LINK_MISMATCH}）。
 *
 * <p>这不是假想：修复前 {@code AuditService} / {@code PeerRegistry} /
 * {@code WadoRsAuditWriter} 三条写入路径里，只有 {@code AuditService} 有锁，
 * 另外两条裸写。并发压一次就能复现（947 行里分叉 557 点），
 * 而后果是<b>误报</b>：飞检现场看到「审计链断了」，实际没人动过数据 ——
 * 这种假警报一次就能让人不再相信这条链，比丢一行审计更糟。
 *
 * <p><b>锁的粒度</b>：{@code audit_imaging_fetch} 每库一张、每库一条链，
 * 所以互斥量取 <b>JVM 级</b>（全表只有一条链，不存在「按什么分组」的问题）。
 *
 * <p><b>锁的释放时机</b>（关键）：JPA 的 INSERT 是在<b>事务提交</b>时才真正落库、
 * 才对别的连接可见的。如果锁在方法返回时就放掉，另一个事务就可能在本事务
 * 提交之前读到「上一行的 checksum」，照样分叉。因此这里做了两件事：
 * <ol>
 *   <li>没有活跃事务时（{@code WadoRsAuditWriter} 那种自带仓储事务的路径）：
 *       锁在 {@code save()} 返回时释放 —— 此时 INSERT 已提交，已可见。</li>
 *   <li>有活跃事务时（{@code AuditService.record} / {@code PeerRegistry} 的
 *       {@code @Transactional} 方法）：锁<b>推迟到事务结束</b>才释放
 *       （{@link TransactionSynchronization#afterCompletion(int)}），
 *       覆盖「读 prev → INSERT → 提交」整个窗口。</li>
 * </ol>
 *
 * <p><b>还差的一层</b>：以上只保证<b>同一个 JVM 内</b>不分叉。多实例部署时
 * 每个进程各有一把锁，仍可能分叉，需要数据库侧的咨询锁
 * （{@code SELECT pg_advisory_xact_lock(...)}）把互斥范围提到数据库。
 * 该层<b>尚未实现</b>，归 task 35 的真库集成测试一并落地 ——
 * task 25 已把进程内嵌 PostgreSQL 的验证环境搭好（{@code EmbeddedPostgresSupport}），
 * 到时不必再从零搭环境。该层必须挂在<b>全部三条写入路径</b>上
 * （本类 / {@code PeerRegistry} / {@code WadoRsAuditWriter}）：
 * 只给一条路径加咨询锁，另外两条照样能读到过期的 prev，链照样会分叉。
 * 现状与责任归属见 {@code discoveries.md} 与 {@code progress.txt} 的
 * 「未实连范围」一节。
 *
 * <p>源主路径证据：dcm4che-net-audit 应用层审计模块 + 链式校验和模式。
 */
public final class AuditChain {

    /**
     * 审计链写入的 JVM 级互斥锁。
     *
     * <p>用 {@link ReentrantLock} 而非 {@code synchronized}：同一线程在一个事务里
     * 可能盖多行审计（每次 {@link #append} 注册一个释放回调），需要可重入 +
     * 可计数的释放语义。
     */
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private AuditChain() {
    }

    /**
     * 追加一行审计：读最新 {@code prev_checksum} → 盖链式校验和 → INSERT。
     *
     * <p><b>这是 {@code audit_imaging_fetch} 的唯一合法写入路径。</b>
     * 任何绕过它直连 {@code auditRepository.save} 的调用都会把链的不变量
     * 交给调用点自觉维护 —— 那正是分叉的来源。
     *
     * @param repository 审计表 Repository
     * @param row        待写入的行（字段在落库前一次性设置完毕）
     * @return 已持久化的行（含数据库分配的 id）
     * @throws IllegalArgumentException repository 或 row 为 {@code null}
     */
    public static AuditRow append(AuditRepository repository, AuditRow row) {
        if (repository == null) {
            throw new IllegalArgumentException("审计 Repository 不能为空");
        }
        if (row == null) {
            throw new IllegalArgumentException("审计行不能为空");
        }

        ChainLockGuard guard = new ChainLockGuard();
        WRITE_LOCK.lock();
        boolean releaseOnExit = true;
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                // 事务还没提交，INSERT 对别的连接尚不可见 —— 锁必须活到事务结束
                TransactionSynchronizationManager.registerSynchronization(guard);
                releaseOnExit = false;
            }
            byte[] prev = latestChecksum(repository);
            AuditChecksum.stamp(row, prev);
            return repository.save(row);
        } catch (RuntimeException | Error e) {
            // 失败立即放锁；事务结束时的 afterCompletion 会因幂等而成为空操作
            guard.release();
            throw e;
        } finally {
            if (releaseOnExit) {
                guard.release();
            }
        }
    }

    /**
     * 取当前表最后一行的 {@code row_checksum}；空表返回 32 字节全 0。
     */
    private static byte[] latestChecksum(AuditRepository repository) {
        Optional<byte[]> latest = repository.findLatestRowChecksum();
        if (latest == null || latest.isEmpty() || latest.get() == null) {
            return AuditChecksum.emptyChecksum();
        }
        return latest.get();
    }

    /**
     * 锁的释放守卫：保证 <b>恰好释放一次</b>。
     *
     * <p>释放可能来自两条路径 —— 正常返回（无事务）与事务结束
     * （{@code afterCompletion}）—— 必须幂等，否则会二次解锁抛
     * {@code IllegalMonitorStateException}。
     */
    private static final class ChainLockGuard implements TransactionSynchronization {

        private final AtomicBoolean released = new AtomicBoolean(false);

        @Override
        public void afterCompletion(int status) {
            release();
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                WRITE_LOCK.unlock();
            }
        }
    }
}
