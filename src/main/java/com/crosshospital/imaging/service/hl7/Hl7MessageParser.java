package com.crosshospital.imaging.service.hl7;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.crosshospital.imaging.domain.Hl7ParsedMessage;
import com.crosshospital.imaging.exception.Hl7ParseException;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * HL7 v2.x 触发报文解析器：{@link MllpFrame} → {@link Hl7ParsedMessage}。
 *
 * <p><b>源主路径证据</b>：{@code github_ref/dcm4che/dcm4che-hl7/.../HL7Parser.java:55-213}
 * 的 SAX 风格解析——{@code parse(BufferedReader)} 按行读、识别 MSH/BHS/FHS 头（行 142-145）、
 * 从 MSH-2 抽 4 字符编码（{@code setDelimiters}，行 158-163）、用 {@code StringTokenizer}
 * 按动态分隔符切字段（行 181-188）。本服务复刻其中三条核心机制：
 *
 * <ol>
 *   <li><b>MSH 是头段</b>（{@code isHeaderSegment} 行 142-145）：第一个 {@code \r}-分隔的行
 *       必须以 MSH 开头，否则整条报文不可读；</li>
 *   <li><b>动态分隔符从 MSH-2 自适应</b>（{@code setDelimiters} 行 158-163）：MSH-2 的 4 字符
 *       依次是 component / repetition / escape / subcomponent 分隔符；
 *       本服务只读 component（取首字符作字段分隔符），其余 3 字符记录以备扩展；</li>
 *   <li><b>不支持就抛</b>（HL7Parser 对 segment &lt; 3 直接抛 IOException 行 92-93）：
 *       MSH &lt; 8 直接抛 {@link Hl7ParseException.FailureCode#INVALID_MSH}，不静默回退。</li>
 * </ol>
 *
 * <p><b>本服务与源产品的差异</b>（刻意）：
 *
 * <ul>
 *   <li>不做完整 SAX 事件回调——只抽白名单字段，详见 {@link Hl7SegmentFieldExtractor}；</li>
 *   <li>不做 escape / repetition 展开——业务字段（PID-3 / PID-5 等）不做 escape 替换，
 *       若业务层需要原始 escape 文本可调用 {@link #segmentsOf(MllpFrame, Charset)}；</li>
 *   <li>不支持 BHS / FHS（批量头 / 文件头）——本服务单条触发一条 fetch 任务，不接批量协议；</li>
 *   <li>段索引对齐：{@code segments[0]} 永远为段 ID（{@code MSH/PID/PV1/OBR/...}），
 *       {@code segments[N]} 对应「段名-N」字段。</li>
 * </ul>
 *
 * <p><b>MSH 特殊索引</b>：HL7 v2.x 把字段分隔符本身算作 MSH-1（{@code setDelimiters} 行 158-163），
 * 所以 MSH 段的索引表是：
 * <pre>
 *   segments[0] = "MSH"
 *   segments[1] = MSH-1 字段分隔符本身（{@code |}）
 *   segments[2] = MSH-2 编码字符（{@code ^~\&}）
 *   segments[3] = MSH-3 Sending Application
 *   segments[4] = MSH-4 Sending Facility
 *   segments[9] = MSH-9 Message Type
 *   segments[10] = MSH-10 Message Control ID
 * </pre>
 * 其他段（PID / PV1 / OBR 等）的索引则从 {@code segments[1]} 起为字段 1。
 */
@Component
@Profile("!test")
public class Hl7MessageParser {

    /** HL7 v2.x 段分隔符（CR）。 */
    public static final char SEGMENT_SEPARATOR = '\r';

    /** HL7 v2.x 头段识别（与 HL7Parser.java:142-145 一致）。本服务仅支持 MSH。 */
    private static final String HEADER_SEGMENT = "MSH";

    /** 本服务支持的消息类型白名单（routeKey 形式）。 */
    private static final Set<String> SUPPORTED_MESSAGE_TYPES = Set.of(
            "ORM^O01",
            "ADT^A04",
            "ADT^A08"
    );

    private final Hl7SegmentFieldExtractor extractor;

    public Hl7MessageParser(Hl7SegmentFieldExtractor extractor) {
        if (extractor == null) {
            throw new IllegalArgumentException("Hl7SegmentFieldExtractor 不能为 null");
        }
        this.extractor = extractor;
    }

    /**
     * 解析一条 MLLP 帧（含已去围栏的 HL7 v2.x payload）。
     *
     * @param frame    MLLP 守护送来的单条报文
     * @param charset  解码 charset（通常由 {@code hl7.trigger.charset} 决定）
     * @return 结构化触发消息
     * @throws Hl7ParseException MSH 缺失 / 编码字符异常 / 不支持的消息类型 / 缺 PID
     */
    public Hl7ParsedMessage parse(MllpFrame frame, Charset charset) {
        if (frame == null) {
            throw new IllegalArgumentException("MllpFrame 不能为 null");
        }

        // 复用 segmentsOf：它已经做了 MSH-1 分隔符抽取 + 整报文切分
        List<String[]> segments = segmentsOf(frame, charset);
        if (segments.isEmpty()) {
            throw new Hl7ParseException(Hl7ParseException.FailureCode.INVALID_MSH, "报文为空或仅含空白段");
        }

        String[] msh = segments.get(0);
        if (msh == null || !HEADER_SEGMENT.equals(msh[0])) {
            throw new Hl7ParseException(Hl7ParseException.FailureCode.INVALID_MSH,
                    "首个段不是 MSH（实际=" + (msh == null ? "null" : msh[0]) + "）");
        }
        // MSH-2（segments[2]）= 编码字符，必须长度为 4
        if (msh.length < 3 || msh[2] == null || msh[2].length() != 4) {
            throw new Hl7ParseException(Hl7ParseException.FailureCode.INVALID_DYNAMIC_DELIMITERS,
                    "MSH-2 编码字符缺失或长度 ≠ 4（实际长度="
                            + (msh.length < 3 || msh[2] == null ? -1 : msh[2].length()) + "）");
        }

        // MSH-9（messageType + "^" + triggerEvent）—— segments[9]
        if (msh.length < 10 || msh[9] == null || msh[9].isEmpty()) {
            throw new Hl7ParseException(Hl7ParseException.FailureCode.INVALID_MSH,
                    "MSH-9 消息类型缺失");
        }
        String[] typeAndEvent = splitAtFirst(msh[9], '^');
        String messageType = typeAndEvent[0];
        String triggerEvent = typeAndEvent.length > 1 ? typeAndEvent[1] : "";
        String routeKey = messageType + "^" + triggerEvent;
        if (!SUPPORTED_MESSAGE_TYPES.contains(routeKey)) {
            throw new Hl7ParseException(Hl7ParseException.FailureCode.UNSUPPORTED_MESSAGE_TYPE,
                    "routeKey=" + routeKey + "（本服务仅支持 " + SUPPORTED_MESSAGE_TYPES + "）");
        }

        // 业务字段提取（白名单）
        String patientMpi = extractor.extract(segments, "PID", 3);
        String patientName = extractor.extract(segments, "PID", 5);
        String patientDob = extractor.extract(segments, "PID", 7);
        String visitNumber = extractor.extract(segments, "PV1", 19);
        String accessionNumber = extractor.extract(segments, "OBR", 3);

        if (patientMpi == null || patientMpi.isBlank()) {
            throw new Hl7ParseException(Hl7ParseException.FailureCode.MISSING_PID,
                    "PID-3 Patient Identifier List 缺失或为空");
        }

        Hl7ParsedMessage.Builder b = Hl7ParsedMessage.builder()
                .messageType(messageType)
                .triggerEvent(triggerEvent)
                .messageControlId(safe(msh, 10))
                .sendingApplication(safe(msh, 3))
                .sendingFacility(safe(msh, 4))
                .patientMpi(patientMpi)
                .patientName(patientName)
                .patientDob(patientDob)
                .visitNumber(visitNumber)
                .accessionNumber(accessionNumber)
                .receivedAt(frame.receivedAt())
                .remoteAddress(frame.remoteAddress())
                .fieldSeparator(msh[1]);
        return b.build();
    }

    /**
     * 把已去围栏的 HL7 payload 按段切分为「段 ID + 字段数组」。
     *
     * <p>算法照搬源产品 {@code HL7Parser.java:84-139}：
     * <ol>
     *   <li>按 {@code \r} 分段（line 88）；</li>
     *   <li>MSH 段从第 8 字符开始按字段分隔符切（line 103-104），
     *       其余段从段头后开始（line 105-107）；</li>
     *   <li>尾随空段丢掉（line 89-91 continue）。</li>
     * </ol>
     *
     * <p>此函数假定调用方已确定分隔符（来自 MSH-1），与源产品把所有切分委托给
     * {@code StringTokenizer}（行 181-188）行为一致——只多走一次 MSH 取 sep。
     */
    public List<String[]> segmentsOf(MllpFrame frame, Charset charset) {
        Charset cs = charset == null ? java.nio.charset.StandardCharsets.UTF_8 : charset;
        byte[] payload = frame.payloadRef();
        String body = new String(payload, cs);
        char sep = detectSeparator(body);
        return splitBody(body, sep);
    }

    /**
     * 从 MSH-1 推断字段分隔符。若首段不是 MSH 或长度 < 4，抛 INVALID_MSH。
     */
    private static char detectSeparator(String body) {
        int firstCr = body.indexOf(SEGMENT_SEPARATOR);
        int end = firstCr < 0 ? body.length() : firstCr;
        if (end < 4 || !body.startsWith(HEADER_SEGMENT + "|")) {
            throw new Hl7ParseException(Hl7ParseException.FailureCode.INVALID_MSH,
                    "MSH 段长度 < 4 或首段不是 MSH");
        }
        return body.charAt(3);
    }

    /**
     * 把 body 按 {@code sep} 切段，并把每段切分为「段 ID + 字段数组」。
     */
    private List<String[]> splitBody(String body, char sep) {
        List<String[]> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == SEGMENT_SEPARATOR) {
                String line = body.substring(start, i);
                if (!line.isEmpty()) {
                    out.add(splitSegment(line, sep));
                }
                start = i + 1;
            }
        }
        if (start < body.length()) {
            String tail = body.substring(start);
            if (!tail.isEmpty()) {
                out.add(splitSegment(tail, sep));
            }
        }
        return out;
    }

    /**
     * 把单段切分为「段 ID + 字段数组」。
     * MSH 段从第 8 字符起算（前 3 字符是 MSH，第 4 字符是字段分隔符，第 5-8 字符是编码字符），
     * 其余段从段头后第 1 位字符起算。
     */
    private String[] splitSegment(String line, char sep) {
        if (line == null || line.length() < 3) {
            throw new Hl7ParseException(Hl7ParseException.FailureCode.INVALID_MSH,
                    "段长度 < 3：len=" + (line == null ? -1 : line.length()));
        }
        String segId = line.substring(0, 3);
        if (HEADER_SEGMENT.equals(segId)) {
            if (line.length() < 8) {
                throw new Hl7ParseException(Hl7ParseException.FailureCode.INVALID_MSH,
                        "MSH 段长度 < 8（line=" + line + "）");
            }
            // MSH 段：[0]=MSH, [1]=sep, [2]=encoding chars, [3..]=字段
            // 即 MSH-N (N≥1) 落在 segments[N]
            return splitMshSegment(segId, sep, line.substring(4));
        } else {
            // 其他段：整行按 sep 切分 → seg[0]=段 ID, seg[1..]=字段 1..N
            // 例如 "PID|1|12345^^^MRN|..." → ["PID", "1", "12345^^^MRN", ...]
            return splitLineBySeparator(line, sep);
        }
    }

    /**
     * MSH 段切分：把「MSH」+「sep + content」拆成 segments[0]=MSH, segments[1]=sep,
     * segments[2]=encodingChars, segments[3..]=后续字段。
     */
    private String[] splitMshSegment(String segId, char sep, String tail) {
        List<String> parts = new ArrayList<>();
        parts.add(segId);
        parts.add(String.valueOf(sep));
        int start = 0;
        for (int i = 0; i < tail.length(); i++) {
            if (tail.charAt(i) == sep) {
                parts.add(tail.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(tail.substring(start));
        return parts.toArray(new String[0]);
    }

    /**
     * 通用段切分：整行按 {@code sep} 切分 → segments[0]=段 ID（PID/OBR/...），
     * segments[N] = 第 N 字段（N≥1）。
     *
     * <p>保留空字段（连续分隔符 → 空字段），与 HL7 v2.x 标准一致。
     *
     * <p>输入 {@code "PID|1|12345^^^MRN|..."} → {@code ["PID", "1", "12345^^^MRN", ...]}，
     * 即 PID-1 = {@code seg[1]}、PID-3 = {@code seg[3]}。
     */
    private String[] splitLineBySeparator(String line, char sep) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == sep) {
                parts.add(line.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(line.substring(start));
        return parts.toArray(new String[0]);
    }

    /**
     * 安全取字段：若索引越界或值为 null，返回 {@code null}（不抛）。
     * 用于 MSH 段元数据（sending app/facility/messageControlId）——这些是建议项而非硬要求。
     */
    private static String safe(String[] arr, int idx) {
        if (arr.length <= idx) {
            return null;
        }
        String v = arr[idx];
        if (v == null || v.isEmpty()) {
            return null;
        }
        return v;
    }

    /**
     * 按 component 分隔符（{@code ^}，HL7 v2.x 默认）拆分，取前两段。
     * 输入 {@code ORM^O01} → {@code [ORM, O01]}；输入 {@code ORM} → {@code [ORM]}。
     */
    private static String[] splitAtFirst(String s, char sep) {
        int i = s.indexOf(sep);
        if (i < 0) {
            return new String[]{s};
        }
        return new String[]{s.substring(0, i), s.substring(i + 1)};
    }

    /** 测试 / 调试用：本服务当前支持的消息类型 routeKey 集合。 */
    public Set<String> supportedMessageTypes() {
        return SUPPORTED_MESSAGE_TYPES;
    }
}