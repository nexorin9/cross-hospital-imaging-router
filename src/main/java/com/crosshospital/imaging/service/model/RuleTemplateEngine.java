package com.crosshospital.imaging.service.model;

import com.crosshospital.imaging.config.ModelConfigProps;
import com.crosshospital.imaging.exception.RuleTemplateNotFoundException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则模板引擎（task 27）—— 模型不可达时的<b>确定性</b>兜底。
 *
 * <p><b>它解决什么问题</b>：AI 增强层（报告归一 / 互认初筛）是跨院调阅主路径的<b>增强</b>，
 * 不是主路径。模型服务挂了、鉴权错了、返回被截断了 —— 这些都不该让一次已经成功的
 * 跨院调阅卡住，更不该让「AI 顺便算一下」变成<b>必须先问模型</b>的前置条件。
 * 本引擎给出确定性的替代产出：<b>结构与模型路径完全相同</b>，只是内容来自词典而不是模型。
 *
 * <p><b>两份文件各管一件事</b>：
 * <ul>
 *   <li>{@code hl7/rule-templates.json} —— <b>骨架</b>：输出载荷有哪些字段、
 *       每个字段用哪条规则、从哪个输入取值；</li>
 *   <li>{@code hl7/segment-mappings.yml} 的 {@code model-fallback} 段 —— <b>词典</b>：
 *       模态→部位映射、兜底文案、互认判据取值与判据说明。</li>
 * </ul>
 * 拆开的理由：词典是信息科会改的东西（换一家外院的模态叫法、加一句兜底文案），
 * 骨架是开发改的东西（加一个输出字段）。混在一起，改文案要动代码结构。
 *
 * <p><b>启动即校验</b>：模板里引用的词典键在 YAML 里不存在时，<b>启动直接失败</b> ——
 * 模板配错要在部署时暴露，不能等到某天模型挂了才发现兜底也出不来东西。
 *
 * <p><b>不做什么</b>：本引擎不产出诊断意见、不下互认结论、不推进任何业务状态。
 * 它只是把词典里的确定性内容摆成和模型一样的形状，让医生在 PACS 侧看到的
 * 仍然是一份<b>需要人工复核的草稿</b>。
 *
 * <p>源主路径证据：通用 AI 系统的降级模式 —— <b>失败要能被看见、被分类、被兜住</b>，
 * 而不是静默返回一个空结果。
 */
@Service
@Profile("!test")
public class RuleTemplateEngine {

    private static final Logger LOG = LoggerFactory.getLogger(RuleTemplateEngine.class);

    /** YAML 里放词典的段名。 */
    public static final String SECTION_MODEL_FALLBACK = "model-fallback";

    /** 占位符形如 {@code ${raw_findings}}。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    /** 输入键：外院检查模态（CHEST / ABDOMEN / CT / MR…）。 */
    public static final String INPUT_RAW_MODALITY = "raw_modality";
    /** 输入键：外院报告里的部位写法（CHEST / ABDOMEN / PELVIS…）。 */
    public static final String INPUT_RAW_BODY_PART = "raw_body_part";
    /** 输入键：外院报告「影像所见」原文。 */
    public static final String INPUT_RAW_FINDINGS = "raw_findings";
    /** 输入键：外院报告「诊断意见」原文。 */
    public static final String INPUT_RAW_IMPRESSION = "raw_impression";
    /** 输入键：外院检查模态（互认初筛用）。 */
    public static final String INPUT_EXTERNAL_MODALITY = "external_modality";
    /** 输入键：本院检查模态（互认初筛用）。 */
    public static final String INPUT_LOCAL_MODALITY = "local_modality";
    /** 输入键：外院检查项目名（互认初筛用）。 */
    public static final String INPUT_EXTERNAL_STUDY_ITEM = "external_study_item";
    /** 输入键：本院检查项目名（互认初筛用）。 */
    public static final String INPUT_LOCAL_STUDY_ITEM = "local_study_item";
    /** 输入键：任务 ID（模板里可用 {@code ${task_id}} 引用）。 */
    public static final String INPUT_TASK_ID = "task_id";

    /** 解析器：词典映射（模态 → 部位名）。 */
    private static final String RESOLVER_MAPPING = "mapping";
    /** 解析器：原文模板（占位符替换）。 */
    private static final String RESOLVER_TEMPLATE = "template";
    /** 解析器：词典映射成列表（模态 → 检查项目名列表）。 */
    private static final String RESOLVER_MAPPING_LIST = "mapping_list";
    /** 解析器：互认可行性判定。 */
    private static final String RESOLVER_FEASIBILITY_DECISION = "feasibility_decision";
    /** 解析器：互认判据说明列表。 */
    private static final String RESOLVER_FEASIBILITY_REASONS = "feasibility_reasons";
    /** 解析器：常量。 */
    private static final String RESOLVER_CONSTANT = "constant";

    /** kind → 模板骨架。 */
    private final Map<String, RuleTemplate> templates;
    /** kind → （词典键 → 词典定义）。 */
    private final Map<String, Map<String, Object>> ruleSets;
    /** 词典文件位置（实例方法里的 WARN 需要回显来源，不能只留在构造函数局部变量里）。 */
    private final String rulesLocation;
    private final ObjectMapper mapper;

    /**
     * 载入模板骨架与词典，并在启动时校验二者对得上。
     *
     * @param props  模型配置（{@code model.fallback.*}：开关与两份模板文件的位置）
     * @param mapper JSON 序列化器
     */
    public RuleTemplateEngine(ModelConfigProps props, ObjectMapper mapper) {
        if (props == null) {
            throw new IllegalArgumentException("ModelConfigProps 不能为 null");
        }
        if (mapper == null) {
            throw new IllegalArgumentException("ObjectMapper 不能为 null");
        }
        this.mapper = mapper;
        this.rulesLocation = props.getFallback().getTemplatesPath();
        String templatesLocation = props.getFallback().getRuleTemplatesPath();
        this.ruleSets = loadRuleSets(this.rulesLocation);
        this.templates = loadTemplates(templatesLocation);
        validateTemplatesAgainstRules();
        LOG.info("规则模板引擎已装载：模板 {} 份（{}），词典来源 {}",
                templates.size(), String.join(", ", templates.keySet()), this.rulesLocation);
    }

    // ==================================================================
    // 对外接口
    // ==================================================================

    /** 支持的介入点类型。 */
    public Set<String> supportedKinds() {
        return Collections.unmodifiableSet(templates.keySet());
    }

    /** 是否为已知 kind。 */
    public boolean supports(String kind) {
        return kind != null && templates.containsKey(kind);
    }

    /** 取模板骨架。 */
    public Optional<RuleTemplate> find(String kind) {
        return Optional.ofNullable(templates.get(kind));
    }

    /** 取词典定义（{@code model-fallback.<kind>.<rule>}）。 */
    public Optional<Map<String, Object>> findRule(String kind, String rule) {
        Map<String, Object> set = ruleSets.get(kind);
        if (set == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(set.get(rule)).map(RuleTemplateEngine::asMap);
    }

    /**
     * 渲染规则模板载荷（无输入 —— 全部字段走兜底文案）。
     *
     * @param kind   介入点类型
     * @param taskId 任务 ID（可空；非空时注入 {@code task_id} 输入）
     * @return JSON 文本载荷
     * @throws RuleTemplateNotFoundException 该 kind 没有模板
     */
    public String readRuleTemplate(String kind, UUID taskId) {
        return readRuleTemplate(kind, taskId, Map.of());
    }

    /**
     * 渲染规则模板载荷。
     *
     * @param kind   介入点类型（{@code report_normalize} / {@code feasibility_screener}）
     * @param taskId 任务 ID（可空；非空时注入 {@code task_id} 输入）
     * @param inputs 输入键值（见本类的 {@code INPUT_*} 常量）；可空
     * @return JSON 文本载荷；<b>字段与模型路径同形状</b>
     * @throws RuleTemplateNotFoundException 该 kind 没有模板
     */
    public String readRuleTemplate(String kind, UUID taskId, Map<String, Object> inputs) {
        RuleTemplate template = templates.get(kind);
        if (template == null) {
            throw new RuleTemplateNotFoundException(kind);
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (inputs != null) {
            merged.putAll(inputs);
        }
        if (taskId != null) {
            merged.putIfAbsent(INPUT_TASK_ID, taskId.toString());
        }
        Map<String, Object> rendered = new LinkedHashMap<>();
        for (Map.Entry<String, RuleTemplate.FieldDirective> entry : template.fields().entrySet()) {
            rendered.put(entry.getKey(), resolve(kind, entry.getValue(), merged));
        }
        try {
            return mapper.writeValueAsString(rendered);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("规则模板渲染结果序列化失败（kind=" + kind + "）：" + e.getMessage(), e);
        }
    }

    // ==================================================================
    // 解析器
    // ==================================================================

    private Object resolve(String kind, RuleTemplate.FieldDirective directive, Map<String, Object> inputs) {
        String resolver = directive.resolver();
        if (resolver == null || resolver.isBlank()) {
            throw new IllegalStateException("规则模板字段缺少 resolver（kind=" + kind + "）");
        }
        return switch (resolver) {
            case RESOLVER_MAPPING -> resolveMapping(kind, directive, inputs);
            case RESOLVER_TEMPLATE -> resolveTemplate(kind, directive, inputs);
            case RESOLVER_MAPPING_LIST -> resolveMappingList(kind, directive, inputs);
            case RESOLVER_FEASIBILITY_DECISION -> resolveFeasibilityDecision(kind, directive, inputs);
            case RESOLVER_FEASIBILITY_REASONS -> resolveFeasibilityReasons(kind, directive, inputs);
            case RESOLVER_CONSTANT -> directive.constant();
            default -> throw new IllegalStateException("未知规则模板解析器：" + resolver + "（kind=" + kind + "）");
        };
    }

    /** 词典映射：查得到就取映射值，查不到就取兜底文案。 */
    private Object resolveMapping(String kind, RuleTemplate.FieldDirective directive, Map<String, Object> inputs) {
        Map<String, Object> def = requireRule(kind, directive.rule());
        Map<String, Object> mapping = asMap(def.get("mapping"));
        Object fallback = def.get("fallback");
        String key = lookupKey(inputs.get(directive.input()));
        if (key != null && mapping.containsKey(key)) {
            return mapping.get(key);
        }
        return fallback == null ? "" : fallback;
    }

    /** 原文模板：占位符全部能取到就替换，取不到就整段用兜底文案。 */
    private Object resolveTemplate(String kind, RuleTemplate.FieldDirective directive, Map<String, Object> inputs) {
        Map<String, Object> def = requireRule(kind, directive.rule());
        Object fallback = def.get("fallback");
        Object template = def.get("template");
        if (!(template instanceof String text)) {
            throw new IllegalStateException(
                    "规则模板 " + kind + "." + directive.rule() + " 的 template 不是字符串");
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            Object value = lookupTemplateValue(directive, inputs, matcher.group(1));
            if (value == null || String.valueOf(value).isBlank()) {
                // 关键输入缺失 —— 不做「半截替换」：外院没给的内容宁可写兜底文案，
                // 也不能把「${raw_findings}」这种占位符原样写进给医生看的报告里。
                return substituteBestEffort(fallback, inputs);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /**
     * 取占位符对应的输入值。
     *
     * <p><b>为什么要按声明的 {@code input} 再兜一次</b>：词典（YAML）里的占位符名
     * 与骨架（JSON）里声明的 {@code input} 键，是<b>两处独立的地方</b> ——
     * 而词典恰恰是信息科会直接改的文件（换外院叫法、加一句兜底文案）。
     * 若两边对不上（比如词典里的占位符被改名），只按占位符名取值就会取不到，
     * 于是外院报告原文被<b>静默替换</b>成「外院报告未提供影像所见描述」——
     * 载荷看起来完全正常，医生却拿到了一份空报告，而日志与审计里没有任何痕迹。
     *
     * <p>所以这里先按占位符名取，取不到再按骨架声明的 {@code input} 取：
     * 改名不再丢数据，而两处对不上的事实会在<b>启动时</b>被
     * {@link #validateTemplatesAgainstRules()} 记一条 WARN 提醒信息科对齐。
     */
    private Object lookupTemplateValue(RuleTemplate.FieldDirective directive,
                                       Map<String, Object> inputs, String placeholder) {
        Object value = inputs.get(placeholder);
        if (value != null) {
            return value;
        }
        String declaredInput = directive.input();
        if (declaredInput == null || declaredInput.isBlank() || declaredInput.equals(placeholder)) {
            return null;
        }
        return inputs.get(declaredInput);
    }

    /**
     * 兜底文案里的占位符也做替换（尽力而为）。
     *
     * <p><b>为什么兜底文案也要替换</b>：兜底文案同样是给医生看的文本，
     * 里面常带 {@code ${task_id}} 这类引用 —— 留着没替换的占位符，
     * 载荷里就会出现「任务 ${task_id} 无备注」这种让医生一头雾水的内容。
     * 尽力而为的含义：能取到就替换，取不到就留空（<b>不留占位符本身</b>）。
     */
    private static String substituteBestEffort(Object fallback, Map<String, Object> inputs) {
        if (fallback == null) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(String.valueOf(fallback));
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            Object value = inputs.get(matcher.group(1));
            String replacement = value == null || String.valueOf(value).isBlank() ? "" : String.valueOf(value);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /** 词典映射成列表：查不到就取兜底列表。 */
    private Object resolveMappingList(String kind, RuleTemplate.FieldDirective directive, Map<String, Object> inputs) {
        Map<String, Object> def = requireRule(kind, directive.rule());
        Map<String, Object> mapping = asMap(def.get("mapping_modality"));
        if (mapping.isEmpty()) {
            mapping = asMap(def.get("mapping"));
        }
        Object fallback = def.get("fallback");
        String key = lookupKey(inputs.get(directive.input()));
        if (key != null && mapping.containsKey(key)) {
            Object value = mapping.get(key);
            // 词典里写了键却没给值（YAML 空值 → null）时不能 List.of(null)：那是 NPE，
            // 会把「词典少写一项」变成整个介入点失败。走兜底列表更贴近真实意图。
            if (value != null) {
                return List.of(value);
            }
        }
        if (fallback instanceof List<?> list) {
            return list;
        }
        if (fallback != null) {
            return List.of(fallback);
        }
        return List.of();
    }

    /**
     * 互认可行性判定：模态一致 <b>且</b>检查项目名一致 → recognizable，否则 not_recommended。
     *
     * <p><b>这是初筛建议，不是互认结论</b>：判定结果一律带
     * {@code requiresHumanReview=true}，由影像科医生逐项复核后拍板。
     */
    private Object resolveFeasibilityDecision(String kind, RuleTemplate.FieldDirective directive,
                                             Map<String, Object> inputs) {
        Map<String, Object> values = requireRule(kind, directive.rule());
        String recognizable = text(values.get("recognizable"), "recognizable");
        String notRecommended = text(values.get("not-recommended"), "not_recommended");
        boolean modalityMatch = sameText(inputs.get(INPUT_EXTERNAL_MODALITY), inputs.get(INPUT_LOCAL_MODALITY));
        boolean itemMatch = sameText(inputs.get(INPUT_EXTERNAL_STUDY_ITEM), inputs.get(INPUT_LOCAL_STUDY_ITEM));
        return modalityMatch && itemMatch ? recognizable : notRecommended;
    }

    /** 互认判据说明：模态匹配与否 + 检查项目一致与否，各出一条。 */
    private Object resolveFeasibilityReasons(String kind, RuleTemplate.FieldDirective directive,
                                            Map<String, Object> inputs) {
        Map<String, Object> def = requireRule(kind, directive.rule());
        Map<String, Object> texts = asMap(def.get("template"));
        if (texts.isEmpty()) {
            texts = def;
        }
        boolean modalityMatch = sameText(inputs.get(INPUT_EXTERNAL_MODALITY), inputs.get(INPUT_LOCAL_MODALITY));
        boolean itemMatch = sameText(inputs.get(INPUT_EXTERNAL_STUDY_ITEM), inputs.get(INPUT_LOCAL_STUDY_ITEM));
        List<String> reasons = new ArrayList<>(2);
        reasons.add(text(texts.get(modalityMatch ? "modality_match" : "modality_mismatch"), ""));
        reasons.add(text(texts.get(itemMatch ? "study_item_match" : "study_item_mismatch"), ""));
        reasons.removeIf(String::isEmpty);
        return reasons;
    }

    // ==================================================================
    // 装载与校验
    // ==================================================================

    /** 载入 YAML 词典段。 */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> loadRuleSets(String location) {
        Object root = readYaml(location);
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new IllegalStateException("规则词典文件解析结果不是映射：" + location);
        }
        Object section = rootMap.get(SECTION_MODEL_FALLBACK);
        if (!(section instanceof Map<?, ?> sectionMap)) {
            throw new IllegalStateException("规则词典文件缺少 " + SECTION_MODEL_FALLBACK + " 段：" + location);
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : sectionMap.entrySet()) {
            String kind = String.valueOf(entry.getKey());
            if (entry.getValue() instanceof Map<?, ?> kindMap) {
                result.put(kind, (Map<String, Object>) kindMap);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** 载入 JSON 模板骨架。 */
    @SuppressWarnings("unchecked")
    private Map<String, RuleTemplate> loadTemplates(String location) {
        try (InputStream in = openResource(location)) {
            Map<String, Object> root = mapper.readValue(in, new TypeReference<Map<String, Object>>() {
            });
            Object section = root.get("templates");
            if (!(section instanceof Map<?, ?> sectionMap)) {
                throw new IllegalStateException("规则模板文件缺少 templates 段：" + location);
            }
            Map<String, RuleTemplate> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : sectionMap.entrySet()) {
                String kind = String.valueOf(entry.getKey());
                if (!(entry.getValue() instanceof Map<?, ?> kindMap)) {
                    continue;
                }
                Object description = kindMap.get("description");
                Object fieldsNode = kindMap.get("fields");
                Map<String, RuleTemplate.FieldDirective> directives = new LinkedHashMap<>();
                if (fieldsNode instanceof Map<?, ?> fieldsMap) {
                    for (Map.Entry<?, ?> field : fieldsMap.entrySet()) {
                        if (field.getValue() instanceof Map<?, ?> spec) {
                            directives.put(String.valueOf(field.getKey()),
                                    new RuleTemplate.FieldDirective(
                                            stringOrNull(spec.get("resolver")),
                                            stringOrNull(spec.get("rule")),
                                            stringOrNull(spec.get("input")),
                                            spec.get("value")));
                        }
                    }
                }
                result.put(kind, new RuleTemplate(kind,
                        description == null ? "" : String.valueOf(description), directives));
            }
            return Collections.unmodifiableMap(result);
        } catch (IOException e) {
            throw new IllegalStateException("规则模板文件读取失败：" + location, e);
        }
    }

    /**
     * 启动即校验：模板引用的词典键必须存在。
     *
     * <p>模板配错要在<b>部署时</b>暴露 —— 等到某天模型挂了才发现兜底也出不来东西，
     * 那是把一次配置错误拖成了生产事故。
     */
    private void validateTemplatesAgainstRules() {
        for (RuleTemplate template : templates.values()) {
            Map<String, Object> ruleSet = ruleSets.get(template.kind());
            if (ruleSet == null) {
                throw new IllegalStateException("规则模板 kind=" + template.kind()
                        + " 在词典文件里没有对应的 " + SECTION_MODEL_FALLBACK + " 段");
            }
            for (Map.Entry<String, RuleTemplate.FieldDirective> entry : template.fields().entrySet()) {
                RuleTemplate.FieldDirective directive = entry.getValue();
                if (RESOLVER_CONSTANT.equals(directive.resolver())) {
                    continue;
                }
                if (directive.rule() == null || directive.rule().isBlank()) {
                    throw new IllegalStateException("规则模板 " + template.kind() + "." + entry.getKey()
                            + " 缺少 rule 键");
                }
                if (!ruleSet.containsKey(directive.rule())) {
                    throw new IllegalStateException("规则模板 " + template.kind() + "." + entry.getKey()
                            + " 引用的词典键 " + SECTION_MODEL_FALLBACK + "." + template.kind() + "."
                            + directive.rule() + " 在词典文件里不存在");
                }
                if (RESOLVER_TEMPLATE.equals(directive.resolver())) {
                    warnOnPlaceholderDrift(template.kind(), entry.getKey(), directive, ruleSet);
                }
            }
        }
    }

    /**
     * 词典占位符与骨架声明的输入键对不上时，启动记一条 WARN。
     *
     * <p><b>为什么不直接启动失败</b>：渲染时已经按骨架声明的输入键兜底取值，
     * 内容不会丢；此时把服务拦在启动门外，代价（整个跨院调阅起不来）
     * 大于收益（提示信息科对齐两处命名）。但这条不一致<b>必须被看见</b> ——
     * 否则词典改了名，下一个人接手时会以为两份文件本来就是这么写的。
     */
    private void warnOnPlaceholderDrift(String kind, String field, RuleTemplate.FieldDirective directive,
                                        Map<String, Object> ruleSet) {
        String input = directive.input();
        if (input == null || input.isBlank()) {
            return;
        }
        Object def = ruleSet.get(directive.rule());
        if (!(def instanceof Map<?, ?> map) || !(map.get("template") instanceof String text)) {
            return;
        }
        if (text.contains("${" + input + "}")) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        List<String> placeholders = new ArrayList<>();
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }
        LOG.warn("规则模板 {}.{}：词典占位符 {} 与骨架声明的输入键 {} 不一致（词典来源 {}）——"
                        + "渲染时按 {} 取值，内容不会丢，但建议把两处命名对齐",
                kind, field, placeholders, input, rulesLocation, input);
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private Map<String, Object> requireRule(String kind, String rule) {
        if (rule == null || rule.isBlank()) {
            throw new IllegalStateException("规则模板缺少词典键（kind=" + kind + "）");
        }
        Map<String, Object> set = ruleSets.get(kind);
        Object def = set == null ? null : set.get(rule);
        if (def == null) {
            throw new IllegalStateException("词典缺少 " + SECTION_MODEL_FALLBACK + "." + kind + "." + rule);
        }
        return asMap(def);
    }

    private Object readYaml(String location) {
        try (InputStream in = openResource(location);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new Yaml().load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("规则词典文件读取失败：" + location, e);
        }
    }

    /** 打开 classpath 或磁盘上的资源。 */
    private InputStream openResource(String location) throws IOException {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("规则模板文件位置未配置");
        }
        Resource resource = resolveResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("规则模板文件不存在：" + location);
        }
        return resource.getInputStream();
    }

    /**
     * 解析模板文件位置。
     *
     * <p><b>为什么绝对路径要单独判</b>：{@link DefaultResourceLoader} 把不以
     * {@code classpath:} 开头的路径一律当<b>类路径</b>解析，
     * 于是 {@code /etc/ops/rules.yml} 这种绝对路径会被当成 classpath 里的资源 ——
     * 结果是「文件明明在那儿，却说不存在」。信息科把词典放到运维目录里是很常见的做法，
     * 这里显式认绝对路径与 {@code file:} 前缀。
     */
    private Resource resolveResource(String location) {
        if (location.startsWith("classpath:")) {
            return new ClassPathResource(location.substring("classpath:".length()));
        }
        if (location.startsWith("file:")) {
            return new FileSystemResource(location.substring("file:".length()));
        }
        if (location.startsWith("/") || location.contains(":\\")) {
            return new FileSystemResource(location);
        }
        return new DefaultResourceLoader().getResource(location);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    /** 取词典查找键：去空白 + 大写（外院模态写法不统一：chest / Chest / CHEST）。 */
    private static String lookupKey(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.toUpperCase(Locale.ROOT);
    }

    /** 两个值是否「同一个」（忽略首尾空白与大小写）。 */
    private static boolean sameText(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        String a = String.valueOf(left).trim();
        String b = String.valueOf(right).trim();
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.equalsIgnoreCase(b);
    }

    private static String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value);
        return s.isEmpty() ? fallback : s;
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
