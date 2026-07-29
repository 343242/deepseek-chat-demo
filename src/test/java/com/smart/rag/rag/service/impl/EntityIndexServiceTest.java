package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.mapper.EntityCooccurrenceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link EntityIndexService} 单元测试。
 * <p>
 * 验证 weak_tie 重算编排序列（§5.1/§5.3/§5.4）：delete → project → updateWeakTie。
 * 顺序是正确性核心——delete 必须先于 project（清除失效边），project 必须先于
 * updateWeakTie（在刷新后的共现图上计算）。SQL 正确性由真实 PG 集成测试覆盖。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntityIndexService — weak_tie 重算编排（delete → project → update）")
class EntityIndexServiceTest {

    @Mock
    private EntityCooccurrenceMapper cooccurrenceMapper;

    @InjectMocks
    private EntityIndexService service;

    @Test
    @DisplayName("recomputeWeakTieScores — 按序调用 delete → project → updateWeakTie")
    void recomputeWeakTieScores_correctOrder() {
        service.recomputeWeakTieScores(10L, null);

        InOrder order = inOrder(cooccurrenceMapper);
        order.verify(cooccurrenceMapper).deleteByScope(10L, null);
        order.verify(cooccurrenceMapper).projectCooccurrence(10L, null);
        order.verify(cooccurrenceMapper).updateWeakTieScores(10L, null);
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("recomputeWeakTieScores — teamId 非空时透传作用域")
    void recomputeWeakTieScores_teamScopePassedThrough() {
        service.recomputeWeakTieScores(7L, 42L);

        InOrder order = inOrder(cooccurrenceMapper);
        order.verify(cooccurrenceMapper).deleteByScope(7L, 42L);
        order.verify(cooccurrenceMapper).projectCooccurrence(7L, 42L);
        order.verify(cooccurrenceMapper).updateWeakTieScores(7L, 42L);
    }
}
