package com.crosshospital.imaging.config;

import com.crosshospital.imaging.repository.DlqRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.service.metrics.ImagingMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Supplier;

/**
 * task 37 指标装配。
 *
 * <p><b>不自己造 MeterRegistry</b>：Spring Boot 已经按 classpath 自动装配了
 * {@code CompositeMeterRegistry}（含 {@code PrometheusMeterRegistry}），
 * 我们再注册一个 {@code @Bean MeterRegistry} 会把 {@code /actuator/prometheus}
 * 依赖的那个 registry 顶掉。这里只把业务 meter 注册到容器给的那一个上。
 *
 * <p>两个 gauge 走 {@link ObjectProvider} 惰性取仓储：test profile 排除了 JPA，
 * 仓储 bean 不存在时返回 0 而不是让整个上下文起不来；生产 profile 下每次
 * Prometheus 抓取都会读一次真实库。gauge 的读数失败（库抖动）也降级为 0，
 * 不能因为看板取数失败把业务线程带崩。
 */
@Configuration
public class MetricsConfig {

    @Bean
    public ImagingMetrics imagingMetrics(MeterRegistry registry,
                                         ObjectProvider<PeerRepository> peerRepository,
                                         ObjectProvider<DlqRepository> dlqRepository) {
        Supplier<Number> enabledPeers = () -> {
            PeerRepository repo = peerRepository.getIfAvailable();
            if (repo == null) {
                return 0;
            }
            try {
                return repo.findAllByEnabledTrue().size();
            } catch (RuntimeException ex) {
                return 0;
            }
        };
        Supplier<Number> unresolvedDlq = () -> {
            DlqRepository repo = dlqRepository.getIfAvailable();
            if (repo == null) {
                return 0;
            }
            try {
                return repo.countByResolvedAtIsNull();
            } catch (RuntimeException ex) {
                return 0;
            }
        };
        return new ImagingMetrics(registry, enabledPeers, unresolvedDlq);
    }
}
