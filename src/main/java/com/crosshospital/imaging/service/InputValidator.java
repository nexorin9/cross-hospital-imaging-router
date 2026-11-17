package com.crosshospital.imaging.service;

import com.crosshospital.imaging.exception.ValidationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 业务层字段语义校验器。
 *
 * <p>承担 {@code @NotBlank/@Min/@Max} 之外的非平凡语义校验：
 * DICOM 标准 UID/AE Title 合规性、JSON 数组唯一性、host/port 基本可用性。
 * DTO 上的 {@code @Pattern/@Valid} 由 spring-boot-starter-validation
 * 在 controller 边界自动触发，本类补充业务层必须把关的硬约束。
 *
 * <p>源主路径：dcm4che ApplicationEntity.setApplicationName 对 AE Title
 * 的 16 字符大写字母限制 + ISO/IEC 8859-1 范围检查。本类保留该约束，
 * 把它移到入参校验，避免无意义请求穿透到 dcm4che Device 层。
 */
@Component
public class InputValidator {

    /** DICOM AE Title 长度上限（与 V1 CHECK 约束一致）。 */
    public static final int AET_MAX_LEN = 16;

    /** DICOM AE Title 字符集：16 字符以内，仅大写字母 / 数字 / 部分特殊字符。 */
    private static final Pattern AET_PATTERN = Pattern.compile("^[A-Z0-9_\\-]{1,16}$");

    /** DICOM SOP Class UID 格式：以数字结尾，前缀形如 1.2.840.10008.x。 */
    private static final Pattern UID_PATTERN = Pattern.compile("^[0-9]+(?:\\.[0-9]+)+$");

    /** TLS 模式白名单。 */
    private static final Set<String> TLS_MODES = Set.of("none", "start_tls", "tls");

    /** 主机名校验：宽松（域名或 IPv4），仅排除空白。 */
    private static final Pattern HOST_PATTERN = Pattern.compile("^[A-Za-z0-9._\\-:]{1,253}$");

    public void isValidAet(String aet) {
        if (aet == null || aet.isBlank()) {
            throw new ValidationException("AET must not be blank");
        }
        if (aet.length() > AET_MAX_LEN) {
            throw new ValidationException(
                    "AET exceeds " + AET_MAX_LEN + " chars: " + aet);
        }
        if (!AET_PATTERN.matcher(aet).matches()) {
            throw new ValidationException(
                    "AET contains illegal characters (allowed: A-Z, 0-9, _, -): " + aet);
        }
    }

    public void isValidHost(String host) {
        if (host == null || host.isBlank()) {
            throw new ValidationException("host must not be blank");
        }
        if (!HOST_PATTERN.matcher(host).matches()) {
            throw new ValidationException("host contains illegal characters: " + host);
        }
    }

    public void isValidPort(Integer port) {
        if (port == null) {
            throw new ValidationException("port must not be null");
        }
        if (port < 1 || port > 65535) {
            throw new ValidationException("port out of range 1..65535: " + port);
        }
    }

    public void isValidTlsMode(String tlsMode) {
        if (tlsMode == null) {
            throw new ValidationException("tls_mode must not be null");
        }
        if (!TLS_MODES.contains(tlsMode)) {
            throw new ValidationException(
                    "tls_mode must be one of " + TLS_MODES + ", got: " + tlsMode);
        }
    }

    public void isValidSopClass(String sopClassUid) {
        if (sopClassUid == null || sopClassUid.isBlank()) {
            throw new ValidationException("SOP Class UID must not be blank");
        }
        if (!UID_PATTERN.matcher(sopClassUid).matches()) {
            throw new ValidationException(
                    "SOP Class UID must be dotted-numeric (DICOM standard): " + sopClassUid);
        }
    }

    public void isValidTransferSyntax(String tsUid) {
        if (tsUid == null || tsUid.isBlank()) {
            throw new ValidationException("Transfer Syntax UID must not be blank");
        }
        if (!UID_PATTERN.matcher(tsUid).matches()) {
            throw new ValidationException(
                    "Transfer Syntax UID must be dotted-numeric (DICOM standard): " + tsUid);
        }
    }

    /** 校验数组中每个 UID 都是合法的 SOP Class UID；数组内不允许重复。 */
    public void validateSopClasses(List<String> sopClasses) {
        if (sopClasses == null) {
            return;
        }
        for (String s : sopClasses) {
            isValidSopClass(s);
        }
        long distinct = sopClasses.stream().distinct().count();
        if (distinct != sopClasses.size()) {
            throw new ValidationException(
                    "present_contexts contains duplicates: " + sopClasses);
        }
    }

    /** 校验数组中每个 UID 都是合法的 Transfer Syntax UID；数组内不允许重复。 */
    public void validateTransferSyntaxes(List<String> transferSyntaxes) {
        if (transferSyntaxes == null) {
            return;
        }
        for (String s : transferSyntaxes) {
            isValidTransferSyntax(s);
        }
        long distinct = transferSyntaxes.stream().distinct().count();
        if (distinct != transferSyntaxes.size()) {
            throw new ValidationException(
                    "transfer_syntaxes contains duplicates: " + transferSyntaxes);
        }
    }

    /**
     * 黑名单 AET 数组校验：每个元素都是合法 AET，且不能包含本节点自身的 AET
     * （自身被自身屏蔽没有意义，应由外院节点的 AET 列表组成）。
     */
    public void validateBlacklistAets(List<String> blacklist, String selfAet) {
        if (blacklist == null || blacklist.isEmpty()) {
            return;
        }
        for (String aet : blacklist) {
            isValidAet(aet);
            if (aet.equals(selfAet)) {
                throw new ValidationException(
                        "blacklist_aets must not contain the node's own AET: " + selfAet);
            }
        }
        long distinct = blacklist.stream().distinct().count();
        if (distinct != blacklist.size()) {
            throw new ValidationException(
                    "blacklist_aets contains duplicates: " + blacklist);
        }
    }
}