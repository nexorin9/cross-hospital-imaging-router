package com.crosshospital.imaging.service.notification;

/**
 * 一次 HIS 通知的投递结果。
 *
 * @param delivered    是否送达（HTTP 2xx）
 * @param attempts     实际尝试次数
 * @param failureCode  失败码（delivered=true 时为 null）
 * @param message      可读说明
 */
public record NotificationResult(boolean delivered,
                                 int attempts,
                                 String failureCode,
                                 String message) {

    public static NotificationResult ok(int attempts) {
        return new NotificationResult(true, attempts, null, "HIS 工单回调已送达");
    }

    public static NotificationResult failed(int attempts, String code, String message) {
        return new NotificationResult(false, attempts, code, message);
    }
}
