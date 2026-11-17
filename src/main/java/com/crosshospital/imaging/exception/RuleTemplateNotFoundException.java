package com.crosshospital.imaging.exception;

/**
 * 规则模板不存在（{@code kind} 在 {@code rule-templates.json} 里查不到）。
 *
 * <p>出现这个异常意味着<b>兜底链路本身缺了一块</b>：模型不可达时没有可渲染的规则模板，
 * 这次 AI 介入就没有结果。它是<b>配置错误</b>（模板文件漏了一条），
 * 不是业务失败 —— 所以单独一个类型，信息科一看就知道该去补模板而不是查网络。
 */
public class RuleTemplateNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String kind;

    public RuleTemplateNotFoundException(String kind) {
        super("没有 kind=" + kind + " 的规则模板；模型不可达时本次 AI 介入无法兜底");
        this.kind = kind;
    }

    /** 缺失的介入点类型。 */
    public String getKind() {
        return kind;
    }
}
