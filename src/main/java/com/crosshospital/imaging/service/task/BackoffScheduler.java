package com.crosshospital.imaging.service.task;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 退避调度器 —— 「隔这么久再执行」这件事的抽象。
 *
 * <p><b>为什么不是一个线程 sleep</b>：退避最长 30 秒。一批 20 个外院节点同时拒接，
 * 用 {@code Thread.sleep(30000)} 就要占住 20 条线程干等 —— 线程池瞬间被退避任务吃光，
 * 真正该跑的调阅反而排不上。用调度器的<b>延迟</b>语义，
 * 线程只在真正到点的那一刻被唤醒一次，等待期间不占任何线程。
 *
 * <p>抽成接口是为了让单测能钉死「退避多久」这个数，而不用真等。
 */
public interface BackoffScheduler {

    /**
     * 在 {@code delayMs} 毫秒之后执行 {@code task}。
     *
     * <p>实现不得抛异常给调用方（调度失败要记日志，不该把调阅流程炸掉）。
     *
     * @param task    到点要执行的动作
     * @param delayMs 延迟毫秒数（非正数 = 立即执行）
     */
    void schedule(Runnable task, long delayMs);

    /**
     * 默认实现：单线程调度器，把退避变成「延迟唤醒」而不是「占着线程等」。
     */
    @Component
    @Profile("!test")
    class ScheduledExecutorBackoffScheduler implements BackoffScheduler {

        private static final Logger LOG =
                LoggerFactory.getLogger(ScheduledExecutorBackoffScheduler.class);

        private final ScheduledExecutorService executor;

        public ScheduledExecutorBackoffScheduler() {
            this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "fetch-retry-backoff");
                    t.setDaemon(true);
                    return t;
                }
            });
        }

        @Override
        public void schedule(Runnable task, long delayMs) {
            if (task == null) {
                return;
            }
            try {
                executor.schedule(task, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
            } catch (RuntimeException e) {
                LOG.error("退避任务入队失败: {}", e.getMessage());
            }
        }

        /** 停机时收掉调度线程（Spring 容器关闭时调用）。 */
        @PreDestroy
        public void shutdown() {
            executor.shutdownNow();
            LOG.info("退避调度器已停止");
        }
    }
}
