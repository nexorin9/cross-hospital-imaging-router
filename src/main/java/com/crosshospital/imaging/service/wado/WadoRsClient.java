package com.crosshospital.imaging.service.wado;

import com.crosshospital.imaging.config.WadoConfigProps;
import com.crosshospital.imaging.exception.ValidationException;
import com.crosshospital.imaging.exception.WadoRsHttpException;
import com.crosshospital.imaging.exception.WadoRsParseException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * WADO-RS HTTP 拉取客户端（外院 / 区域 PACS 检索网关 → 本服务缓存）。
 *
 * <p><b>源主路径采用</b>：dcm4che 的 {@code wadors} 命令行工具是「WADO-RS GET → 校验响应码 →
 * multipart/related 逐 part 抽 DICOM」这三步串联。本类把同一串动作固化为可复用的服务方法
 * {@link #fetchStudy(String, String)}，其中 multipart 解析委派给
 * {@link MultipartDicomExtractor}（直接复用 dcm4che-mime {@code MultipartParser}）。
 * 与源工具的差别在于：源工具面向运维命令行一次性跑完，本类是长驻服务的一部分 —— 每次拉取都要带上
 * 调用人（peerAet）与 studyUid 以便落审计、重试要能被 task 12 的策略接管。
 *
 * <p><b>状态码语义</b>（DICOMweb PS3.18 §8.6.3）：
 * <ul>
 *   <li>{@code 200} —— 整 study 拉全</li>
 *   <li>{@code 206} —— 网关只给了部分对象；解析照常，但 {@code partial=true} 显式带出</li>
 *   <li>{@code 416} —— 网关无法为该请求提供对象；按不可重试失败抛出，**不**返回空列表</li>
 *   <li>{@code 404} —— study 不存在 / 患者标识对不上</li>
 *   <li>5xx / 传输异常 —— 可重试</li>
 * </ul>
 */
@Service
@Profile("!test")
public class WadoRsClient {

    private static final Logger LOG = LoggerFactory.getLogger(WadoRsClient.class);

    /** DICOM UID 形态：数字段以点分隔。用于挡掉路径穿越与拼接错误。 */
    private static final Pattern STUDY_UID_PATTERN = Pattern.compile("^[0-9]+(\\.[0-9]+)+$");

    private static final int READ_CHUNK = 64 * 1024;

    private final WadoConfigProps props;
    private final MultipartDicomExtractor extractor;
    private final WadoRsRetryPolicy retryPolicy;
    private final WadoCapacityMonitor capacityMonitor;
    private final WadoRsAuditWriter auditWriter;

    /** OkHttpClient 线程安全，构建一次即可；字段可见性用 volatile 保证懒加载安全。 */
    private volatile OkHttpClient httpClient;

    /**
     * 兼容旧调用方（task 11 测试）。不启用重试 + 不落审计 + 不打指标。
     */
    public WadoRsClient(WadoConfigProps props, MultipartDicomExtractor extractor) {
        this(props, extractor, null, null, null);
    }

    /**
     * 完整构造（task 12 启用重试 + 容量监控 + 失败审计）—— <b>Spring 装配入口</b>。
     *
     * <p>三个依赖可为 {@code null}，对应 unit-of-work 单测场景（{@code null} 表示 no-op）——
     * {@link #doFetchOnce} 与 {@link #fetchStudyDetailed} 在 null 时跳过对应副作用。
     *
     * <p><b>必须标 {@code @Autowired}</b>：本类有两个公开构造器（另一个供 task 11 的旧调用方），
     * Spring 在"多构造器且无默认构造器"时无法自行选择 —— 不标就会退化成
     * 「找默认构造器 → 找不到 → {@code No default constructor found}」，<b>整个容器起不来</b>。
     */
    @Autowired
    public WadoRsClient(WadoConfigProps props, MultipartDicomExtractor extractor,
                        WadoRsRetryPolicy retryPolicy, WadoCapacityMonitor capacityMonitor,
                        WadoRsAuditWriter auditWriter) {
        this.props = props;
        this.extractor = extractor;
        this.retryPolicy = retryPolicy;
        this.capacityMonitor = capacityMonitor;
        this.auditWriter = auditWriter;
    }

    /**
     * 拉取一个 study 的全部 DICOM 对象（multipart/related 解析后的实例字节流）。
     *
     * <p>task 12 起：自动按 {@link WadoRsRetryPolicy} 重试；重试耗尽后抛出
     * {@code retriesExhausted=true} 的 {@link WadoRsHttpException}。
     *
     * @param peerAet  外院节点 AET，仅用于日志与审计串联
     * @param studyUid StudyInstanceUID
     * @throws ValidationException    studyUid 形态非法（**未发出任何请求**）
     * @throws WadoRsHttpException    传输失败 / 状态码不可接受（含重试耗尽）
     * @throws WadoRsParseException   响应体不是合法 multipart/related 或 part 缺 DICOM 前导
     */
    public List<ByteBuffer> fetchStudy(String peerAet, String studyUid) {
        return fetchStudyDetailed(peerAet, studyUid).instances();
    }

    /**
     * 同 {@link #fetchStudy}，但带回 HTTP 状态码与 partial 标记。
     *
     * <p>task 12：包了重试循环、容量监控、失败审计三层包裹；底层单次拉取由
     * {@link #doFetchOnce(String, String)} 完成。
     */
    public WadoRsFetchResult fetchStudyDetailed(String peerAet, String studyUid) {
        long start = System.currentTimeMillis();
        Throwable lastFailure = null;
        int attempt = 0;
        int maxAttempts = retryPolicy == null ? 0 : retryPolicy.maxRetries();

        while (true) {
            attempt++;
            try {
                WadoRsFetchResult result = doFetchOnce(peerAet, studyUid);
                long elapsed = System.currentTimeMillis() - start;
                if (capacityMonitor != null) {
                    capacityMonitor.recordSuccess(result.totalBytes(), result.instanceCount(), elapsed);
                }
                return result;
            } catch (RuntimeException e) {
                lastFailure = e;
                if (capacityMonitor != null) {
                    classifyAndRecord(e);
                }

                // 不可重试 → 直接抛出
                if (retryPolicy == null || !retryPolicy.isRetryable(e)) {
                    long elapsed = System.currentTimeMillis() - start;
                    if (auditWriter != null) {
                        auditWriter.recordFailure(peerAet, studyUid, e, attempt, "system", null, elapsed);
                    }
                    throw e;
                }
                // 已达上限 → 抛出 retriesExhausted 异常
                if (attempt > maxAttempts) {
                    long elapsed = System.currentTimeMillis() - start;
                    WadoRsHttpException exhausted = retryPolicy.exhausted(e, attempt);
                    if (auditWriter != null) {
                        auditWriter.recordFailure(peerAet, studyUid, exhausted, attempt, "system", null, elapsed);
                    }
                    throw exhausted;
                }

                // 退避 + 下一轮
                long backoffMs = retryPolicy.computeBackoffMs(attempt);
                if (capacityMonitor != null) {
                    capacityMonitor.recordRetry();
                }
                retryPolicy.logRetry(attempt + 1, backoffMs, e);
                try {
                    retryPolicy.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    long elapsed = System.currentTimeMillis() - start;
                    if (auditWriter != null) {
                        auditWriter.recordFailure(peerAet, studyUid, ie, attempt, "system", null, elapsed);
                    }
                    throw new WadoRsHttpException(WadoRsHttpException.STATUS_TRANSPORT_FAILURE,
                            "WADO-RS 重试退避被中断", false, WadoRsHttpException.Reason.TRANSPORT_FAILURE, ie);
                }
            }
        }
    }

    /**
     * 单次拉取（task 11 已实现的 HTTP + multipart 解析；task 12 把它从重试循环中独立出来，
     * 便于 unit-of-work 单测和重试循环复用同一段实现）。
     */
    private WadoRsFetchResult doFetchOnce(String peerAet, String studyUid) {
        String url = buildUrl(peerAet, studyUid);
        Request request = buildRequest(url);

        try (Response response = client().newCall(request).execute()) {
            int status = response.code();
            String contentType = response.header("Content-Type");

            if (status == 404) {
                throw new WadoRsHttpException(404, WadoRsHttpException.snippet(readErrorBody(response)),
                        false, WadoRsHttpException.Reason.STUDY_NOT_FOUND);
            }
            if (status == 416) {
                throw new WadoRsHttpException(416, WadoRsHttpException.snippet(readErrorBody(response)),
                        false, WadoRsHttpException.Reason.RANGE_NOT_SATISFIABLE);
            }
            if (status != 200 && status != 206) {
                boolean retryable = status >= 500;
                throw new WadoRsHttpException(status, WadoRsHttpException.snippet(readErrorBody(response)),
                        retryable, retryable ? WadoRsHttpException.Reason.SERVER_ERROR
                                : WadoRsHttpException.Reason.CLIENT_ERROR);
            }

            byte[] body = readBounded(response.body());
            List<DicomPart> parts = extractor.extract(body, contentType);
            extractor.logExtracted(peerAet, studyUid, parts);

            List<ByteBuffer> instances = parts.stream().map(DicomPart::bytes).toList();
            long total = parts.stream().mapToLong(DicomPart::size).sum();
            if (status == 206) {
                LOG.warn("WADO-RS 返回部分内容 peerAet={} studyUid={} 仅取到 {}/未知 实例（{} 字节），"
                                + "上层不得当作整 study 已拉全", peerAet, studyUid, parts.size(), total);
            }
            return new WadoRsFetchResult(peerAet, studyUid, status, status == 206, instances, total);
        } catch (IOException e) {
            throw WadoRsHttpException.transportFailure(
                    "WADO-RS 请求失败 url=" + url + " cause=" + e.getClass().getSimpleName()
                            + ": " + e.getMessage(), e);
        }
    }

    /** 按异常类型分别打到 http / parse / unknown 计数器。 */
    private void classifyAndRecord(Throwable t) {
        if (capacityMonitor == null) return;
        if (t instanceof WadoRsHttpException http) {
            capacityMonitor.recordHttpFailure(http.getReason());
        } else if (t instanceof WadoRsParseException parse) {
            capacityMonitor.recordParseFailure(parse.getParseError());
        } else {
            capacityMonitor.recordUnknownFailure(t);
        }
    }

    /** 组装 URL：模板占位替换 + UID 形态校验 + 百分号编码。 */
    String buildUrl(String peerAet, String studyUid) {
        if (studyUid == null || !STUDY_UID_PATTERN.matcher(studyUid).matches()) {
            throw new ValidationException("studyUid 形态非法（应为数字点分 DICOM UID）：" + studyUid);
        }
        String template = props.getBaseUrlTemplate();
        if (template == null || template.isBlank()) {
            throw new ValidationException("wado.base-url-template 未配置");
        }
        String url;
        if (template.contains("{studyUid}")) {
            url = template.replace("{studyUid}", encode(studyUid));
        } else {
            url = template + (template.endsWith("/") ? "" : "/") + encode(studyUid);
        }
        if (url.contains("{peerAet}")) {
            url = url.replace("{peerAet}", encode(peerAet == null ? "" : peerAet));
        }
        return url;
    }

    private Request buildRequest(String url) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .get()
                // DICOMweb：study 级别 retrieve 必须用 multipart/related
                .header("Accept", "multipart/related; type=\"application/dicom\"")
                .header("User-Agent", props.getUserAgent());
        String token = props.getBearerToken();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    /** 懒加载 OkHttpClient：超时来自 wado.connect-timeout-ms / wado.read-timeout-ms。 */
    OkHttpClient client() {
        OkHttpClient local = httpClient;
        if (local == null) {
            synchronized (this) {
                local = httpClient;
                if (local == null) {
                    local = new OkHttpClient.Builder()
                            .connectTimeout(props.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                            .readTimeout(props.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                            .writeTimeout(props.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                            .build();
                    httpClient = local;
                }
            }
        }
        return local;
    }

    /** 有上限地读满响应体：超过 max-multipart-bytes 立刻中断，不让异常网关打满堆。 */
    private byte[] readBounded(ResponseBody body) throws IOException {
        if (body == null) {
            return new byte[0];
        }
        long limit = props.getMaxMultipartBytes();
        long declared = body.contentLength();
        if (declared > limit) {
            throw new WadoRsParseException(WadoRsParseException.ParseError.MULTIPART_TOO_LARGE,
                    "响应体 Content-Length=" + declared + " 超过 wado.max-multipart-bytes 上限 " + limit);
        }
        try (InputStream in = body.byteStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(READ_CHUNK);
            byte[] chunk = new byte[READ_CHUNK];
            int n;
            while ((n = in.read(chunk, 0, chunk.length)) > 0) {
                if (out.size() + n > limit) {
                    throw new WadoRsParseException(WadoRsParseException.ParseError.MULTIPART_TOO_LARGE,
                            "响应体超过 wado.max-multipart-bytes 上限 " + limit + " 字节，已中断读取");
                }
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** 失败响应体读一小段即可（避免为一条错误信息拉 5GB）。 */
    private byte[] readErrorBody(Response response) {
        ResponseBody body = response.body();
        if (body == null) {
            return new byte[0];
        }
        try (InputStream in = body.byteStream()) {
            byte[] snippet = new byte[WadoRsHttpException.MAX_BODY_SNIPPET];
            int n = in.readNBytes(snippet, 0, snippet.length);
            return n <= 0 ? new byte[0] : java.util.Arrays.copyOf(snippet, n);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}