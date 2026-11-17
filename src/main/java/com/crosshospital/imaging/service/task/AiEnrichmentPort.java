package com.crosshospital.imaging.service.task;

import com.crosshospital.imaging.domain.FetchTask;

/**
 * AI 增强端口（task 27 引入；task 28 报告归一、task 29 互认初筛各自实现并注册）。
 *
 * <p><b>为什么用端口而不是直接依赖服务</b>：调阅任务服务（{@code FetchTaskService}）
 * 是主路径，AI 增强是增强层。增强层要换模型、换提示词、甚至整个不部署，
 * 都不该逼着主路径改签名 —— 端口在这里就是那条<b>可以拔掉的插头</b>。
 * 端口没接上时，{@code FetchTaskService} 的行为与「没有 AI 这一层」完全一致。
 *
 * <p><b>实现方必须遵守的契约</b>：
 * <ol>
 *   <li>模型不可达时<b>回退规则模板</b>，产出 {@code source=rule_fallback} 的摘要，
 *       而不是把失败抛给调用方；</li>
 *   <li>连规则兜底都没有时返回 {@link AiEnrichmentOutcome#unavailable(String)}，
 *       <b>不抛异常</b>；</li>
 *   <li>无论返回什么，<b>都不推进 PACS 写入、不推 HIS、不改任务状态</b> ——
 *       那些是 {@code FetchTaskService} 的职责。</li>
 * </ol>
 */
public interface AiEnrichmentPort {

    /**
     * 为任务产出 AI 摘要（落 {@code ai_summaries}）。
     *
     * @param task 调阅任务行
     * @return 产出结果；本次没有产出时返回 {@link AiEnrichmentOutcome#unavailable(String)}
     */
    AiEnrichmentOutcome enrich(FetchTask task);
}
