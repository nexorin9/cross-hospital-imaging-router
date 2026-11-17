package com.crosshospital.imaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

/**
 * WADO-RS 拉取配置（{@code wado.*}）。
 *
 * <p>对应对外集成面「区域 PACS 检索网关」的 HTTP 入口。凭据位（{@link #bearerToken}）由环境变量
 * {@code WADO_RS_BEARER_TOKEN} 注入，**不**硬编码、不落库。
 *
 * <p>源主路径证据：dcm4che-mime {@code MultipartParser.java}（boundary 驱动的 multipart/related
 * 流式解析）；本项目的 HTTP 外壳由 OkHttp 承担 —— dcm4che 的 {@code wadors} 命令行工具正是
 * 「WADO-RS GET + multipart/related 解析」这一对动作，本配置把该对动作参数化并接入环境变量。
 */
@ConfigurationProperties(prefix = "wado")
@Profile("!test")
public class WadoConfigProps {

    /**
     * WADO-RS URL 模板。支持两种占位：
     * <ul>
     *   <li>{@code {studyUid}} —— 拉取 study 时替换为 StudyInstanceUID（推荐）</li>
     *   <li>{@code {peerAet}} —— 多网关场景下替换为外院节点 AET</li>
     * </ul>
     * 若模板不含 {@code {studyUid}}，客户端会把 studyUid 追加到路径末尾。
     */
    private String baseUrlTemplate = "http://127.0.0.1:8081/wado/studies/{studyUid}";

    /** Bearer 令牌（环境变量 {@code WADO_RS_BEARER_TOKEN}）；空字符串 = 不带 Authorization 头。 */
    private String bearerToken = "";

    /** TCP/TLS 连接超时（毫秒）。 */
    private int connectTimeoutMs = 5000;

    /** 响应读取超时（毫秒）；跨院大 study 拉取按此值放宽。 */
    private int readTimeoutMs = 30000;

    /** 单次拉取响应体字节上限；超过即拒绝，防止网关异常回包打满堆。 */
    private long maxMultipartBytes = 5_368_709_120L;

    /** HTTP User-Agent；信息科在区域网关侧按此识别调用方。 */
    private String userAgent = "cross-hospital-imaging-router/0.1";

    /** 最大重试次数（task 12 的 {@code WadoRsRetryPolicy} 消费；4xx 不计入）。 */
    private int maxRetries = 3;

    public String getBaseUrlTemplate() { return baseUrlTemplate; }
    public void setBaseUrlTemplate(String baseUrlTemplate) { this.baseUrlTemplate = baseUrlTemplate; }

    public String getBearerToken() { return bearerToken; }
    public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken; }

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }

    public long getMaxMultipartBytes() { return maxMultipartBytes; }
    public void setMaxMultipartBytes(long maxMultipartBytes) { this.maxMultipartBytes = maxMultipartBytes; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
}