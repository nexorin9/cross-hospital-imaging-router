package com.crosshospital.imaging.service.hl7;

import java.nio.charset.Charset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 触发通道的<b>入门日志</b>路由器：task 18/19 的业务路由器装配前的默认实现。
 *
 * <p>本轮（task 17）只交付 MLLP 线路层：分帧、容量闸门、ACK/NAK、优雅停机。线路层收到一帧后
 * 必须把它交给某个 {@link Hl7TriggerRouter}，否则 ACK 没有业务含义（「收到了但没入队」）。
 * 因此这里提供默认实现，使服务在业务路由器上线前也能独立启动并被验收：
 *
 * <ul>
 *   <li>把帧写入 {@link #recentFrames()} 的有界内存环（默认 200 条）——信息科可核对「HIS 到底发没发、发了几条」；</li>
 *   <li>逐条打 INFO 日志（源产品同款取段方式：扫到第一个 {@code \r} 得 MSH 段头）；</li>
 *   <li><b>不</b>伪装成已入队：返回即成功代表「线路层已收妥」，业务语义由后续路由器承担，
 *       真正落 {@code imaging_fetch_tasks} 是 task 19 的职责。</li>
 * </ul>
 *
 * <p>装配顺序：{@code MllpServerConfig} 以 {@code @ConditionalOnMissingBean(Hl7TriggerRouter.class)}
 * 提供本实现；task 19 提供 {@code Hl7MessageRouter} 后自动顶替本类，无需改线路层。
 */
public class Hl7IngressLogRouter implements Hl7TriggerRouter {

    private static final Logger LOG = LoggerFactory.getLogger(Hl7IngressLogRouter.class);

    /** 入门日志默认保留条数；内存有界，防常驻进程被长连打爆。 */
    public static final int DEFAULT_CAPACITY = 200;

    private final Deque<MllpFrame> recent;
    private final int capacity;
    private final AtomicLong routed = new AtomicLong();

    public Hl7IngressLogRouter() {
        this(DEFAULT_CAPACITY);
    }

    public Hl7IngressLogRouter(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("入门日志容量必须为正数，收到 " + capacity);
        }
        this.capacity = capacity;
        this.recent = new ConcurrentLinkedDeque<>();
    }

    @Override
    public void route(MllpFrame frame) {
        long seq = routed.incrementAndGet();
        recent.addLast(frame);
        while (recent.size() > capacity) {
            recent.pollFirst();
        }
        LOG.info("[HL7 触发入门 #{}] {}", seq, frame);
    }

    /** 已收帧总数（进程级）。 */
    public long routedCount() {
        return routed.get();
    }

    /** 最近若干帧（时间正序）；供运维排查与后续 API 化。 */
    public List<MllpFrame> recentFrames() {
        return new ArrayList<>(recent);
    }

    /** 清空入门日志（测试与运维复位用）。 */
    public void clear() {
        recent.clear();
    }

    /** 便于日志里显示的可读摘要（解码 charset 由调用方传入）。 */
    public static String describe(MllpFrame frame, Charset charset, Instant now) {
        return frame.summary(charset);
    }
}