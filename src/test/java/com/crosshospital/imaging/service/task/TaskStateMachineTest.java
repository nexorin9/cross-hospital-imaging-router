package com.crosshospital.imaging.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.exception.IllegalTaskTransitionException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Task 20 — 五态机校验器单元测试（纯逻辑，不碰数据库与网络）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>6 个状态 × 合法后继（每个状态的后继集合逐一核对）；</li>
 *   <li>8 种典型非法跃迁（倒退、跳跃、终态复活）；</li>
 *   <li>同态跃迁 = 幂等无操作（不是非法）；</li>
 *   <li>终态集合与幂等块集合；</li>
 *   <li>非法跃迁异常里带「合法后继」，方便排障。</li>
 * </ul>
 */
@DisplayName("Task 20 — TaskStateMachine 五态机校验器")
class TaskStateMachineTest {

    @Test
    @DisplayName("6 个状态各自的合法后继集合与 V2 迁移一致")
    void legalSuccessorsMatchMigration() {
        assertEquals(2, TaskStateMachine.allowedNext(TaskStatus.QUEUED).size());
        assertTrue(TaskStateMachine.allowedNext(TaskStatus.QUEUED).contains(TaskStatus.QUERYING));
        assertTrue(TaskStateMachine.allowedNext(TaskStatus.QUEUED).contains(TaskStatus.FAILED));

        assertTrue(TaskStateMachine.allowedNext(TaskStatus.QUERYING).contains(TaskStatus.PULLING));
        assertTrue(TaskStateMachine.allowedNext(TaskStatus.QUERYING).contains(TaskStatus.FAILED));

        assertTrue(TaskStateMachine.allowedNext(TaskStatus.PULLING).contains(TaskStatus.STORED));
        assertTrue(TaskStateMachine.allowedNext(TaskStatus.PULLING).contains(TaskStatus.FAILED));

        assertTrue(TaskStateMachine.allowedNext(TaskStatus.STORED).contains(TaskStatus.NOTIFIED));
        assertTrue(TaskStateMachine.allowedNext(TaskStatus.STORED).contains(TaskStatus.FAILED));

        assertTrue(TaskStateMachine.allowedNext(TaskStatus.NOTIFIED).isEmpty());
        assertTrue(TaskStateMachine.allowedNext(TaskStatus.FAILED).isEmpty());
    }

    @ParameterizedTest(name = "合法跃迁 {0} → {1}")
    @CsvSource({
            "QUEUED,QUERYING",
            "QUEUED,FAILED",
            "QUERYING,PULLING",
            "QUERYING,FAILED",
            "PULLING,STORED",
            "PULLING,FAILED",
            "STORED,NOTIFIED",
            "STORED,FAILED"
    })
    @DisplayName("8 条合法跃迁全部放行")
    void legalTransitionsPass(TaskStatus from, TaskStatus to) {
        assertTrue(TaskStateMachine.isLegal(from, to));
        TaskStateMachine.assertTransition(from, to); // 不抛即通过
    }

    @ParameterizedTest(name = "非法跃迁 {0} → {1}")
    @CsvSource({
            "STORED,QUERYING",
            "STORED,PULLING",
            "NOTIFIED,QUERYING",
            "NOTIFIED,STORED",
            "NOTIFIED,FAILED",
            "FAILED,QUEUED",
            "FAILED,QUERYING",
            "QUERYING,STORED",
            "QUEUED,PULLING",
            "QUEUED,STORED"
    })
    @DisplayName("非法跃迁（倒退 / 跳跃 / 终态复活）一律被拒")
    void illegalTransitionsRejected(TaskStatus from, TaskStatus to) {
        assertFalse(TaskStateMachine.isLegal(from, to));
        assertThrows(IllegalTaskTransitionException.class,
                () -> TaskStateMachine.assertTransition(from, to));
    }

    @Test
    @DisplayName("同态跃迁算幂等无操作，不是非法")
    void sameStateIsNoOp() {
        for (TaskStatus status : TaskStatus.values()) {
            assertTrue(TaskStateMachine.isNoOp(status, status), status + " 同态应为幂等");
            assertTrue(TaskStateMachine.isLegal(status, status), status + " 同态不应判非法");
        }
    }

    @Test
    @DisplayName("null 起点或终点一律判非法")
    void nullsAreIllegal() {
        assertFalse(TaskStateMachine.isLegal(null, TaskStatus.QUEUED));
        assertFalse(TaskStateMachine.isLegal(TaskStatus.QUEUED, null));
        assertFalse(TaskStateMachine.isLegal(null, null));
        assertThrows(IllegalTaskTransitionException.class,
                () -> TaskStateMachine.assertTransition(null, TaskStatus.QUEUED));
    }

    @Test
    @DisplayName("终态集合 = notified / failed；幂等块 = 四个未终结态")
    void terminalAndIdempotentBlockSets() {
        assertEquals(2, TaskStateMachine.terminalStates().size());
        assertTrue(TaskStateMachine.terminalStates().contains(TaskStatus.NOTIFIED));
        assertTrue(TaskStateMachine.terminalStates().contains(TaskStatus.FAILED));
        assertTrue(TaskStateMachine.isTerminal(TaskStatus.NOTIFIED));
        assertTrue(TaskStateMachine.isTerminal(TaskStatus.FAILED));
        assertFalse(TaskStateMachine.isTerminal(TaskStatus.STORED));

        assertTrue(TaskStateMachine.isIdempotentBlock(TaskStatus.QUEUED));
        assertTrue(TaskStateMachine.isIdempotentBlock(TaskStatus.QUERYING));
        assertTrue(TaskStateMachine.isIdempotentBlock(TaskStatus.PULLING));
        assertTrue(TaskStateMachine.isIdempotentBlock(TaskStatus.STORED));
        assertFalse(TaskStateMachine.isIdempotentBlock(TaskStatus.NOTIFIED));
        assertFalse(TaskStateMachine.isIdempotentBlock(TaskStatus.FAILED));
    }

    @Test
    @DisplayName("非法跃迁异常带「合法后继」，排障能直接看出推早了还是推晚了")
    void illegalTransitionExceptionCarriesContext() {
        IllegalTaskTransitionException ex = assertThrows(IllegalTaskTransitionException.class,
                () -> TaskStateMachine.assertTransition(TaskStatus.STORED, TaskStatus.QUERYING));
        assertEquals(TaskStatus.STORED, ex.getFrom());
        assertEquals(TaskStatus.QUERYING, ex.getTo());
        assertTrue(ex.getMessage().contains("stored"), ex.getMessage());
        assertTrue(ex.getMessage().contains("querying"), ex.getMessage());
        assertTrue(ex.getMessage().contains("notified"), ex.getMessage());
    }

    @Test
    @DisplayName("跃迁表文本可读（每个状态一行）")
    void describeIsReadable() {
        String text = TaskStateMachine.describe();
        assertTrue(text.contains("queued → failed / querying"), text);
        assertTrue(text.contains("notified → (终态)"), text);
        assertEquals(TaskStatus.values().length, text.split("\n").length);
    }
}
