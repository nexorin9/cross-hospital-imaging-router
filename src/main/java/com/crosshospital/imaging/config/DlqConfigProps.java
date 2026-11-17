package com.crosshospital.imaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

/**
 * 调阅任务死信队列配置（{@code dlq.*}）。
 *
 * <p>对应「调阅失败但不能自动恢复」的那一类事件：外院 PACS 关掉了、白名单把某个 AET 挡了、
 * 传输语法对不上 —— 重试多少次都没用，只能进死信等人处置。
 *
 * <p>三个旋钮：
 * <ul>
 *   <li>{@code retention-days} —— 已处置的死信保留多久（默认 30 天）；<b>未处置的死信永不自动清理</b>，
 *       清理掉等于把「还没人管的失败」静默抹掉，那是排障链路上最不能接受的一种丢数据。</li>
 *   <li>{@code alert-webhook-url} —— 告警外发地址（钉钉 / 企业微信 / 院内自建告警网关均可）；
 *       空 = 只落库不推告警。凭据位走环境变量 {@code DLQ_ALERT_WEBHOOK_URL}，本类不持有任何口令。</li>
 *   <li>{@code alert-cooldown-minutes} —— 同一失败码的告警冷却窗口（默认 10 分钟），
 *       防止一次外院断网把几十条同码死信变成几十条告警把值班手机震爆。</li>
 * </ul>
 *
 * <p>源主路径证据：通用分布式系统 DLQ 模式（重试耗尽 → 死信 → 人工处置 → TTL 清理），
 * 与本仓库已有的 {@code hl7.dead-letter.*}（HL7 触发报文死信）同构但作用域不同：
 * 那条记的是「报文没解析出来」，本条记的是「一次调阅任务反复失败」。
 */
@ConfigurationProperties(prefix = "dlq")
@Profile("!test")
public class DlqConfigProps {

    /** 已处置死信的保留天数（默认 30 天）；≤0 表示不自动清理。 */
    private int retentionDays = 30;

    /** 告警 webhook 地址；空 = 不推告警（只落死信表）。 */
    private String alertWebhookUrl = "";

    /** 同一失败码的告警冷却窗口（分钟）；≤0 表示不冷却。 */
    private int alertCooldownMinutes = 10;

    /** 告警投递超时（毫秒）。告警走独立线程，不阻塞失败主路径，但仍要有上限。 */
    private int alertTimeoutMs = 3000;

    /** 死信列表单页最大条数（防呆：信息科手滑写 size=100000 也不会把库拖垮）。 */
    private int maxPageSize = 200;

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public String getAlertWebhookUrl() {
        return alertWebhookUrl;
    }

    public void setAlertWebhookUrl(String alertWebhookUrl) {
        this.alertWebhookUrl = alertWebhookUrl;
    }

    public int getAlertCooldownMinutes() {
        return alertCooldownMinutes;
    }

    public void setAlertCooldownMinutes(int alertCooldownMinutes) {
        this.alertCooldownMinutes = alertCooldownMinutes;
    }

    public int getAlertTimeoutMs() {
        return alertTimeoutMs;
    }

    public void setAlertTimeoutMs(int alertTimeoutMs) {
        this.alertTimeoutMs = alertTimeoutMs;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    /** 解析告警地址；空串按「未配置」处理（返回 {@code null}）。 */
    public String resolveAlertWebhookUrl() {
        if (alertWebhookUrl == null) {
            return null;
        }
        String v = alertWebhookUrl.trim();
        return v.isEmpty() ? null : v;
    }

    /** 启动期自检：保留天数与超时不得为负。 */
    public void validate() {
        if (retentionDays < 0) {
            throw new IllegalArgumentException("dlq.retention-days 不能为负，收到 " + retentionDays);
        }
        if (alertCooldownMinutes < 0) {
            throw new IllegalArgumentException(
                    "dlq.alert-cooldown-minutes 不能为负，收到 " + alertCooldownMinutes);
        }
        if (alertTimeoutMs <= 0) {
            throw new IllegalArgumentException("dlq.alert-timeout-ms 必须为正数，收到 " + alertTimeoutMs);
        }
        if (maxPageSize <= 0) {
            throw new IllegalArgumentException("dlq.max-page-size 必须为正数，收到 " + maxPageSize);
        }
    }
}
