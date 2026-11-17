package com.crosshospital.imaging.service.model;

import com.crosshospital.imaging.domain.AiSummary;
import com.crosshospital.imaging.repository.AiSummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * AI 介入点结果落库（task 27）。
 *
 * <p>把一次 {@link AiResult} 写进 {@code ai_summaries}，并返回落库后的主键 ——
 * 调阅任务行上的 {@code ai_summary_id} 指的就是它。
 *
 * <p><b>{@code source} 是这一行的关键字段</b>：{@code model} = 模型写的，
 * {@code rule_fallback} = 模型不可达时规则模板凑的。医生在 PACS 侧看到一份归一结果时，
 * 就能分清它是「模型判的」还是「规则兜的底」—— 这也是 V4 迁移用 CHECK 约束
 * 把这两个值钉死的原因：不允许出现第三种说不清来源的状态。
 *
 * <p><b>本类不吞异常</b>：落库失败要让调用方知道（调用方决定是否阻断），
 * 但调用方（{@code AiEnrichmentSupport}）会把失败降级为「本次 AI 介入没有产出」，
 * <b>不阻断</b>跨院调阅主路径 —— 这一条由 {@link Propagation#REQUIRES_NEW} 兑现：
 * 摘要写入跑在自己的事务里，失败回滚自己，绝不把主路径的事务带崩。
 */
@Service
@Profile("!test")
public class AiSummaryService {

    private static final Logger LOG = LoggerFactory.getLogger(AiSummaryService.class);

    private final AiSummaryRepository repository;

    public AiSummaryService(AiSummaryRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("AiSummaryRepository 不能为 null");
        }
        this.repository = repository;
    }

    /**
     * 落一行 AI 介入点结果。
     *
     * <p><b>为什么用 {@link Propagation#REQUIRES_NEW}（独立事务）</b>：
     * 本方法是在调阅任务推进事务（{@code FetchTaskService.advance}）里被调用的，
     * 那是<b>主路径</b>的事务。若摘要落库失败还留在同一个事务里，失败会同时砸两样东西：
     * <ul>
     *   <li><b>Spring 侧</b>：{@code persist} 抛出的异常会把参与中的事务标成 rollback-only，
     *       外层提交时抛 {@code UnexpectedRollbackException}；</li>
     *   <li><b>PostgreSQL 侧</b>：一条失败的 INSERT 会把整个事务置为 aborted，
     *       之后外层的每一条语句都报 {@code 25P02 current transaction is aborted}。</li>
     * </ul>
     * 两者叠加的后果是：<b>AI 摘要这一条辅助材料的写入失败，会让整次跨院调阅的
     * notified 推进失败</b> —— 增强层变成了主路径的前置条件，
     * 与 task 27 的承诺（「AI 失败不阻断 notified 推进」）正好相反。
     * 独立事务把失败关在摘要这一侧：它回滚自己，外层事务照常提交。
     *
     * <p><b>代价</b>：摘要行先于外层事务提交。若外层事务后来回滚，会留下一条
     * 没有任务回链的摘要行（任务行的 {@code ai_summary_id} 不会指向它）——
     * 这是「多一条可追溯的记录」与「拖垮主路径」之间的取舍，本项目选前者。
     *
     * @param taskId 任务 ID
     * @param kind   介入点类型
     * @param result 产出（{@code source} 决定落 {@code model} 还是 {@code rule_fallback}）
     * @return 落库后的主键（{@code ai_summaries.id}）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long persist(UUID taskId, String kind, AiResult result) {
        if (taskId == null) {
            throw new IllegalArgumentException("落 AI 摘要时 taskId 不能为 null");
        }
        if (result == null) {
            throw new IllegalArgumentException("落 AI 摘要时 result 不能为 null");
        }
        AiSummary row = new AiSummary();
        row.setTaskId(taskId);
        row.setKind(kind == null || kind.isBlank() ? result.kind() : kind);
        row.setPayload(result.payload());
        // V4 契约：model_name 记的是「实际产出所用的模型」；规则兜底没有模型参与 → 必须是 NULL。
        // 在写库这一层兜住，而不是指望每个调用方都记得传 null ——
        // 一个记着「其实没调模型」的模型名，会让审计轨迹说谎。
        row.setModelName(AiResult.SOURCE_RULE_FALLBACK.equals(result.source()) ? null : result.modelName());
        row.setSource(result.source());
        row.setLatencyMs(toIntMillis(result.latencyMs()));
        AiSummary saved = repository.save(row);
        LOG.debug("AI 摘要已落库：id={} taskId={} kind={} source={}",
                saved.getId(), taskId, saved.getKind(), saved.getSource());
        return saved.getId();
    }

    private static Integer toIntMillis(long latencyMs) {
        if (latencyMs < 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, latencyMs);
    }
}
