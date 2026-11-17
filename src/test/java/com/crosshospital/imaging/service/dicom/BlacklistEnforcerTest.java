package com.crosshospital.imaging.service.dicom;

import com.crosshospital.imaging.exception.HandshakeException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BlacklistEnforcer} 单元测试。
 *
 * <p>核心覆盖：
 * <ol>
 *   <li>命中：peerAet 在 blacklist 中 → 抛 {@link HandshakeException}，{@code retryable=false}</li>
 *   <li>不命中：peerAet 不在 blacklist 中 → 正常通过</li>
 *   <li>大小写不敏感：AET 协议层 ASCII 匹配，本服务按 {@code equalsIgnoreCase}</li>
 *   <li>边界：null / 空 / null peerAet / 多 AET 列表</li>
 * </ol>
 */
public class BlacklistEnforcerTest {

    private final BlacklistEnforcer enforcer = new BlacklistEnforcer();

    /** ① 命中 — peerAet 在 blacklist → 抛 HandshakeException(BLACKLISTED, retryable=false)。 */
    @Test
    void enforceThrowsWhenPeerAetIsBlacklisted() {
        List<String> blacklist = Arrays.asList("MALICIOUS_AET", "BAD_PEER");
        HandshakeException ex = assertThrows(HandshakeException.class,
                () -> enforcer.enforce("MALICIOUS_AET", blacklist));
        assertEquals(HandshakeException.FailureCode.BLACKLISTED, ex.getFailureCode());
        assertFalse(ex.isRetryable(), "黑名单命中不应可重试");
        assertTrue(ex.getMessage().contains("MALICIOUS_AET"));
        // isBlacklisted 也应返回 true
        assertTrue(enforcer.isBlacklisted("MALICIOUS_AET", blacklist));
    }

    /** ② 不命中 — peerAet 不在 blacklist → 正常返回。 */
    @Test
    void enforcePassesWhenPeerAetIsNotBlacklisted() {
        List<String> blacklist = Arrays.asList("MALICIOUS_AET", "BAD_PEER");
        assertDoesNotThrow(() -> enforcer.enforce("GOOD_PEER", blacklist));
        assertFalse(enforcer.isBlacklisted("GOOD_PEER", blacklist));
    }

    /** ③ 大小写不敏感 — dcm4che Connection.isBlacklisted() 同样按 ASCII 不区分大小写匹配。 */
    @Test
    void enforceMatchesCaseInsensitively() {
        List<String> blacklist = Arrays.asList("MALICIOUS_AET");
        // 大写命中
        assertTrue(enforcer.isBlacklisted("MALICIOUS_AET", blacklist));
        // 小写命中
        assertTrue(enforcer.isBlacklisted("malicious_aet", blacklist));
        // 混合大小写命中
        assertTrue(enforcer.isBlacklisted("Malicious_Aet", blacklist));

        // 抛异常时不区分大小写
        HandshakeException ex = assertThrows(HandshakeException.class,
                () -> enforcer.enforce("malicious_aet", blacklist));
        assertEquals(HandshakeException.FailureCode.BLACKLISTED, ex.getFailureCode());
        assertFalse(ex.isRetryable());
    }

    /** ④ 边界 — null / 空 / null peerAet / 多 AET 列表。 */
    @Test
    void enforceHandlesEmptyAndNullEdgeCases() {
        // 空 blacklist
        assertDoesNotThrow(() -> enforcer.enforce("ANY_AET", Collections.emptyList()));
        assertFalse(enforcer.isBlacklisted("ANY_AET", Collections.emptyList()));

        // null blacklist
        assertDoesNotThrow(() -> enforcer.enforce("ANY_AET", null));
        assertFalse(enforcer.isBlacklisted("ANY_AET", null));

        // null peerAet + 非空 blacklist → 不命中（不能 NPE）
        assertFalse(enforcer.isBlacklisted(null, Arrays.asList("BAD")));
        assertDoesNotThrow(() -> enforcer.enforce(null, Arrays.asList("BAD")));

        // blacklist 中含 null 元素 → 跳过（不能 NPE）
        List<String> blacklistWithNull = Arrays.asList("BAD", null, "WORSE");
        assertTrue(enforcer.isBlacklisted("BAD", blacklistWithNull));
        assertTrue(enforcer.isBlacklisted("WORSE", blacklistWithNull));
        assertFalse(enforcer.isBlacklisted("GOOD", blacklistWithNull));
        assertDoesNotThrow(() -> enforcer.enforce("GOOD", blacklistWithNull));

        // 多 AET 列表 → 命中任一即抛
        HandshakeException ex = assertThrows(HandshakeException.class,
                () -> enforcer.enforce("WORSE", blacklistWithNull));
        assertEquals(HandshakeException.FailureCode.BLACKLISTED, ex.getFailureCode());
    }
}