package com.crosshospital.imaging.config;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;

import com.crosshospital.imaging.service.hl7.MllpRelease;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

/**
 * HL7 v2.x MLLP 触发通道配置（{@code hl7.*}）。
 *
 * <p>对应对外集成面「本院 HIS → 调阅触发」的 TCP 入口：HIS 在医生发起跨院调阅时向本服务
 * {@code tcp/<hl7.trigger.port>} 发一条 MLLP 帧的 ORM^O01，服务确认（ACK）后入队。
 *
 * <p>凭据位与地址位全部走环境变量（{@code HL7_TRIGGER_PORT} / {@code HL7_TRIGGER_BIND_HOST} /
 * {@code HL7_CHARSET} / {@code HL7_LOCAL_APPLICATION} / {@code HL7_LOCAL_FACILITY} /
 * {@code HL7_MLLP_RELEASE_MODE}），本类不持有任何口令。
 *
 * <p>源主路径证据：{@code github_ref/dcm4che/dcm4che-hl7/.../MLLPConnection.java} 的 MLLP1/MLLP2
 * 释放模式与 ACK/NAK 语义，在本配置面表现为 {@link Trigger#getReleaseMode()}。
 */
@ConfigurationProperties(prefix = "hl7")
@Profile("!test")
public class Hl7ConfigProps {

    /** 触发通道（{@code hl7.trigger.*}）。 */
    private Trigger trigger = new Trigger();

    /** HL7 死信与告警（{@code hl7.dead-letter.*}）。 */
    private DeadLetter deadLetter = new DeadLetter();

    /** 本方 HL7 应用名（MSH-3 Sending Application 回执用）；同 DICOM AET，≤16 字符。 */
    private String localApplication = "IMAGER";

    /** 本方设施/院区标识（MSH-4 Sending Facility 回执用）；≤16 字符。 */
    private String localFacility = "LOCAL_HOSP";

    /** 段位置白名单与规则回退词典（task 18 消费）。 */
    private String segmentMappings = "classpath:hl7/segment-mappings.yml";

    /** HL7 应用标识（MSH-9 之外的应用层身份），供回执引用。 */
    public Trigger getTrigger() {
        return trigger;
    }

    public void setTrigger(Trigger trigger) {
        this.trigger = trigger == null ? new Trigger() : trigger;
    }

    public DeadLetter getDeadLetter() {
        return deadLetter;
    }

    public void setDeadLetter(DeadLetter deadLetter) {
        this.deadLetter = deadLetter == null ? new DeadLetter() : deadLetter;
    }

    public String getLocalApplication() {
        return localApplication;
    }

    public void setLocalApplication(String localApplication) {
        this.localApplication = localApplication;
    }

    public String getLocalFacility() {
        return localFacility;
    }

    public void setLocalFacility(String localFacility) {
        this.localFacility = localFacility;
    }

    public String getSegmentMappings() {
        return segmentMappings;
    }

    public void setSegmentMappings(String segmentMappings) {
        this.segmentMappings = segmentMappings;
    }

    /**
     * 触发通道参数。
     *
     * <p>与 {@code application.yml} 的 {@code hl7.trigger.*} 块一一对应。
     */
    public static class Trigger {

        /** 监听端口；0 = 由内核分配空闲端口（测试用，生产固定 6661 走环境变量）。 */
        private int port = 6661;

        /** 监听地址；默认全网卡，便于 HIS 从任意院内网段接入。 */
        private String bindHost = "0.0.0.0";

        /** 单帧 payload 上限（字节）。院内 ORM^O01 通常几百字节，4 MB 足够覆盖含长报告文本的极端情况。 */
        private int maxFrameBytes = 4 * 1024 * 1024;

        /** 读空闲阈值（毫秒），0 = 关闭空闲检测。 */
        private long idleTimeoutMs = 30_000L;

        /** 是否在空闲超时后<b>断开</b>连接。默认 false —— MLLP 触发通道常被 HIS 长连复用，
         * 空闲只告警不断连；仅当院内防火墙清理长连接导致对端不自愈时才置 true。 */
        private boolean closeOnIdle = false;

        /** Netty worker 线程数；0 = 按 CPU 核数 ×2。 */
        private int workerThreads = 0;

        /** accept backlog。 */
        private int backlog = 128;

        /** 报文编码。国内 HIS 常见 GBK，但国标 HL7 要求 UTF-8，故默认 UTF-8 并留环境变量位。 */
        private String charset = "UTF-8";

        /** MLLP 释放模式：MLLP2 回 ACK（默认）；MLLP1 不回。 */
        private String releaseMode = "MLLP2";

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getBindHost() {
            return bindHost;
        }

        public void setBindHost(String bindHost) {
            this.bindHost = bindHost;
        }

        public int getMaxFrameBytes() {
            return maxFrameBytes;
        }

        public void setMaxFrameBytes(int maxFrameBytes) {
            this.maxFrameBytes = maxFrameBytes;
        }

        public long getIdleTimeoutMs() {
            return idleTimeoutMs;
        }

        public void setIdleTimeoutMs(long idleTimeoutMs) {
            this.idleTimeoutMs = idleTimeoutMs;
        }

        public boolean isCloseOnIdle() {
            return closeOnIdle;
        }

        public void setCloseOnIdle(boolean closeOnIdle) {
            this.closeOnIdle = closeOnIdle;
        }

        public int getWorkerThreads() {
            return workerThreads;
        }

        public void setWorkerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
        }

        public int getBacklog() {
            return backlog;
        }

        public void setBacklog(int backlog) {
            this.backlog = backlog;
        }

        public String getCharset() {
            return charset;
        }

        public void setCharset(String charset) {
            this.charset = charset;
        }

        public String getReleaseMode() {
            return releaseMode;
        }

        public void setReleaseMode(String releaseMode) {
            this.releaseMode = releaseMode;
        }

        /** 解析报文编码；配置值非法时抛异常（配置型失败要早暴露，不静默回退）。 */
        public Charset resolveCharset() {
            if (charset == null || charset.isBlank()) {
                return java.nio.charset.StandardCharsets.UTF_8;
            }
            try {
                return Charset.forName(charset.trim());
            } catch (IllegalCharsetNameException | UnsupportedCharsetException ex) {
                throw new IllegalArgumentException("hl7.trigger.charset 取值非法: '" + charset + "'", ex);
            }
        }

        /** 解析释放模式；取值非法时抛 {@link IllegalArgumentException}。 */
        public MllpRelease resolveReleaseMode() {
            return MllpRelease.parse(releaseMode);
        }

        /**
         * 启动前参数自检：把「配错了直到有人发消息才炸」改成「启动即失败」。
         *
         * @return 校验通过后的最终监听端口（0 表示由内核分配）
         */
        public int validate() {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("hl7.trigger.port 必须在 0..65535，收到 " + port);
            }
            if (maxFrameBytes <= 0) {
                throw new IllegalArgumentException("hl7.trigger.max-frame-bytes 必须为正数，收到 " + maxFrameBytes);
            }
            if (idleTimeoutMs < 0) {
                throw new IllegalArgumentException("hl7.trigger.idle-timeout-ms 不能为负，收到 " + idleTimeoutMs);
            }
            if (workerThreads < 0) {
                throw new IllegalArgumentException("hl7.trigger.worker-threads 不能为负，收到 " + workerThreads);
            }
            if (backlog <= 0) {
                throw new IllegalArgumentException("hl7.trigger.backlog 必须为正数，收到 " + backlog);
            }
            if (bindHost == null || bindHost.isBlank()) {
                throw new IllegalArgumentException("hl7.trigger.bind-host 不能为空");
            }
            resolveCharset();
            resolveReleaseMode();
            return port;
        }
    }

    /**
     * HL7 死信与告警配置（{@code hl7.dead-letter.*}）。
     *
     * <p>死信永远落库；告警 webhook 是<b>可选</b>的外发通道 —— 没配就只落库 + 记日志，
     * 不影响触发通道本身工作。URL 走环境变量 {@code HL7_DEADLETTER_ALERT_WEBHOOK_URL}，
     * 本类不持有任何口令。
     */
    public static class DeadLetter {

        /** 告警 webhook 地址；空 = 不推告警（只落死信表）。 */
        private String alertWebhookUrl = "";

        /** 告警投递超时（毫秒）。告警走独立线程，不占 Netty 事件循环，但仍要有上限。 */
        private int alertTimeoutMs = 3000;

        public String getAlertWebhookUrl() {
            return alertWebhookUrl;
        }

        public void setAlertWebhookUrl(String alertWebhookUrl) {
            this.alertWebhookUrl = alertWebhookUrl;
        }

        public int getAlertTimeoutMs() {
            return alertTimeoutMs;
        }

        public void setAlertTimeoutMs(int alertTimeoutMs) {
            this.alertTimeoutMs = alertTimeoutMs;
        }

        /** 解析告警地址；空串按"未配置"处理（返回 {@code null}）。 */
        public String resolveAlertWebhookUrl() {
            if (alertWebhookUrl == null) {
                return null;
            }
            String v = alertWebhookUrl.trim();
            return v.isEmpty() ? null : v;
        }

        /** 启动期自检：超时必须为正数。 */
        public void validate() {
            if (alertTimeoutMs <= 0) {
                throw new IllegalArgumentException(
                        "hl7.dead-letter.alert-timeout-ms 必须为正数，收到 " + alertTimeoutMs);
            }
        }
    }

    /** 启动期自检：应用名 / 设施名沿用 DICOM AET 的 16 字符上限（MSH-3 / MSH-4 同规格）。 */
    public void validateIdentity() {
        requireAetLike(localApplication, "hl7.local-application");
        requireAetLike(localFacility, "hl7.local-facility");
        if (segmentMappings == null || segmentMappings.isBlank()) {
            throw new IllegalArgumentException("hl7.segment-mappings 不能为空");
        }
    }

    private static void requireAetLike(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        String v = value.trim();
        if (v.length() > 16) {
            throw new IllegalArgumentException(field + " 超长: '" + v + "'（" + v.length()
                    + " 字符），MSH-3/MSH-4 与 DICOM AET 同为 16 字符上限");
        }
    }
}