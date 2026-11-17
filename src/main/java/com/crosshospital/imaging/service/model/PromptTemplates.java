package com.crosshospital.imaging.service.model;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 两个 AI 介入点的提示词（task 28 / 29）。
 *
 * <p><b>为什么单独一个类</b>：提示词是要给信息科和影像科医生一起过目的东西，
 * 散在 service 的字符串拼接里没人看得见、也没法单独改。集中放一处后，
 * 「角色设定 / 输入组织 / 输出约束」三件事都能被单独 review。
 *
 * <p><b>两条硬边界写进 system prompt</b>：①只做术语归一 / 初筛草稿，
 * 不产出诊断结论、不替医生判互认；②输出必须是指定的 JSON 结构，
 * 读不懂的内容写「未提供」，<b>不许补写</b>外院报告里没有的所见或意见。
 *
 * <p>规则回退文案不在这里 —— 那部分在 {@code hl7/segment-mappings.yml}
 * 的 {@code model-fallback} 段，由信息科维护。
 */
@Component
public class PromptTemplates {

    /** 报告归一的系统角色。 */
    public static final String REPORT_SYSTEM =
            "你是医院影像科的报告术语归一助手。任务是把外院影像报告里的部位、影像所见、诊断意见、"
                    + "检查项目名改写成本院习惯用语，输出结构化 JSON。"
                    + "严格规则：只改写术语与结构，不新增、不推断、不删除外院报告的医学内容；"
                    + "外院未提供的段落写「外院报告未提供」；不得给出诊断结论或互认结论；"
                    + "输出只是给影像科医生复核的草稿。";

    /** 互认初筛的系统角色。 */
    public static final String FEASIBILITY_SYSTEM =
            "你是医院影像科的互认可行性初筛助手。任务是按外院检查模态、序列、检查项目与本院检查项目"
                    + "的对应关系，给出「可互认序列 / 不建议互认序列」的初筛草稿和判据说明。"
                    + "严格规则：缺证据时一律判 not_recommended 并列明缺失项；"
                    + "不得下最终互认结论，不得给出诊断建议；requiresHumanReview 恒为 true；"
                    + "输出只是给影像科医生逐项复核的草稿。";

    /** 报告归一的输入模板。 */
    public String reportUser(String studyUid, String externalReportText) {
        return "StudyInstanceUID：" + (studyUid == null ? "未提供" : studyUid)
                + "\n外院报告原文：\n" + externalReportText
                + "\n\n请输出 JSON：{\"bodyPart\":string,\"findings\":string,\"impression\":string,"
                + "\"studyItems\":string[]}。bodyPart/findings/impression/studyItems 均为必填。";
    }

    /** 报告归一的两条消息（模型路径与规则兜底路径共用输入组织）。 */
    public List<ChatMessage> reportMessages(String studyUid, String externalReportText) {
        return List.of(ChatMessage.system(REPORT_SYSTEM),
                ChatMessage.user(reportUser(studyUid, externalReportText)));
    }

    /** 互认初筛的输入模板。 */
    public String feasibilityUser(ExternalStudyMetadataView metadata, List<String> localStudyItems) {
        return "外院 StudyInstanceUID：" + nvl(metadata.studyUid())
                + "\n外院模态：" + nvl(metadata.modality())
                + "\n外院检查项目：" + nvl(metadata.studyItem())
                + "\n外院序列：" + (metadata.series() == null || metadata.series().isEmpty()
                        ? "未提供" : String.join("、", metadata.series()))
                + "\n本院检查项目：" + (localStudyItems == null || localStudyItems.isEmpty()
                        ? "未提供" : String.join("、", localStudyItems))
                + "\n\n请输出 JSON：{\"decision\":\"recognizable|not_recommended\","
                + "\"reasons\":string[],\"requiresHumanReview\":true,"
                + "\"recognizableSeries\":string[],\"notRecommendedSeries\":string[]}。"
                + "decision/reasons/requiresHumanReview 为必填。";
    }

    /** 互认初筛的两条消息。 */
    public List<ChatMessage> feasibilityMessages(ExternalStudyMetadataView metadata,
                                                 List<String> localStudyItems) {
        return List.of(ChatMessage.system(FEASIBILITY_SYSTEM),
                ChatMessage.user(feasibilityUser(metadata, localStudyItems)));
    }

    /** 提示词侧的元数据视图（与 domain 类型解耦，避免 service 层反向依赖）。 */
    public record ExternalStudyMetadataView(String studyUid, String modality,
                                            String studyItem, List<String> series) {
        public ExternalStudyMetadataView {
            series = series == null ? List.of() : List.copyOf(series);
        }
    }

    private static String nvl(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }
}
