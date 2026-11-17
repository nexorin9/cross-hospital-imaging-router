package com.crosshospital.imaging.service.routing;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.repository.RouteConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 外院节点定位：把一条 HL7 触发报文映射到「该去哪家外院拉片子」。
 *
 * <p><b>为什么需要它</b>：HIS 触发报文里带的是患者主索引（PID-3）与影像引用（OBR-3），
 * <b>不带</b>外院的 DICOM AE Title —— 外院在哪台机器上是本院的路由知识。
 * 没有这一步就没法在 {@code imaging_fetch_tasks.peer_aet}（NOT NULL）里填一个真实节点。
 *
 * <p><b>定位规则（确定性，不用模型）</b>：区域/院内主索引普遍带医院命名空间
 * （{@code HZH0012345} 的 {@code HZH}、{@code PAT_MPI_EXAMPLE} 的 {@code PAT}），
 * 命名空间 → 外院 AET 的映射放在运行期配置表 {@code route_config} 里：
 *
 * <pre>
 *   key = hl7.peer-routing.mpi-namespaces   value = {"HZH": "PEER_HZ", "BJ": "PEER_BJ"}
 *   key = hl7.peer-routing.default-peer-aet  value = "PEER_HZ"          （无命名空间时的兜底）
 * </pre>
 *
 * <p>匹配顺序（先具体后宽泛）：
 * <ol>
 *   <li>主索引<b>整体</b>命中映射表（个别患者的特例，如同名同姓或迁院患者）；</li>
 *   <li>主索引<b>命名空间前缀</b>命中映射表（常规路径）；</li>
 *   <li>兜底默认节点。</li>
 * </ol>
 *
 * <p><b>两条硬约束</b>：
 * <ul>
 *   <li>映射到的 AET 必须已在准入表 {@code imaging_peers} 登记 ——
 *       否则排期出去的任务会在建联那一步才炸，白白占一轮重试；</li>
 *   <li>配置读不到 / JSON 坏了 → 返回"未定位"而不是抛异常：一条报文不该把整个触发通道带崩，
 *       信息科从死信里看到"未配置映射"就知道该补哪条配置。</li>
 * </ul>
 */
@Service
@Profile("!test")
public class PeerRoutingResolver {

    private static final Logger LOG = LoggerFactory.getLogger(PeerRoutingResolver.class);

    /** 命名空间映射的配置键（value 为 JSON 对象：前缀 → AET）。 */
    public static final String CONFIG_MPI_NAMESPACES = "hl7.peer-routing.mpi-namespaces";
    /** 兜底节点配置键（value 为 JSON 字符串）。 */
    public static final String CONFIG_DEFAULT_PEER = "hl7.peer-routing.default-peer-aet";

    private final RouteConfigRepository routeConfigRepository;
    private final PeerRepository peerRepository;
    private final ObjectMapper objectMapper;

    public PeerRoutingResolver(RouteConfigRepository routeConfigRepository,
                                PeerRepository peerRepository,
                                ObjectMapper objectMapper) {
        if (routeConfigRepository == null || peerRepository == null || objectMapper == null) {
            throw new IllegalArgumentException("PeerRoutingResolver 依赖不能为 null");
        }
        this.routeConfigRepository = routeConfigRepository;
        this.peerRepository = peerRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 定位一条触发报文对应的外院节点。
     *
     * @param patientMpi 患者主索引（PID-3）
     * @return 定位结果；未定位时 {@code resolved=false} 且 {@code reason} 说明原因
     */
    public PeerRoutingResult resolve(String patientMpi) {
        if (patientMpi == null || patientMpi.isBlank()) {
            return PeerRoutingResult.unresolved("患者主索引为空，无法定位外院节点");
        }
        // 主索引可能是 HL7 复合字段（值^^^赋号机构^类型码）或含重复（~），只取第一个分量
        String mpi = firstComponent(patientMpi);
        if (mpi == null || mpi.isBlank()) {
            return PeerRoutingResult.unresolved("患者主索引为空，无法定位外院节点");
        }

        Map<String, String> namespaces = readNamespaceMap();
        if (namespaces.isEmpty()) {
            return PeerRoutingResult.unresolved("未配置 " + CONFIG_MPI_NAMESPACES
                    + "，无法判断该患者主索引归属哪家外院");
        }

        // 1) 主索引整体命中（特例患者）
        String exact = lookup(namespaces, mpi);
        if (exact != null) {
            return verifyRegistered(exact, "主索引整体命中映射");
        }

        // 2) 命名空间前缀命中（常规路径）
        String namespace = namespaceOf(mpi);
        if (namespace != null) {
            String byNamespace = lookup(namespaces, namespace);
            if (byNamespace != null) {
                return verifyRegistered(byNamespace, "主索引命名空间 " + namespace + " 命中映射");
            }
        } else {
            LOG.debug("主索引 {} 不含命名空间前缀，改用兜底节点", mpi);
        }

        // 3) 兜底默认节点
        String fallback = readDefaultPeer();
        if (fallback == null || fallback.isBlank()) {
            return PeerRoutingResult.unresolved("主索引 " + mpi + " 未命中命名空间映射，且未配置兜底节点 "
                    + CONFIG_DEFAULT_PEER);
        }
        return verifyRegistered(fallback.trim(), "兜底默认节点");
    }

    /**
     * 校验映射到的 AET 已在准入表登记。
     *
     * <p>没登记的节点一律视为未定位：排一个注定建联失败的任务，只会让信息科多查一轮死信。
     */
    private PeerRoutingResult verifyRegistered(String aet, String reason) {
        boolean registered = peerRepository.existsByPeerAet(aet);
        if (!registered) {
            return PeerRoutingResult.unresolved(aet + " 未在准入表 imaging_peers 登记（" + reason + "）");
        }
        return PeerRoutingResult.resolved(aet, reason);
    }

    /** 读命名空间映射；配置缺失或 JSON 坏了都返回空表（不抛）。 */
    private Map<String, String> readNamespaceMap() {
        return routeConfigRepository.findByKey(CONFIG_MPI_NAMESPACES)
                .map(cfg -> {
                    try {
                        return objectMapper.readValue(cfg.getValue(),
                                new TypeReference<Map<String, String>>() { });
                    } catch (Exception ex) {
                        LOG.error("{} 的 JSON 解析失败，命名空间映射按空处理：{}", CONFIG_MPI_NAMESPACES,
                                ex.getMessage());
                        return Map.<String, String>of();
                    }
                })
                .orElseGet(() -> {
                    LOG.warn("运行期配置 {} 未登记，HL7 触发报文无法定位外院节点", CONFIG_MPI_NAMESPACES);
                    return Map.of();
                });
    }

    /** 读兜底节点；配置缺失或 JSON 坏了返回 {@code null}（不抛）。 */
    private String readDefaultPeer() {
        return routeConfigRepository.findByKey(CONFIG_DEFAULT_PEER)
                .map(cfg -> {
                    try {
                        return objectMapper.readValue(cfg.getValue(), String.class);
                    } catch (Exception ex) {
                        LOG.error("{} 的 JSON 解析失败，兜底节点按未配置处理：{}", CONFIG_DEFAULT_PEER,
                                ex.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }

    /** 键查找（大小写不敏感 —— 主索引前缀在不同 HIS 里大小写不一致很常见）。 */
    private static String lookup(Map<String, String> map, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String direct = map.get(key);
        if (direct != null && !direct.isBlank()) {
            return direct.trim();
        }
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)
                    && e.getValue() != null && !e.getValue().isBlank()) {
                return e.getValue().trim();
            }
        }
        return null;
    }

    /**
     * 取主索引的命名空间前缀：开头连续的 ASCII 字母。
     *
     * <p>{@code HZH0012345 → HZH}；{@code PAT_MPI_EXAMPLE → PAT}；{@code 12345 → null}（纯数字主索引没有命名空间）。
     */
    public static String namespaceOf(String patientMpi) {
        String s = firstComponent(patientMpi);
        if (s == null) {
            return null;
        }
        int i = 0;
        while (i < s.length() && Character.isLetter(s.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return null;
        }
        return s.substring(0, i).toUpperCase(Locale.ROOT);
    }

    /**
     * 取复合字段的第一个分量：先按重复符 {@code ~} 截断，再按分量符 {@code ^} 截断。
     *
     * <p>主索引在 HL7 里可能是 {@code PAT_MPI_EXAMPLE^^^HIS_MPI^MR}（值 / 赋号机构 / 类型码），
     * 也可能是多个标识用 {@code ~} 连写；路由要的是稳定的那一个标识。
     */
    static String firstComponent(String fieldValue) {
        if (fieldValue == null) {
            return null;
        }
        String s = fieldValue.trim();
        int rep = s.indexOf('~');
        if (rep >= 0) {
            s = s.substring(0, rep);
        }
        int comp = s.indexOf('^');
        if (comp >= 0) {
            s = s.substring(0, comp);
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 定位结果。
     *
     * @param resolved 是否定位到承接节点
     * @param peerAet  承接节点的 AE Title（未定位时为 {@code null}）
     * @param reason   判定依据 / 未定位原因（写进死信，供信息科照着补配置）
     */
    public record PeerRoutingResult(boolean resolved, String peerAet, String reason) {

        public static PeerRoutingResult resolved(String peerAet, String reason) {
            return new PeerRoutingResult(true, peerAet, reason);
        }

        public static PeerRoutingResult unresolved(String reason) {
            return new PeerRoutingResult(false, null, reason);
        }

        /** 便捷取值：定位成功时返回 AET，否则 {@code Optional.empty()}。 */
        public Optional<String> peerAetOptional() {
            return resolved ? Optional.ofNullable(peerAet) : Optional.empty();
        }
    }
}
