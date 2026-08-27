package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.config.RagEntityProperties;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * {@link ScopeLockTemplate} 单元测试（§3.1 契约固化：自动提交拒绝 + 前置序列）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScopeLockTemplate — 事务断言 + setLockTimeout → lockScope 顺序")
class ScopeLockTemplateTest {

    @Mock
    private EntityCooccurrenceMapper cooccurrenceMapper;

    private ScopeLockTemplate template;

    @BeforeEach
    void setUp() {
        template = new ScopeLockTemplate(cooccurrenceMapper,
                new RagEntityProperties(10, 500,32, 0.85, 50, 20, 10, 1, 0.7,
                        0.5, 0.3, 0.2, true, null, true, 12345, 3, 0, 0, null));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private void simulateTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @Test
    @DisplayName("自动提交下调用 → IllegalStateException 拒绝（pg_advisory_xact_lock 静默失效防呆，§3.1）")
    void autoCommit_rejected() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        assertThatThrownBy(() -> template.withinScopeLock(1L, null, () -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("事务内");

        verifyNoInteractions(cooccurrenceMapper);   // 取锁前拦截，不碰 mapper
    }

    @Test
    @DisplayName("事务内调用 → setLockTimeout（配置值）先于 lockScope，body 执行（R1 序列）")
    void inTransaction_correctSequence() {
        simulateTransaction();
        List<String> sequence = new java.util.ArrayList<>();
        doAnswer(inv -> {
            sequence.add("setLockTimeout(" + inv.getArgument(0) + ")");
            return null;
        }).when(cooccurrenceMapper).setLockTimeout(anyLong());
        doAnswer(inv -> {
            sequence.add("lockScope");
            return null;
        }).when(cooccurrenceMapper).lockScope(anyLong(), any());

        template.withinScopeLock(1L, 2L, () -> sequence.add("body"));

        assertThat(sequence).containsExactly("setLockTimeout(12345)", "lockScope", "body");
    }
}
