package com.crosshospital.imaging.service.wado;

import com.crosshospital.imaging.config.WadoConfigProps;
import com.crosshospital.imaging.exception.WadoRsParseException;
import org.dcm4che3.mime.MultipartInputStream;
import org.dcm4che3.mime.MultipartParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WADO-RS multipart/related 响应解析：逐 part 抽出 Content-Type + DICOM 二进制。
 *
 * <p><b>源主路径采用</b>：直接复用 dcm4che-mime 的 {@link MultipartParser} —— 它以
 * {@code "\r\n--" + boundary} 为分隔符做流式扫描，{@link MultipartParser#parse} 内部
 * ① {@code skipPreamble} 跳过首个 boundary 之前的响应头残留，② 循环读两字节判断
 * 「{@code --}」结束标记 / 「{@code CR LF}」part 分隔，③ 为每个 part 造一个
 * {@link MultipartInputStream} 并回调 {@code Handler.bodyPart(partNumber, in)}，
 * 回调返回后 {@code mis.skipAll()} 把该 part 剩余字节吃掉。part 内的头字段由
 * {@link MultipartInputStream#readHeaderParams()} 给出。
 *
 * <p>本类在源解析器之上补了三件本产品必需、而源工具不关心的事：
 * <ol>
 *   <li><b>前导校验</b>：每个 part 必须带 DICOM Part-10 前导（offset 128 处 magic = {@code "DICM"}），
 *       否则拒绝 —— 防止把网关的错误页 / 空对象当成影像写进本院 PACS；</li>
 *   <li><b>容量上限</b>：响应体与单 part 均受 {@code wado.max-multipart-bytes} 约束；</li>
 *   <li><b>失败不静默</b>：解析不出对象时抛 {@link WadoRsParseException} 而不是返回空列表，
 *       避免上游把「解析失败」误读成「该 study 无影像」。</li>
 * </ol>
 */
@Service
@Profile("!test")
public class MultipartDicomExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(MultipartDicomExtractor.class);

    /** DICOM Part-10 前导 magic 在字节流中的偏移。 */
    public static final int DICOM_MAGIC_OFFSET = 128;

    /** DICOM Part-10 前导 magic。 */
    public static final String DICOM_MAGIC = "DICM";

    private static final String MULTIPART_RELATED = "multipart/related";
    private static final String APPLICATION_DICOM = "application/dicom";
    private static final int READ_CHUNK = 64 * 1024;

    private final long maxMultipartBytes;

    public MultipartDicomExtractor(WadoConfigProps props) {
        this.maxMultipartBytes = props.getMaxMultipartBytes();
    }

    /**
     * 从 Content-Type 中取出 boundary 参数。
     *
     * <p>兼容 {@code boundary=abc} 与 {@code boundary="abc"} 两种写法（部分区域网关只在后者加引号）。
     */
    public String extractBoundary(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new WadoRsParseException(WadoRsParseException.ParseError.INVALID_CONTENT_TYPE,
                    "WADO-RS 响应缺少 Content-Type 头");
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (!lower.contains(MULTIPART_RELATED)) {
            throw new WadoRsParseException(WadoRsParseException.ParseError.INVALID_CONTENT_TYPE,
                    "WADO-RS 响应 Content-Type 非 multipart/related: " + contentType);
        }
        for (String segment : contentType.split(";")) {
            String s = segment.trim();
            if (!s.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                continue;
            }
            String value = s.substring("boundary=".length()).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isEmpty()) {
                return value;
            }
        }
        throw new WadoRsParseException(WadoRsParseException.ParseError.MISSING_BOUNDARY,
                "WADO-RS multipart/related 响应未声明 boundary: " + contentType);
    }

    /**
     * 解析响应体，返回按 part 顺序排列的 DICOM 对象。
     *
     * <p>非 multipart 的 {@code application/dicom} 单对象响应（区域网关在 study 仅含 1 个实例时的
     * 常见简写）被包成 1 个 part，与 multipart 路径返回同构结果，上层无需分支。
     */
    public List<DicomPart> extract(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            throw new WadoRsParseException(WadoRsParseException.ParseError.EMPTY_MULTIPART,
                    "WADO-RS 响应体为空");
        }
        if (body.length > maxMultipartBytes) {
            throw new WadoRsParseException(WadoRsParseException.ParseError.MULTIPART_TOO_LARGE,
                    "WADO-RS 响应体 " + body.length + " 字节超过上限 " + maxMultipartBytes);
        }

        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (lowerType.startsWith(APPLICATION_DICOM)) {
            ByteBuffer single = toByteBuffer(body, maxMultipartBytes);
            validateDicomPreamble(single, 1);
            return List.of(new DicomPart(1, APPLICATION_DICOM, single));
        }

        String boundary = extractBoundary(contentType);
        List<DicomPart> parts = new ArrayList<>();
        MultipartParser parser = new MultipartParser(boundary);
        try {
            parser.parse(new ByteArrayInputStream(body), (partNumber, in) -> {
                Map<String, List<String>> headers = in.readHeaderParams();
                String partContentType = headerValue(headers, "Content-Type");
                ByteBuffer partBytes = readPart(in, partNumber);
                parts.add(new DicomPart(partNumber,
                        partContentType == null ? APPLICATION_DICOM : partContentType,
                        partBytes));
            });
        } catch (EOFException e) {
            throw new WadoRsParseException(WadoRsParseException.ParseError.TRUNCATED_MULTIPART,
                    "multipart/related 在 part 结束前断流（boundary=" + boundary + "）", e);
        } catch (IOException e) {
            throw new WadoRsParseException(WadoRsParseException.ParseError.MALFORMED_MULTIPART,
                    "multipart/related 解析失败（boundary=" + boundary + "）: " + e.getMessage(), e);
        }

        if (parts.isEmpty()) {
            throw new WadoRsParseException(WadoRsParseException.ParseError.EMPTY_MULTIPART,
                    "multipart/related 未解析出任何 part（boundary=" + boundary + "）");
        }
        for (DicomPart part : parts) {
            validateDicomPreamble(part.bytes(), part.partNumber());
        }
        return parts;
    }

    /**
     * 校验 DICOM Part-10 前导：offset 128 处必须是 {@code "DICM"}。
     *
     * <p>外院网关偶尔在 multipart 里混入 DICOM JSON manifest 或空 part；本院 PACS 拒收这类字节，
     * 与其落到 task 15 的 C-STORE 阶段再失败，不如在解析阶段就拦下并给出可定位的 part 序号。
     */
    public void validateDicomPreamble(ByteBuffer bytes, int partNumber) {
        if (bytes.remaining() < DICOM_MAGIC_OFFSET + DICOM_MAGIC.length()) {
            throw new WadoRsParseException(WadoRsParseException.ParseError.INVALID_DICOM_PREAMBLE,
                    "part#" + partNumber + " 仅 " + bytes.remaining()
                            + " 字节，不足以容纳 DICOM Part-10 前导（需 >= 132 字节）");
        }
        byte[] magic = new byte[DICOM_MAGIC.length()];
        ByteBuffer view = bytes.duplicate();
        view.position(view.position() + DICOM_MAGIC_OFFSET);
        view.get(magic);
        if (!DICOM_MAGIC.equals(new String(magic, StandardCharsets.US_ASCII))) {
            throw new WadoRsParseException(WadoRsParseException.ParseError.INVALID_DICOM_PREAMBLE,
                    "part#" + partNumber + " 缺少 DICOM Part-10 前导：offset " + DICOM_MAGIC_OFFSET
                            + " 处应为 \"" + DICOM_MAGIC + "\"，实际为 0x"
                            + String.format("%02x%02x%02x%02x", magic[0], magic[1], magic[2], magic[3]));
        }
    }

    /** 读满一个 part 的体（读到 MultipartInputStream 在 boundary 处返回 -1 为止）。 */
    private ByteBuffer readPart(MultipartInputStream in, int partNumber) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(READ_CHUNK);
        byte[] chunk = new byte[READ_CHUNK];
        int n;
        while ((n = in.read(chunk, 0, chunk.length)) > 0) {
            if (buffer.size() + n > maxMultipartBytes) {
                // 不用 IOException：走 WadoRsParseException 才能保住 MULTIPART_TOO_LARGE 这个码，
                // 否则会被 extract() 的 catch (IOException) 归成 MALFORMED_MULTIPART。
                throw new WadoRsParseException(WadoRsParseException.ParseError.MULTIPART_TOO_LARGE,
                        "part#" + partNumber + " 超过 wado.max-multipart-bytes 上限 " + maxMultipartBytes);
            }
            buffer.write(chunk, 0, n);
        }
        return ByteBuffer.wrap(buffer.toByteArray());
    }

    private ByteBuffer toByteBuffer(byte[] body, long limit) {
        if (body.length > limit) {
            throw new WadoRsParseException(WadoRsParseException.ParseError.MULTIPART_TOO_LARGE,
                    "响应体超过上限 " + limit);
        }
        return ByteBuffer.wrap(body);
    }

    /** 大小写不敏感地取 part 头字段（不同网关对 header 名大小写处理不一致）。 */
    private String headerValue(Map<String, List<String>> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                List<String> values = e.getValue();
                if (values != null && !values.isEmpty()) {
                    return values.get(0);
                }
            }
        }
        return null;
    }

    /** 供上层日志引用：本次解析出的对象总数与总字节。 */
    public static String summarize(List<DicomPart> parts) {
        long total = parts.stream().mapToLong(DicomPart::size).sum();
        return parts.size() + " 个 DICOM 对象 / " + total + " 字节";
    }

    /** 结构化日志行：谁拉的、拉了什么、多少字节。 */
    public void logExtracted(String peerAet, String studyUid, List<DicomPart> parts) {
        LOG.info("WADO-RS multipart 解析完成 peerAet={} studyUid={} {}", peerAet, studyUid,
                summarize(parts));
    }
}