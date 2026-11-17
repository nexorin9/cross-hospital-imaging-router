package com.crosshospital.imaging.exception;

/**
 * 外院报告原文为空 / 不可读（task 28）。
 *
 * <p><b>为什么单独一个异常类型</b>：外院报告为空是「输入不合法」，不是「模型不可达」。
 * 前者不该走规则模板兜底凑一份看起来正常的草稿 —— 那等于凭空造一份报告给医生看；
 * 后者才该兜底。分类错了，兜底就会把「没有数据」伪装成「已经处理过」。
 *
 * <p>继承 {@link ValidationException}：HTTP 400，且 {@code GlobalExceptionHandler}
 * 已有统一响应（code / message / traceId）。
 */
public class InvalidReportTextException extends ValidationException {

    private static final long serialVersionUID = 1L;

    public InvalidReportTextException(String message) {
        super(message);
    }
}
