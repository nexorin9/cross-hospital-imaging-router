package com.crosshospital.imaging.service.task;

import com.crosshospital.imaging.domain.ExternalStudyMaterial;
import com.crosshospital.imaging.domain.ExternalStudyMetadata;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.repository.ExternalStudyMaterialRepository;
import com.crosshospital.imaging.service.model.FeasibilityScreenerService;
import com.crosshospital.imaging.service.model.ReportNormalizationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 两个 AI 介入点的组合端口（task 28 + 29 接入主路径）。
 *
 * <p><b>为什么是「实现端口」而不是直接改 FetchTaskService</b>：task 27 在主路径上留了
 * 一个可拔的插头 {@link AiEnrichmentPort}，主路径只认一个端口。两个介入点都要在
 * notified 前产出摘要，就把它们收进这一个类里 —— 主路径不用知道自己接了几个 AI 服务，
 * 拔掉这个 bean 后行为与「没有 AI 这一层」完全一致。
 *
 * <p><b>真实输入来自 V9 表</b>：外院报告原文、模态、序列、检查项目由 C-FIND / WADO-RS
 * 拉回后登记。资料没登记时<b>不产出摘要</b>（不臆造外院报告内容），
 * 主路径照常推进 notified。
 *
 * <p><b>两条摘要，一个 task.ai_summary_id</b>：任务行上的字段只能指向一行，
 * 本端口优先返回「报告归一」的摘要 id；互认初筛摘要落
 * {@code ai_summaries(kind=feasibility_screener)}，按同一个 {@code task_id} 关联可查。
 * 这是 task 27 定下的表结构约束，不是漏写。
 *
 * <p><b>失败一律降级</b>：资料读取、模型、落库任一环节抛异常都返回
 * {@link AiEnrichmentOutcome#unavailable(String)}，绝不把增强层故障冒泡成路由失败。
 */
@Service
@Profile("!test")
public class ImagingAiEnrichment implements AiEnrichmentPort {

    private static final Logger LOG = LoggerFactory.getLogger(ImagingAiEnrichment.class);

    private final ExternalStudyMaterialRepository materials;
    private final ReportNormalizationService reports;
    private final FeasibilityScreenerService screener;
    private final ObjectMapper mapper;

    public ImagingAiEnrichment(ExternalStudyMaterialRepository materials,
                               ReportNormalizationService reports,
                               FeasibilityScreenerService screener,
                               ObjectMapper mapper) {
        if (materials == null || reports == null || screener == null || mapper == null) {
            throw new IllegalArgumentException("ImagingAiEnrichment 的依赖不能为 null");
        }
        this.materials = materials;
        this.reports = reports;
        this.screener = screener;
        this.mapper = mapper;
    }

    @Override
    public AiEnrichmentOutcome enrich(FetchTask task) {
        if (task == null || task.getStudyUid() == null || task.getStudyUid().isBlank()) {
            return AiEnrichmentOutcome.unavailable("任务缺少 study_uid，无法读取外院检查资料");
        }
        try {
            return enrichInternal(task);
        } catch (RuntimeException ex) {
            LOG.warn("AI 增强异常（已降级为本次不产出，不阻断 notified）：taskId={} msg={}",
                    task.getTaskId(), ex.getMessage());
            return AiEnrichmentOutcome.unavailable("AI 增强异常：" + ex.getMessage());
        }
    }

    private AiEnrichmentOutcome enrichInternal(FetchTask task) {
        ExternalStudyMaterial material = materials.findByStudyUid(task.getStudyUid()).orElse(null);
        if (material == null) {
            LOG.info("外院检查资料未登记，本次不产出 AI 摘要（不臆造报告内容）：taskId={} studyUid={}",
                    task.getTaskId(), task.getStudyUid());
            return AiEnrichmentOutcome.unavailable("外院检查资料未登记（study_uid=" + task.getStudyUid() + "）");
        }

        List<String> series = readList(material.getSeries());
        List<String> studyItems = readList(material.getStudyItems());
        List<String> localItems = readList(material.getLocalStudyItems());
        String studyItem = studyItems.isEmpty() ? "" : studyItems.get(0);

        // 介入点 1：有外院报告原文才归一；没有就跳过（绝不拿空文本凑一份草稿）
        AiEnrichmentOutcome primary = null;
        if (material.getReportText() != null && !material.getReportText().isBlank()) {
            primary = reports.normalizeForTask(task.getTaskId(), material.getStudyUid(),
                    material.getReportText());
        }

        // 介入点 2：互认初筛（模态 / 序列 / 检查项目来自外院元数据）
        ExternalStudyMetadata metadata = new ExternalStudyMetadata(
                material.getStudyUid(), material.getModality(), studyItem, series);
        AiEnrichmentOutcome secondary = screener.screenForTask(task.getTaskId(), metadata, localItems);

        if (primary != null && primary.hasSummary()) {
            return primary;
        }
        if (secondary.hasSummary()) {
            return secondary;
        }
        return AiEnrichmentOutcome.unavailable("两个 AI 介入点均无产出");
    }

    /** JSONB 列以 JSON 文本承载，读成字符串列表；解析失败按空列表处理（不臆造序列）。 */
    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = mapper.readTree(json);
            List<String> result = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(item -> result.add(item.asText()));
            }
            return List.copyOf(result);
        } catch (JsonProcessingException e) {
            LOG.warn("外院检查资料的 JSON 字段不可解析，按空列表处理：{}", e.getMessage());
            return List.of();
        }
    }
}
