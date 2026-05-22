package com.smart.rag.rag.chunk;

import com.smart.rag.rag.config.DocumentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChunkStrategyFactory 单元测试。
 * <p>
 * 验证策略路由：按名称获取策略、未知策略 fallback、可用策略列表。
 * </p>
 */
class ChunkStrategyFactoryTest {

    private DocumentProperties properties;
    private TokenChunkStrategy tokenStrategy;
    private StructureAwareChunkStrategy structureStrategy;
    private ParentChildChunkStrategy parentChildStrategy;
    private ChunkStrategyFactory factory;

    @BeforeEach
    void setUp() {
        properties = new DocumentProperties();
        properties.setChunkStrategy("parent-child");

        tokenStrategy = new TokenChunkStrategy(properties);
        structureStrategy = new StructureAwareChunkStrategy(properties);
        parentChildStrategy = new ParentChildChunkStrategy(properties);

        List<ChunkStrategy> strategies = List.of(tokenStrategy, structureStrategy, parentChildStrategy);
        factory = new ChunkStrategyFactory(strategies, properties);
    }

    @Nested
    @DisplayName("策略路由")
    class StrategyRouting {

        @Test
        @DisplayName("按名称获取 token 策略")
        void getStrategy_token() {
            ChunkStrategy strategy = factory.getStrategy("token");
            assertThat(strategy).isSameAs(tokenStrategy);
        }

        @Test
        @DisplayName("按名称获取 paragraph 策略")
        void getStrategy_paragraph() {
            ChunkStrategy strategy = factory.getStrategy("paragraph");
            assertThat(strategy).isSameAs(structureStrategy);
        }

        @Test
        @DisplayName("按名称获取 parent-child 策略")
        void getStrategy_parentChild() {
            ChunkStrategy strategy = factory.getStrategy("parent-child");
            assertThat(strategy).isSameAs(parentChildStrategy);
        }

        @Test
        @DisplayName("未知策略名称回退到默认策略")
        void unknownStrategy_fallsBackToDefault() {
            ChunkStrategy strategy = factory.getStrategy("nonexistent");
            // 默认策略是 parent-child
            assertThat(strategy).isSameAs(parentChildStrategy);
        }
    }

    @Nested
    @DisplayName("可用策略列表")
    class AvailableStrategies {

        @Test
        @DisplayName("返回所有已注册策略名称")
        void availableStrategies_containsAll() {
            assertThat(factory.availableStrategies())
                    .containsExactlyInAnyOrder("token", "paragraph", "parent-child");
        }
    }
}
