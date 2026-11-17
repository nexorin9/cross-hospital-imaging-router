package com.crosshospital.imaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

/**
 * HIS 工单通知配置（{@code notification.*}，task 32）。
 *
 * <p>凭据位全部走环境变量：webhook URL 里常带院内网关 token，
 * 不写进代码、不落日志、不落死信表（死信只记 host 摘要）。
 */
@ConfigurationProperties(prefix = "notification")
@Profile("!test")
public class NotificationConfigProps {

    /** HIS 工单回调地址；留空 = 未配置（落死信，不假装通知成功）。 */
    private String hisWebhookUrl = "";

    /** 单次 POST 超时（毫秒）。 */
    private int hisWebhookTimeoutMs = 5000;

    /** 失败是否重试。 */
    private boolean retryOnFailure = true;

    /** 最大尝试次数（含首次）。 */
    private int maxRetries = 3;

    /** 重试退避基数（毫秒），第 n 次等待 base × n。 */
    private long backoffMs = 200;

    /** 院内告警网关地址；留空 = 只落库与日志。 */
    private String alertWebhookUrl = "";

    /** 本院 PACS 的 Called AE Title（回调消息体里的 target_pacs_aet）。 */
    private String targetPacsAet = "LOCAL_PACS";

    public String getHisWebhookUrl() { return hisWebhookUrl; }
    public void setHisWebhookUrl(String hisWebhookUrl) { this.hisWebhookUrl = hisWebhookUrl; }

    public int getHisWebhookTimeoutMs() { return hisWebhookTimeoutMs; }
    public void setHisWebhookTimeoutMs(int hisWebhookTimeoutMs) { this.hisWebhookTimeoutMs = hisWebhookTimeoutMs; }

    public boolean isRetryOnFailure() { return retryOnFailure; }
    public void setRetryOnFailure(boolean retryOnFailure) { this.retryOnFailure = retryOnFailure; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public long getBackoffMs() { return backoffMs; }
    public void setBackoffMs(long backoffMs) { this.backoffMs = backoffMs; }

    public String getAlertWebhookUrl() { return alertWebhookUrl; }
    public void setAlertWebhookUrl(String alertWebhookUrl) { this.alertWebhookUrl = alertWebhookUrl; }

    public String getTargetPacsAet() { return targetPacsAet; }
    public void setTargetPacsAet(String targetPacsAet) { this.targetPacsAet = targetPacsAet; }
}
