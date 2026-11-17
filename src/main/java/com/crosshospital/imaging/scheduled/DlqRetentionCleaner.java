package com.crosshospital.imaging.scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.crosshospital.imaging.config.DlqConfigProps;
import com.crosshospital.imaging.domain.DlqEntry;
import com.crosshospital.imaging.repository.DlqRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 死信保留期清理（每天一次）。
 *
 * <p><b>只清「已处置」的死信</b>：{@code resolved_at < now() - retention_days}（默认 30 天）。
 * 未处置的死信<b>永不</b>自动清理 —— 那等于把「还没人管的失败」静默抹掉，
 * 是排障链路上最不能接受的一种丢数据。宁可让表大一点，也不能让失败悄悄消失。
 *
 * <p>清理动作本身也留一行日志（删了多少行、阈值是什么），运维要能回答
 * 「上周五那条死信还在不在」—— 答案是「还在，但已过保留期被清了，清理日志在这里」。
 *
 * <p>源主路径证据：通用分布式系统 DLQ 模式中的 TTL 清理环节。
 */
@Component
@Profile("!test")
public class DlqRetentionCleaner {

    private static final Logger LOG = LoggerFactory.getLogger(DlqRetentionCleaner.class);

    private final DlqRepository repository;
    private final DlqConfigProps props;

    public DlqRetentionCleaner(DlqRepository repository, DlqConfigProps props) {
        this.repository = repository;
        this.props = props;
    }

    /**
     * 每日清理（默认凌晨 03:30，避开业务高峰与数据库备份窗口）。
     */
    @Scheduled(cron = "${dlq.retention-cleanup-cron:0 30 3 * * *}")
    public void cleanup() {
        int removed = cleanupExpired();
        if (removed > 0) {
            LOG.info("死信保留期清理完成：删除 {} 条已处置且超过保留期的死信（保留期 {} 天）",
                    removed, props.getRetentionDays());
        }
    }

    /**
     * 执行清理。
     *
     * @return 删除的行数（0 = 本次没有可清理的行）
     */
    public int cleanupExpired() {
        int retentionDays = props.getRetentionDays();
        if (retentionDays <= 0) {
            LOG.debug("dlq.retention-days={}（≤0 表示不自动清理），跳过死信清理", retentionDays);
            return 0;
        }
        Instant threshold = Instant.now().minus(Duration.ofDays(retentionDays));
        List<DlqEntry> expired = repository.findResolvedBefore(threshold);
        if (expired == null || expired.isEmpty()) {
            return 0;
        }
        repository.deleteAll(expired);
        LOG.info("死信保留期清理：删除 {} 条（resolved_at < {}，保留期 {} 天）",
                expired.size(), threshold, retentionDays);
        return expired.size();
    }
}
