package com.crosshospital.imaging.service.routing;

import com.crosshospital.imaging.exception.HandshakeException;
import com.crosshospital.imaging.exception.RoutingFailureException;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.Locale;

/**
 * 路由失败码总表 —— 「失败叫什么名字 / 该不该重试 / 运维该做什么」三件事的**唯一出处**。
 *
 * <p>为什么不让 task 13/14/15 的 {@link RoutingFailureException.FailureCode} 直接承担这件事：
 * 那套枚举描述的是**协议栈在哪一步抛的**，本表描述的是**这条故障该不该重试、要找谁**。
 * 同一个协议失败在业务上可能给出不同处置（外院 AE 白名单没登记 → 通知影像科改配置；
 * 外院 PACS 正在重启 → 队列重试即可）。两张表一一对应，但语义不混用。
 *
 * <h2>分层的判据</h2>
 * <pre>
 *   Throwable
 *     ├── HandshakeException（task 7-10 握手层，9 种）
 *     │      → 直接取其 FailureCode 语义
 *     └── RoutingFailureException（task 13-15 主路径，10 种）
 *            → 先看 cause 链做「细化」，细化不出来才按 FailureCode 兜底
 * </pre>
 *
 * <p><b>为什么要先看 cause 链</b>：dcm4che 把「TCP 连不上」与「对端回 A-ASSOCIATE-RJ」
 * 都以 {@code IOException} 抛出，协议层无法区分；只有顺着 cause 链找到
 * {@link ConnectException}（网络层拒绝）还是 {@link SSLException}（TLS 层配错），
 * 才能给出正确的重试判定。task 13 在代码注释里明确把「细分到 connection_refused」
 * 交给了本表，就是这个交接点。
 *
 * <h2>源主路径证据</h2>
 * <ul>
 *   <li>{@code github_ref/dcm4che-net/.../ConnectionMonitor.java:49-58} ——
 *       {@code onConnectionFailed(conn, remoteConn, socket, Throwable e)} 把**建连期**
 *       的失败连同原始 {@link Throwable} 一起交给回调；源产品据此区分
 *       「连不上」与「被拒」，本表把这条判据产品化成失败码 + 运维建议</li>
 *   <li>{@code Connection.java:1080-1089} ——
 *       {@code catch (GeneralSecurityException) / catch (IOException)} 后
 *       {@code monitor.onConnectionFailed(...)} 再 {@code throw e}：
 *       失败事件与异常**同一条信息流**，本表的 {@code classify(Throwable)}
 *       就是这条流的产品化落点</li>
 * </ul>
 */
public enum FailureCodeRegistry {

    // ---- 网络层：可重试（重试有意义）----
    CONNECTION_REFUSED("connection_refused", true,
            "连不上外院 PACS：核对 host/port 与网络策略，确认该节点服务已起；"
                    + "若是网络分区，等待恢复后队列会自动重放"),
    CONNECT_TIMEOUT("connect_timeout", true,
            "建连超时：外院 PACS 负载高或链路丢包，队列会退避重试；"
                    + "连续出现请让对方信息科查该院 PACS 的负载"),

    // ---- 协议层：重试无意义（必须改配置）----
    ASSOCIATION_REJECTED("association_rejected", false,
            "外院 PACS 拒绝本院 AE Title：请影像科在对方 PACS 的 AE 白名单里登记本节点"
                    + "（AET 见 dicom.local-aet），登记后重新发起调阅"),
    TLS_MISMATCH("tls_mismatch", false,
            "TLS 协议或密码套件不匹配：核对准入表 tls_mode 与对端要求；"
                    + "常见于对方换了证书后未同步 cipher 配置"),
    TRANSFER_SYNTAX_EMPTY("transfer_syntax_empty", false,
            "本端与外院支持的传输语法无交集：请让对方在 PACS 上开启"
                    + "Explicit VR Little Endian(1.2.840.10008.1.2.1) 后重新协商"),
    SOP_CLASS_UNSUPPORTED("sop_class_unsupported", false,
            "双方无共同的 SOP Class：核对准入表 present_contexts 与本端"
                    + "routing.query-retrieve-sop-classes；跨院调阅需对方开放"
                    + "Query/Retrieve 服务"),

    // ---- C-FIND 阶段 ----
    QUERY_TIMEOUT("query_timeout", true,
            "C-FIND 响应超时：外院 PACS 查询侧过慢或卡住，队列会退避重试；"
                    + "若反复超时请让对方查该院 PACS 的数据库负载"),
    QUERY_FAILED("query_failed", true,
            "C-FIND 阶段链路中断：连接在查询过程中被对端释放，队列会退避重试；"
                    + "连续出现请查外院 PACS 日志与中间网络设备"),
    NO_STUDY_FOUND("no_study_found", false,
            "外院确无该患者的影像：这不是故障。请与病人核对是否在外院做过检查、"
                    + "外院 MPI 是否一致；勿反复重试"),

    // ---- C-MOVE 阶段 ----
    RETRIEVE_FAILED("retrieve_failed", true,
            "C-MOVE 拉回失败：请核对外院 PACS 的 MoveDestination 是否登记了本服务节点"
                    + "（routing.local-move-dest-aet），以及其 104 端口是否放通"),
    MOVE_TIMEOUT("move_timeout", true,
            "C-MOVE 响应超时：大 study 拉回可能超过 routing.retrieve-timeout-ms；"
                    + "请让对方信息科调大该值或调低单次拉回实例数"),
    SUB_OP_FAILED("sub_op_failed", true,
            "C-MOVE 子操作失败：外院侧个别 instance 取回失败（权限/已归档/磁盘故障），"
                    + "请让对方查该 study 的存储状态"),
    PARTIAL_FAILURE("partial_failure", true,
            "C-MOVE 部分成功：部分 instance 拉回失败。**已按整次失败处理**"
                    + "（不把半份影像推进到 stored），重试前请让对方确认该 study 完整性"),

    // ---- C-STORE 阶段 ----
    STORE_FAILED("store_failed", true,
            "写入本院 PACS 失败：请核对 LOCAL_PACS_HOST/PORT/AET 三项配置，"
                    + "并让影像科确认本院 PACS 存储服务与剩余空间"),
    STORE_TIMEOUT("store_timeout", true,
            "写入本院 PACS 超时：本院 PACS 存储侧过慢或卡住；"
                    + "请影像科查 PACS 存储队列，重试不会丢数据"),

    // ---- 任务层：卡死（task 21 巡检产出）----
    STUCK_TASK_TIMEOUT("stuck_task_timeout", false,
            "任务停在查询阶段超过阈值仍未推进：巡检已把它退回 queued 重新排队；"
                    + "反复出现请让对方信息科查该院 PACS 的查询侧负载与中间网络链路"),

    // ---- 兜底 ----
    UNKNOWN("unknown", false,
            "未归类的失败：请按审计行的原始消息排查；"
                    + "若可复现，请把 task_id 与失败消息交给本服务维护方补充分类");

    private final String code;
    private final boolean retryable;
    private final String suggestedAction;

    FailureCodeRegistry(String code, boolean retryable, String suggestedAction) {
        this.code = code;
        this.retryable = retryable;
        this.suggestedAction = suggestedAction;
    }

    /** 写 {@code imaging_fetch_tasks.failure_code} / 审计表 {@code failure_code} 列的值。 */
    public String code() {
        return code;
    }

    /** 该失败码是否值得重试（配置型失败为 false —— 重试一万次也没用）。 */
    public boolean retryable() {
        return retryable;
    }

    /** 给信息科看的处置建议（一句话，直接落到「找谁改什么」）。 */
    public String suggestedAction() {
        return suggestedAction;
    }

    public static FailureCodeRegistry fromDbValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (FailureCodeRegistry code : values()) {
            if (code.code.equals(normalized)) {
                return code;
            }
        }
        return UNKNOWN;
    }

    /**
     * {@link RoutingFailureException.FailureCode} → 本表的一一兜底映射。
     *
     * <p>主路径 10 个失败码在本表里都有同名的登记项，故名同即映射；
     * 唯一需要特别处理的是 {@code ASSOCIATION_REJECTED} —— 它在协议层是个筐，
     * 但 cause 链里可能藏着更精确的原因（见 {@link #classify(Throwable)}）。
     */
    public static FailureCodeRegistry of(RoutingFailureException.FailureCode code) {
        if (code == null) {
            return UNKNOWN;
        }
        switch (code) {
            case ASSOCIATION_REJECTED: return ASSOCIATION_REJECTED;
            case QUERY_TIMEOUT:       return QUERY_TIMEOUT;
            case QUERY_FAILED:        return QUERY_FAILED;
            case NO_STUDY_FOUND:      return NO_STUDY_FOUND;
            case RETRIEVE_FAILED:     return RETRIEVE_FAILED;
            case MOVE_TIMEOUT:        return MOVE_TIMEOUT;
            case SUB_OP_FAILED:       return SUB_OP_FAILED;
            case PARTIAL_FAILURE:     return PARTIAL_FAILURE;
            case STORE_FAILED:        return STORE_FAILED;
            case STORE_TIMEOUT:       return STORE_TIMEOUT;
            default:                  return UNKNOWN;
        }
    }

    /** {@link HandshakeException.FailureCode}（握手层）→ 本表。 */
    public static FailureCodeRegistry of(HandshakeException.FailureCode code) {
        if (code == null) {
            return UNKNOWN;
        }
        switch (code) {
            case CONNECTION_REFUSED:       return CONNECTION_REFUSED;
            case CONNECT_TIMEOUT:          return CONNECT_TIMEOUT;
            case TLS_MISMATCH:             return TLS_MISMATCH;
            case CIPHER_MISMATCH:          return TLS_MISMATCH;
            case TRANSFER_SYNTAX_EMPTY:    return TRANSFER_SYNTAX_EMPTY;
            case SOP_CLASS_UNSUPPORTED:    return SOP_CLASS_UNSUPPORTED;
            case BLACKLISTED:              return ASSOCIATION_REJECTED;
            case PEER_NOT_FOUND:           return ASSOCIATION_REJECTED;
            case INCOMPATIBLE_CONNECTION:  return ASSOCIATION_REJECTED;
            default:                       return UNKNOWN;
        }
    }

    /**
     * 失败分类入口 —— 任何抛上来的异常都从这里取失败码。
     *
     * <p>判据优先级（先精确后笼统）：
     * <ol>
     *   <li>握手层 {@link HandshakeException} → 直接映射（它已经分好类了）</li>
     *   <li>主路径 {@link RoutingFailureException} → 先沿 cause 链细化，再按
     *       {@link RoutingFailureException.FailureCode} 兜底</li>
     *   <li>其它 → {@link #UNKNOWN}</li>
     * </ol>
     *
     * <p>细化的三条规则（顺序敏感，由具体到笼统）：
     * <ul>
     *   <li>cause 链里有 {@link ConnectException} / {@link NoRouteToHostException} /
     *       {@link UnknownHostException} → {@link #CONNECTION_REFUSED}
     *       —— 这是「网络层到不了」，重试有意义</li>
     *   <li>cause 链里有 {@link GeneralSecurityException} / {@link SSLException}
     *       → {@link #TLS_MISMATCH} —— 这是「证书/套件配错」，重试无意义</li>
     *   <li>消息含「传输语法无交集」→ {@link #TRANSFER_SYNTAX_EMPTY}；
     *       消息含「SOP Class」且含「未被接受 / 无任何」→
     *       {@link #SOP_CLASS_UNSUPPORTED} —— 这两条配置型失败在协议层都被并进
     *       {@code ASSOCIATION_REJECTED}，靠消息文本把它们拆回来</li>
     * </ul>
     */
    public static FailureCodeRegistry classify(Throwable failure) {
        if (failure == null) {
            return UNKNOWN;
        }
        if (failure instanceof HandshakeException) {
            return of(((HandshakeException) failure).getFailureCode());
        }
        if (failure instanceof RoutingFailureException) {
            RoutingFailureException routing = (RoutingFailureException) failure;
            FailureCodeRegistry refined = refine(routing);
            return refined != null ? refined : of(routing.getFailureCode());
        }
        return UNKNOWN;
    }

    /**
     * 沿 cause 链做协议层「细化」；判不出更精确的码返回 {@code null}（交由调用方兜底）。
     *
     * <p>cause 链深度封顶 8 层：真实链路里没有更深的花样，链太长反而说明有环。
     */
    private static FailureCodeRegistry refine(RoutingFailureException routing) {
        Throwable t = routing;
        for (int depth = 0; t != null && depth < 8; depth++) {
            if (t instanceof ConnectException
                    || t instanceof NoRouteToHostException
                    || t instanceof PortUnreachableException
                    || t instanceof UnknownHostException) {
                return CONNECTION_REFUSED;
            }
            if (t instanceof SSLException || t instanceof GeneralSecurityException) {
                return TLS_MISMATCH;
            }
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }

        String message = routing.getMessage() == null
                ? "" : routing.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("传输语法无交集")) {
            return TRANSFER_SYNTAX_EMPTY;
        }
        if (message.contains("sop class")
                && (message.contains("没有任何被接受")
                    || message.contains("未协商到")
                    || message.contains("未接受"))) {
            return SOP_CLASS_UNSUPPORTED;
        }
        return null;
    }
}