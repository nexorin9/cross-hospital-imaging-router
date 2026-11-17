package com.crosshospital.imaging.service.task;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 在途影像暂存 —— C-MOVE 拉回之后、C-STORE 写进本院 PACS 之前的那一小段时间里，
 * 字节流放在这里。
 *
 * <p><b>为什么需要它</b>：跨院调阅是「拉回来」与「写进去」两件事，中间必须有个
 * 落点。放在内存里而不是直接写盘，理由是：
 * <ul>
 *   <li>这批字节的最终归宿就是<b>本院 PACS</b>，落盘只是多一次拷贝；</li>
 *   <li>落盘就意味着在服务器上留一份影像副本 —— 那是 PACS 的职责，不是路由服务的。
 *       路由服务一旦在磁盘上堆影像，就得跟着做加密、脱敏、留存期清理，
 *       一样都做不好。</li>
 * </ul>
 *
 * <p><b>容量闸门</b>：暂存区有总字节上限。超限时<b>拒收新任务</b>而不是让内存被打爆 ——
 * 服务被打爆 = 所有外院节点的调阅全停；拒收只是这一单调阅进死信。
 *
 * <p>本类是<b>在途</b>暂存（PULLING → STORED 之间），不是影像缓存 ——
 * 影像写进本院 PACS 之后的缓存与淘汰是另一个职责（按节点的
 * {@code cache_ttl_hours / max_cache_mb} 管），两者不要混。
 */
@Component
public class InFlightImageBuffer {

    /** 单个任务最多暂存的字节数（默认 512 MB，与单次调阅的常见体量相当）。 */
    public static final long DEFAULT_MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    /** 最多同时暂存的任务数（默认 8，防止并发拉阅把内存吃满）。 */
    public static final int DEFAULT_MAX_TASKS = 8;

    private final Map<UUID, List<ByteBuffer>> entries = new ConcurrentHashMap<>();
    private final AtomicLong totalBytes = new AtomicLong();
    private final long maxTotalBytes;
    private final int maxTasks;

    public InFlightImageBuffer() {
        this(DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_TASKS);
    }

    public InFlightImageBuffer(long maxTotalBytes, int maxTasks) {
        this.maxTotalBytes = maxTotalBytes;
        this.maxTasks = maxTasks;
    }

    /**
     * 暂存一批拉回的字节。
     *
     * @throws IllegalStateException 暂存区已满（总字节或任务数超限）—— 调用方据此判失败
     */
    public void put(UUID taskId, List<ByteBuffer> instances) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不能为 null");
        }
        if (instances == null || instances.isEmpty()) {
            throw new IllegalArgumentException("拉回的影像为空，不能暂存");
        }
        long size = 0;
        for (ByteBuffer buffer : instances) {
            size += buffer == null ? 0 : buffer.remaining();
        }
        synchronized (this) {
            if (!entries.containsKey(taskId) && entries.size() >= maxTasks) {
                throw new IllegalStateException("在途影像暂存区任务数已满（上限 " + maxTasks
                        + "），本次调阅不予受理，请稍后重试");
            }
            if (totalBytes.get() + size > maxTotalBytes) {
                throw new IllegalStateException("在途影像暂存区容量已满（上限 "
                        + maxTotalBytes + " 字节），本次调阅不予受理，请稍后重试");
            }
            entries.put(taskId, new ArrayList<>(instances));
            totalBytes.addAndGet(size);
        }
    }

    /**
     * 取出并移除某任务暂存的字节（取走即释放 —— 写进 PACS 后不该再占着内存）。
     *
     * @return 字节列表；没有暂存时返回空列表
     */
    public List<ByteBuffer> take(UUID taskId) {
        if (taskId == null) {
            return List.of();
        }
        synchronized (this) {
            List<ByteBuffer> found = entries.remove(taskId);
            if (found == null) {
                return List.of();
            }
            long size = 0;
            for (ByteBuffer buffer : found) {
                size += buffer == null ? 0 : buffer.remaining();
            }
            totalBytes.addAndGet(-size);
            return found;
        }
    }

    /** 丢弃某任务的暂存（任务失败时清理，避免字节长期占着内存）。 */
    public void discard(UUID taskId) {
        take(taskId);
    }

    /** 当前暂存的任务数。 */
    public int taskCount() {
        return entries.size();
    }

    /** 当前暂存的总字节数。 */
    public long usedBytes() {
        return Math.max(0, totalBytes.get());
    }

    /** 暂存区容量上限（字节）。 */
    public long maxTotalBytes() {
        return maxTotalBytes;
    }
}
