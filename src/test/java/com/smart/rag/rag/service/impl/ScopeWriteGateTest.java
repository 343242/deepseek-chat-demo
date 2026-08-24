package com.smart.rag.rag.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ScopeWriteGate} 单元测试（§3.6：per-scope 互斥、tryAcquire 超时、release 配对）。
 */
@DisplayName("ScopeWriteGate — 同 scope 写闸门")
class ScopeWriteGateTest {

    private final ScopeWriteGate gate = new ScopeWriteGate(null);

    @Test
    @DisplayName("同 scope 二次 acquire 超时（permits=1 互斥）")
    void sameScope_secondAcquireTimesOut() throws Exception {
        gate.tryAcquire(1L, null, 50);

        assertThatThrownBy(() -> gate.tryAcquire(1L, null, 50))
                .isInstanceOf(ScopeWriteGate.WriteGateTimeoutException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    @DisplayName("release 后可再次 acquire（finally 配对释放语义）")
    void release_allowsReacquire() {
        gate.tryAcquire(1L, 20L, 50);
        gate.release(1L, 20L);

        assertThatCode(() -> gate.tryAcquire(1L, 20L, 50)).doesNotThrowAnyException();
        gate.release(1L, 20L);
    }

    @Test
    @DisplayName("不同 scope 不互相阻塞（批量上传跨 scope 并发）")
    void differentScopes_independent() {
        gate.tryAcquire(1L, null, 50);

        assertThatCode(() -> gate.tryAcquire(2L, null, 50)).doesNotThrowAnyException();
        assertThatCode(() -> gate.tryAcquire(1L, 30L, 50)).doesNotThrowAnyException();

        gate.release(1L, null);
        gate.release(2L, null);
        gate.release(1L, 30L);
    }

    @Test
    @DisplayName("teamId null 与 -1 是不同 scope 键（null 归一为 '-1' 仅在 lockScope key；闸门键同为 null→-1）")
    void nullTeamScope_keyNormalization() {
        // 注：ScopeWriteGate 的 key 归一与 lockScope 一致（null → -1）
        gate.tryAcquire(1L, null, 50);
        // 同 (1, null) 互斥
        assertThatThrownBy(() -> gate.tryAcquire(1L, null, 50))
                .isInstanceOf(ScopeWriteGate.WriteGateTimeoutException.class);
        gate.release(1L, null);
        // release 后信号量复位
        assertThatCode(() -> gate.tryAcquire(1L, null, 50)).doesNotThrowAnyException();
        gate.release(1L, null);
    }

    @Test
    @DisplayName("未 acquire 直接 release 无害（幂等清理）")
    void releaseWithoutAcquire_harmless() {
        assertThatCode(() -> gate.release(99L, null)).doesNotThrowAnyException();
    }
}
