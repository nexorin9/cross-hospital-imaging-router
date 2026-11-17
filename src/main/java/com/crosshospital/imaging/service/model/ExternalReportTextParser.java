package com.crosshospital.imaging.service.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 外院报告原文的分段抽取（task 28）。
 *
 * <p><b>它给两条路径供料</b>：模型路径把整篇原文放进 user prompt；模型不可达时，
 * 规则模板引擎需要 {@code raw_findings / raw_impression / raw_body_part / raw_modality}
 * 四个输入才能渲染出「同形状」的兜底载荷。没有这份解析，兜底只能全写「未提供」，
 * 医生拿到的草稿会把明明存在的所见与意见也抹掉。
 *
 * <p><b>不臆造</b>：认不出的段落一律留空，由词典的兜底文案接管；
 * 解析只做切分与关键词归一，不做任何医学推断。
 */
public final class ExternalReportTextParser {

    private ExternalReportTextParser() {
    }

    /** 部位关键词 → 词典 {@code body_part.mapping} 的键（segment-mappings.yml）。 */
    private static final Map<String, String> BODY_PARTS = Map.of(
            "胸", "CHEST", "肺", "CHEST",
            "腹", "ABDOMEN", "肝", "ABDOMEN",
            "盆", "PELVIS",
            "头", "HEAD", "颅", "HEAD", "脑", "HEAD",
            "膝", "KNEE");

    private static final List<String> MODALITIES = List.of("CT", "MR", "XA", "US", "CR", "DX");

    /**
     * 抽取规则模板输入。
     *
     * @param reportText 外院报告原文（可含中英文段落标题）
     * @return 键与 {@link RuleTemplateEngine#INPUT_*} 常量对齐的输入表
     */
    public static Map<String, Object> ruleInputs(String reportText) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (reportText == null || reportText.isBlank()) {
            return inputs;
        }
        StringBuilder findings = new StringBuilder();
        StringBuilder impression = new StringBuilder();
        String section = "findings";
        for (String rawLine : reportText.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String heading = sectionOf(line);
            if (heading != null) {
                section = heading;
                // 标题行本身可能带着正文：「影像所见：双肺纹理清晰」
                String rest = stripHeading(line);
                if (!rest.isBlank()) {
                    append(section, rest, findings, impression);
                }
                continue;
            }
            append(section, line, findings, impression);
        }
        inputs.put(RuleTemplateEngine.INPUT_RAW_FINDINGS, findings.toString().trim());
        inputs.put(RuleTemplateEngine.INPUT_RAW_IMPRESSION, impression.toString().trim());
        inputs.put(RuleTemplateEngine.INPUT_RAW_BODY_PART, detectBodyPart(reportText));
        inputs.put(RuleTemplateEngine.INPUT_RAW_MODALITY, detectModality(reportText));
        inputs.put("raw_report_text", reportText);
        return inputs;
    }

    /** 段落标题识别：中文院内写法 + 外院常见英文写法。 */
    private static String sectionOf(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.startsWith("影像所见") || upper.startsWith("检查所见")
                || upper.startsWith("所见") || upper.startsWith("FINDINGS")) {
            return "findings";
        }
        if (upper.startsWith("诊断意见") || upper.startsWith("影像诊断") || upper.startsWith("印象")
                || upper.startsWith("结论") || upper.startsWith("IMPRESSION")
                || upper.startsWith("CONCLUSION")) {
            return "impression";
        }
        return null;
    }

    /** 去掉标题前缀，保留冒号后的正文。 */
    private static String stripHeading(String line) {
        int colon = Math.max(line.indexOf('：'), line.indexOf(':'));
        return colon < 0 ? "" : line.substring(colon + 1).trim();
    }

    private static void append(String section, String text,
                               StringBuilder findings, StringBuilder impression) {
        StringBuilder target = "impression".equals(section) ? impression : findings;
        if (target.length() > 0) {
            target.append('\n');
        }
        target.append(text);
    }

    /** 部位归一：只认词典里已有的键，认不出就返回空字符串（走「未明确」兜底）。 */
    private static String detectBodyPart(String text) {
        for (Map.Entry<String, String> entry : BODY_PARTS.entrySet()) {
            if (text.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        for (String key : List.of("CHEST", "ABDOMEN", "PELVIS", "HEAD", "KNEE")) {
            if (text.toUpperCase(Locale.ROOT).contains(key)) {
                return key;
            }
        }
        return "";
    }

    /** 模态归一：按词典 {@code study_items.mapping_modality} 的键取第一个命中。 */
    private static String detectModality(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        for (String modality : MODALITIES) {
            if (upper.contains(modality)) {
                return modality;
            }
        }
        return "";
    }
}
