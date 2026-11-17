package com.crosshospital.imaging.service.routing;

import com.crosshospital.imaging.exception.HandshakeException;
import com.crosshospital.imaging.exception.RoutingFailureException;
import com.crosshospital.imaging.exception.RoutingFailureException.FailureCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FailureCodeRegistry} 分类与重试判定单测。
 *
 * <p>这张表是信息科排障的唯一词表，也是重试决策的唯一依据 ——
 * 码分错一次，运维就会被引到错误的科室去（把 AE 白名单问题报给网络组、
 * 把网络问题报给影像科）。所以本类逐个失败码断言三件事：码值、可重试性、
 * 处置建议非空。
 */
class FailureCodeRegistryTest {

    // ==================================================================
    // 词表本身
    // ==================================================================

    @Test
    @DisplayName("词表覆盖 task 16 要求的 9 个失败码，且码值即 DB 列取值")
    void registryCoversTheNineRequiredCodes() {
        Set<String> codes = new HashSet<>();
        for (FailureCodeRegistry code : FailureCodeRegistry.values()) {
            codes.add(code.code());
            assertAll(code.name(),
                    () -> assertFalse(code.code().isBlank(), "码值不能为空"),
                    () -> assertEquals(code.code(), code.code().toLowerCase(java.util.Locale.ROOT),
                            "DB 列取值必须是小写下划线"),
                    () -> assertNotNull(code.suggestedAction()),
                    () -> assertFalse(code.suggestedAction().isBlank(), "处置建议不能为空"));
        }
        for (String required : Arrays.asList(
                "connection_refused", "association_rejected", "query_timeout",
                "retrieve_failed", "store_failed", "partial_failure",
                "tls_mismatch", "transfer_syntax_empty", "sop_class_unsupported")) {
            assertTrue(codes.contains(required), "词表缺少 task 16 要求的失败码: " + required);
        }
    }

    @Test
    @DisplayName("可重试/不可重试的划分符合「时间会不会变好」判据")
    void retryabilityFollowsTransientVsConfigurationSplit() {
        assertAll(
                // 瞬态：重试有意义
                () -> assertTrue(FailureCodeRegistry.CONNECTION_REFUSED.retryable()),
                () -> assertTrue(FailureCodeRegistry.CONNECT_TIMEOUT.retryable()),
                () -> assertTrue(FailureCodeRegistry.QUERY_TIMEOUT.retryable()),
                () -> assertTrue(FailureCodeRegistry.QUERY_FAILED.retryable()),
                () -> assertTrue(FailureCodeRegistry.RETRIEVE_FAILED.retryable()),
                () -> assertTrue(FailureCodeRegistry.MOVE_TIMEOUT.retryable()),
                () -> assertTrue(FailureCodeRegistry.SUB_OP_FAILED.retryable()),
                () -> assertTrue(FailureCodeRegistry.PARTIAL_FAILURE.retryable()),
                () -> assertTrue(FailureCodeRegistry.STORE_FAILED.retryable()),
                () -> assertTrue(FailureCodeRegistry.STORE_TIMEOUT.retryable()),
                // 配置型：重试无意义
                () -> assertFalse(FailureCodeRegistry.ASSOCIATION_REJECTED.retryable()),
                () -> assertFalse(FailureCodeRegistry.TLS_MISMATCH.retryable()),
                () -> assertFalse(FailureCodeRegistry.TRANSFER_SYNTAX_EMPTY.retryable()),
                () -> assertFalse(FailureCodeRegistry.SOP_CLASS_UNSUPPORTED.retryable()),
                () -> assertFalse(FailureCodeRegistry.NO_STUDY_FOUND.retryable()),
                () -> assertFalse(FailureCodeRegistry.UNKNOWN.retryable()));
    }

    @Test
    @DisplayName("fromDbValue 双向可逆；未知值收敛到 UNKNOWN 而不是抛异常")
    void fromDbValueRoundTripsAndFallsBack() {
        for (FailureCodeRegistry code : FailureCodeRegistry.values()) {
            assertSame(code, FailureCodeRegistry.fromDbValue(code.code()),
                    "DB 值往返必须回到同一个码: " + code.code());
        }
        assertAll(
                () -> assertSame(FailureCodeRegistry.UNKNOWN, FailureCodeRegistry.fromDbValue(null)),
                () -> assertSame(FailureCodeRegistry.UNKNOWN, FailureCodeRegistry.fromDbValue("  ")),
                () -> assertSame(FailureCodeRegistry.UNKNOWN, FailureCodeRegistry.fromDbValue("不存在的码")),
                // 大小写容忍：运维从工单里手抄的值常带大小写混写
                () -> assertSame(FailureCodeRegistry.QUERY_TIMEOUT,
                        FailureCodeRegistry.fromDbValue("QUERY_TIMEOUT")));
    }

    // ==================================================================
    // 主路径异常 → 失败码
    // ==================================================================

    @Test
    @DisplayName("主路径 10 个 FailureCode 一一映射到词表同名项")
    void routingFailureCodesMapOneToOne() {
        for (FailureCode code : FailureCode.values()) {
            FailureCodeRegistry mapped = FailureCodeRegistry.of(code);
            assertAll(code.name(),
                    () -> assertNotSame(FailureCodeRegistry.UNKNOWN, mapped,
                            "主路径失败码必须有对应登记项: " + code),
                    () -> assertEquals(code.name().toLowerCase(java.util.Locale.ROOT), mapped.code()));
        }
        assertSame(FailureCodeRegistry.UNKNOWN, FailureCodeRegistry.of((FailureCode) null));
    }

    @Test
    @DisplayName("classify：cause 链里的 ConnectException 细化为 connection_refused")
    void connectExceptionInCauseChainBecomesConnectionRefused() {
        RoutingFailureException wrapped = new RoutingFailureException(
                FailureCode.ASSOCIATION_REJECTED, "关联建立失败", "REMOTE_PACS", "MPI-1",
                new ConnectException("Connection refused"));
        assertSame(FailureCodeRegistry.CONNECTION_REFUSED,
                FailureCodeRegistry.classify(wrapped));
    }

    @Test
    @DisplayName("classify：cause 链里的 SSL 异常细化为 tls_mismatch（而不是笼统的 association_rejected）")
    void sslExceptionInCauseChainBecomesTlsMismatch() {
        RoutingFailureException wrapped = new RoutingFailureException(
                FailureCode.ASSOCIATION_REJECTED, "TLS 协商失败", "REMOTE_PACS", "MPI-1",
                new SSLHandshakeException("no cipher suites in common"));
        assertSame(FailureCodeRegistry.TLS_MISMATCH, FailureCodeRegistry.classify(wrapped));
    }

    @Test
    @DisplayName("classify：嵌套两层的 ConnectException 也能找到（cause 链要往下走）")
    void nestedCauseChainIsWalked() {
        RoutingFailureException wrapped = new RoutingFailureException(
                FailureCode.ASSOCIATION_REJECTED, "关联建立失败", "REMOTE_PACS", "MPI-1",
                new java.io.IOException("association aborted",
                        new ConnectException("Connection refused")));
        assertSame(FailureCodeRegistry.CONNECTION_REFUSED,
                FailureCodeRegistry.classify(wrapped));
    }

    @Test
    @DisplayName("classify：自环 cause 链不导致死循环")
    void selfReferencingCauseChainTerminates() {
        Throwable looping = new ConnectException("Connection refused") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        RoutingFailureException wrapped = new RoutingFailureException(
                FailureCode.ASSOCIATION_REJECTED, "关联建立失败", "REMOTE_PACS", "MPI-1", looping);
        assertSame(FailureCodeRegistry.CONNECTION_REFUSED,
                FailureCodeRegistry.classify(wrapped));
    }

    @Test
    @DisplayName("classify：传输语法无交集从 association_rejected 里被拆回本码")
    void transferSyntaxEmptyIsSplitOutOfAssociationRejected() {
        RoutingFailureException e = new RoutingFailureException(
                FailureCode.ASSOCIATION_REJECTED,
                "传输语法无交集: peer=REMOTE_PACS peerTs=[]",
                "REMOTE_PACS", "MPI-1");
        assertSame(FailureCodeRegistry.TRANSFER_SYNTAX_EMPTY, FailureCodeRegistry.classify(e));
    }

    @Test
    @DisplayName("classify：SOP Class 未被接受被拆成 sop_class_unsupported")
    void unsupportedSopClassIsSplitOut() {
        RoutingFailureException e = new RoutingFailureException(
                FailureCode.ASSOCIATION_REJECTED,
                "关联内没有任何被接受的查询 SOP Class: 已协商=(空)",
                "REMOTE_PACS", "MPI-1");
        assertSame(FailureCodeRegistry.SOP_CLASS_UNSUPPORTED, FailureCodeRegistry.classify(e));
    }

    @Test
    @DisplayName("classify：既无精确 cause 也无特征消息时按 FailureCode 兜底，不臆造")
    void fallsBackToFailureCodeWhenNothingMoreSpecific() {
        RoutingFailureException e = new RoutingFailureException(
                FailureCode.ASSOCIATION_REJECTED,
                "外院 PACS 拒绝本院 AE Title: peer=REMOTE_PACS localAet=IMAGER",
                "REMOTE_PACS", "MPI-1");
        assertSame(FailureCodeRegistry.ASSOCIATION_REJECTED, FailureCodeRegistry.classify(e));
    }

    // ==================================================================
    // 握手层异常 → 失败码
    // ==================================================================

    @Test
    @DisplayName("classify：握手异常按其 FailureCode 直译；黑名单归 association_rejected")
    void handshakeExceptionsMapDirectly() {
        assertSame(FailureCodeRegistry.CONNECTION_REFUSED, FailureCodeRegistry.classify(
                new HandshakeException(HandshakeException.FailureCode.CONNECTION_REFUSED, "连不上")));
        assertSame(FailureCodeRegistry.CONNECT_TIMEOUT, FailureCodeRegistry.classify(
                new HandshakeException(HandshakeException.FailureCode.CONNECT_TIMEOUT, "超时")));
        // cipher 不匹配与 TLS 不匹配对运维是同一件事（都去改 TLS 配置），合并到一个码
        assertSame(FailureCodeRegistry.TLS_MISMATCH, FailureCodeRegistry.classify(
                new HandshakeException(HandshakeException.FailureCode.CIPHER_MISMATCH, "套件无交集")));
        assertSame(FailureCodeRegistry.TRANSFER_SYNTAX_EMPTY, FailureCodeRegistry.classify(
                new HandshakeException(HandshakeException.FailureCode.TRANSFER_SYNTAX_EMPTY, "无交集")));
        assertSame(FailureCodeRegistry.SOP_CLASS_UNSUPPORTED, FailureCodeRegistry.classify(
                new HandshakeException(HandshakeException.FailureCode.SOP_CLASS_UNSUPPORTED, "不支持")));
        assertSame(FailureCodeRegistry.ASSOCIATION_REJECTED, FailureCodeRegistry.classify(
                new HandshakeException(HandshakeException.FailureCode.BLACKLISTED, "命中黑名单")));
        assertSame(FailureCodeRegistry.ASSOCIATION_REJECTED, FailureCodeRegistry.classify(
                new HandshakeException(HandshakeException.FailureCode.PEER_NOT_FOUND, "准入表无此节点")));
    }

    @Test
    @DisplayName("classify：非路由异常（如调用方参数错误）收敛到 UNKNOWN，不臆造失败码")
    void unrelatedThrowableBecomesUnknown() {
        assertSame(FailureCodeRegistry.UNKNOWN,
                FailureCodeRegistry.classify(new IllegalArgumentException("patient_mpi 不能为空")));
        assertSame(FailureCodeRegistry.UNKNOWN, FailureCodeRegistry.classify(null));
    }
}