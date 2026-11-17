package com.crosshospital.imaging.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 错误码 → 异常类型 / HTTP 状态码 的唯一映射表（task 38）。
 *
 * <p>为什么不让每个异常类自己带 httpStatus：状态码是<b>对外契约</b>，
 * 散在十几个异常类里就没人能一眼核对「哪些是 502、哪些是 4xx」。
 * 这里把 18 个码摆成一张表，测试直接遍历断言，改一个码的影响面一目了然。
 *
 * <p>{@link #classify(Throwable)} 是给现存异常类（task 7–27 已经落地的
 * {@code HandshakeException / WadoRsHttpException / ...}）留的桥：
 * 它们没有继承新的三层基类，按类名归到同一套错误码语义上，
 * 不需要为了分层去改写十几个已经跑绿的类的构造签名。
 */
public final class ErrorCodeRegistry {

    private ErrorCodeRegistry() {
    }

    /** 异常类型分层映射。 */
    public static Class<? extends RuntimeException> exceptionType(ErrorCode code) {
        if (code == null) {
            return InfrastructureException.class;
        }
        return switch (code.layer()) {
            case DOMAIN -> DomainException.class;
            case INFRASTRUCTURE -> InfrastructureException.class;
            case EXTERNAL_SYSTEM -> ExternalSystemException.class;
        };
    }

    /** HTTP 状态码（int，便于与 MockMvc 的 getStatus() 直接比）。 */
    public static int httpStatus(ErrorCode code) {
        return code == null ? 500 : code.httpStatus();
    }

    /** 按错误码构造对应层的异常实例（供统一异常处理器之外的地方复用）。 */
    public static RuntimeException newException(ErrorCode code, String message) {
        return switch (code.layer()) {
            case DOMAIN -> new DomainException(code, message);
            case INFRASTRUCTURE -> new InfrastructureException(code, message);
            case EXTERNAL_SYSTEM -> new ExternalSystemException(code, message);
        };
    }

    /** code → ErrorCode 全表，按枚举声明顺序。 */
    public static Map<String, ErrorCode> all() {
        Map<String, ErrorCode> map = new LinkedHashMap<>();
        for (ErrorCode value : ErrorCode.values()) {
            ErrorCode previous = map.put(value.code(), value);
            if (previous != null) {
                throw new IllegalStateException("错误码重复：" + value.code());
            }
        }
        return map;
    }

    /**
     * 现存异常 → 统一错误码的桥接（best-effort，认不出来返回 null）。
     *
     * <p>只在统一异常处理器的兜底分支用：主路径异常各有自己的专用 handler，
     * 这里不影响它们的行为。
     */
    public static ErrorCode classify(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        if (throwable instanceof DomainException de) {
            return de.errorCode();
        }
        if (throwable instanceof InfrastructureException ie) {
            return ie.errorCode();
        }
        if (throwable instanceof ExternalSystemException ee) {
            return ee.errorCode();
        }
        String name = throwable.getClass().getSimpleName();
        return switch (name) {
            case "HandshakeException", "WadoRsHttpException", "ModelUnreachableException" ->
                    ErrorCode.CONNECTION_REFUSED;
            case "ModelTimeoutException" -> ErrorCode.MODEL_TIMEOUT;
            case "ModelResponseParseException", "WadoRsParseException" ->
                    ErrorCode.MODEL_PARSE_ERROR;
            case "InvalidMllpFrameException", "Hl7ParseException" ->
                    ErrorCode.INVALID_HL7_FRAME;
            case "ConflictException", "IllegalTaskTransitionException" ->
                    ErrorCode.IDEM_CONFLICT;
            // ValidationException / NotFoundException 有各自的 400 / 404 handler，
            // 不硬塞进本表，避免把「参数写错」报成「HL7 帧非法」
            default -> null;
        };
    }

    /** 自检：18 个码、名字唯一、HTTP 状态都在 4xx/5xx、每层至少有一个码。 */
    public static Set<String> selfCheck() {
        Map<String, ErrorCode> map = all();
        Set<String> problems = new TreeSet<>();
        if (map.size() != 18) {
            problems.add("错误码数量应为 18，实际 " + map.size());
        }
        for (ErrorCode code : map.values()) {
            if (code.httpStatus() < 400 || code.httpStatus() > 599) {
                problems.add(code.code() + " 的 HTTP 状态不是 4xx/5xx");
            }
            if (code.suggestedAction() == null || code.suggestedAction().isBlank()) {
                problems.add(code.code() + " 缺处置建议");
            }
        }
        return problems;
    }
}
