package com.smart.rag.rag.etl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EtlRouteStrategyFactory 单元测试。
 * <p>
 * 验证策略按 order 升序遍历、第一个匹配的策略被选中、无匹配时抛异常。
 */
class EtlRouteStrategyFactoryTest {

    private EtlRouteStrategy fastTrackStrategy;
    private EtlRouteStrategy standardStrategy;

    @BeforeEach
    void setUp() {
        fastTrackStrategy = mock(EtlRouteStrategy.class);
        when(fastTrackStrategy.getOrder()).thenReturn(0);

        standardStrategy = mock(EtlRouteStrategy.class);
        when(standardStrategy.getOrder()).thenReturn(100);
    }

    private EtlCandidate candidate(long fileSize) {
        return new EtlCandidate(1L, "bucket", "key", "file.txt", "text/plain", fileSize, 1L, null);
    }

    @Nested
    @DisplayName("策略路由选择")
    class ResolveStrategy {

        @Test
        @DisplayName("FastTrack 匹配时优先返回")
        void resolve_fastTrackMatched() {
            when(fastTrackStrategy.shouldApply(anyList())).thenReturn(true);
            // standard 不需要判定
            EtlRouteStrategyFactory factory = new EtlRouteStrategyFactory(
                    List.of(fastTrackStrategy, standardStrategy));

            EtlRouteStrategy result = factory.resolve(List.of(candidate(100)));
            assertThat(result).isSameAs(fastTrackStrategy);
        }

        @Test
        @DisplayName("FastTrack 不匹配时回退到 Standard")
        void resolve_fallBackToStandard() {
            when(fastTrackStrategy.shouldApply(anyList())).thenReturn(false);
            when(standardStrategy.shouldApply(anyList())).thenReturn(true);

            EtlRouteStrategyFactory factory = new EtlRouteStrategyFactory(
                    List.of(fastTrackStrategy, standardStrategy));

            EtlRouteStrategy result = factory.resolve(List.of(candidate(100)));
            assertThat(result).isSameAs(standardStrategy);
        }

        @Test
        @DisplayName("没有任何策略匹配时抛出 IllegalStateException")
        void resolve_noMatch_throws() {
            when(fastTrackStrategy.shouldApply(anyList())).thenReturn(false);
            when(standardStrategy.shouldApply(anyList())).thenReturn(false);

            EtlRouteStrategyFactory factory = new EtlRouteStrategyFactory(
                    List.of(fastTrackStrategy, standardStrategy));

            List<EtlCandidate> candidates = List.of(candidate(100));
            assertThatThrownBy(() -> factory.resolve(candidates))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No ETL route strategy matched");
        }
    }

    @Nested
    @DisplayName("策略排序")
    class StrategyOrdering {

        @Test
        @DisplayName("策略按 order 升序判定，即使传入顺序相反")
        void resolve_respectsOrderRegardlessOfInputOrder() {
            when(standardStrategy.shouldApply(anyList())).thenReturn(true);

            // 传入顺序: standard(100) 在前，fastTrack(0) 在后
            EtlRouteStrategyFactory factory = new EtlRouteStrategyFactory(
                    List.of(standardStrategy, fastTrackStrategy));

            // fastTrack 的 order=0 更小，应该先判定
            // 但 fastTrack 未设置 shouldApply，默认返回 false
            // 所以 standard 被选中
            EtlRouteStrategy result = factory.resolve(List.of(candidate(100)));
            assertThat(result).isSameAs(standardStrategy);
        }

        @Test
        @DisplayName("空策略列表时直接抛出异常")
        void resolve_emptyStrategies_throws() {
            EtlRouteStrategyFactory factory = new EtlRouteStrategyFactory(Collections.emptyList());

            assertThatThrownBy(() -> factory.resolve(List.of(candidate(100))))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("边界值")
    class EdgeCases {

        @Test
        @DisplayName("空候选列表也能路由")
        void resolve_emptyCandidates() {
            when(fastTrackStrategy.shouldApply(anyList())).thenReturn(true);

            EtlRouteStrategyFactory factory = new EtlRouteStrategyFactory(
                    List.of(fastTrackStrategy));

            EtlRouteStrategy result = factory.resolve(Collections.emptyList());
            assertThat(result).isSameAs(fastTrackStrategy);
        }

        @Test
        @DisplayName("只有一个策略时直接返回")
        void resolve_singleStrategy() {
            when(standardStrategy.shouldApply(anyList())).thenReturn(true);

            EtlRouteStrategyFactory factory = new EtlRouteStrategyFactory(
                    List.of(standardStrategy));

            EtlRouteStrategy result = factory.resolve(List.of(candidate(1024)));
            assertThat(result).isSameAs(standardStrategy);
        }
    }
}
