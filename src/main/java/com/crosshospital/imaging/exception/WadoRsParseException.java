package com.crosshospital.imaging.exception;

/**
 * WADO-RS 响应体 multipart/related 解析失败。
 *
 * <p>对应源仓库 dcm4che-mime {@code MultipartParser.parse} 的三条失败出口：
 * preamble/boundary 定位失败（{@code missingBoundary}）、流中途断流（{@code EOFException} →
 * {@link ParseError#TRUNCATED_MULTIPART}）、以及本产品新增的业务校验
 * （{@link ParseError#INVALID_DICOM_PREAMBLE}、{@link ParseError#MULTIPART_TOO_LARGE}）。
 *
 * <p>解析失败**不会**静默返回空列表 —— 空列表会被上游当成「该 study 本来就没有影像」，
 * 从而推进错误的业务状态，因此一律抛异常交由 task 16 的失败码分类处理。
 */
public class WadoRsParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum ParseError {
        /** Content-Type 不是 multipart/related、也不是 application/dicom。 */
        INVALID_CONTENT_TYPE,
        /** multipart/related 但响应头没带 boundary 参数。 */
        MISSING_BOUNDARY,
        /** 解析出 0 个 part。 */
        EMPTY_MULTIPART,
        /** 流在 part 结束前断掉（对应 MultipartParser 的 EOFException）。 */
        TRUNCATED_MULTIPART,
        /** 结构不合法：boundary 后既不是 CRLF 也不是结束标记。 */
        MALFORMED_MULTIPART,
        /** 某个 part 的字节流没有 DICOM Part-10 前导（offset 128 处应为 "DICM"）。 */
        INVALID_DICOM_PREAMBLE,
        /** 响应体超过 wado.max-multipart-bytes 上限。 */
        MULTIPART_TOO_LARGE
    }

    private final ParseError parseError;

    public WadoRsParseException(ParseError parseError, String message) {
        this(parseError, message, null);
    }

    public WadoRsParseException(ParseError parseError, String message, Throwable cause) {
        super(message, cause);
        this.parseError = parseError;
    }

    public ParseError getParseError() { return parseError; }
}