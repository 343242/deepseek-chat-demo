package com.demo.chat.rag.chunk;

import com.demo.chat.rag.config.DocumentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ParentChildChunkStrategy 单元测试。
 * <p>
 * 验证父子文档分块：parent/child metadata 正确性、parentId 唯一性、
 * childIndex 递增、边界条件。
 * </p>
 */
class ParentChildChunkStrategyTest {

    private DocumentProperties properties;
    private ParentChildChunkStrategy strategy;

    @BeforeEach
    void setUp() {
        properties = new DocumentProperties();
        properties.setParentChunkSize(200);
        properties.setChildChunkSize(50);
        strategy = new ParentChildChunkStrategy(properties);
    }

    @Nested
    @DisplayName("strategyName")
    class StrategyNameTest {

        @Test
        @DisplayName("返回 'parent-child'")
        void returns_parent_child() {
            assertThat(strategy.strategyName()).isEqualTo("parent-child");
        }
    }

    @Nested
    @DisplayName("基本父子分块")
    class BasicParentChildChunking {

        @Test
        @DisplayName("长文档产生 parent 和 child")
        void longDoc_producesParentsAndChildren() {
            String longText = "这是一段较长的测试文本，用于验证父子文档分块策略。".repeat(100);
            Document doc = new Document(longText);

            List<Document> chunks = strategy.chunk(List.of(doc), "test.txt");

            // 应同时包含 parent 和 child
            List<Document> parents = filterByIsParent(chunks, true);
            List<Document> children = filterByIsParent(chunks, false);

            assertThat(parents).isNotEmpty();
            assertThat(children).isNotEmpty();
        }

        @Test
        @DisplayName("parent 标记 isParent=true")
        void parent_hasIsParentTrue() {
            String longText = "这是一段较长的测试文本，用于验证父子文档分块策略。".repeat(100);
            Document doc = new Document(longText);

            List<Document> chunks = strategy.chunk(List.of(doc), "test.txt");

            List<Document> parents = filterByIsParent(chunks, true);
            for (Document parent : parents) {
                assertThat(parent.getMetadata()).containsEntry(ParentChildChunkStrategy.META_IS_PARENT, true);
                assertThat(parent.getMetadata()).containsEntry("chunkType", "parent");
            }
        }

        @Test
        @DisplayName("child 标记 isParent=false 并包含 parentId")
        void child_hasIsParentFalseAndParentId() {
            String longText = "这是一段较长的测试文本，用于验证父子文档分块策略。".repeat(100);
            Document doc = new Document(longText);

            List<Document> chunks = strategy.chunk(List.of(doc), "test.txt");

            List<Document> children = filterByIsParent(chunks, false);
            for (Document child : children) {
                assertThat(child.getMetadata()).containsEntry(ParentChildChunkStrategy.META_IS_PARENT, false);
                assertThat(child.getMetadata()).containsKey(ParentChildChunkStrategy.META_PARENT_ID);
                assertThat(child.getMetadata()).containsEntry("chunkType", "child");
            }
        }
    }

    @Nested
    @DisplayName("parentId 唯一性")
    class ParentIdUniqueness {

        @Test
        @DisplayName("每个 parent 有唯一的 parentId")
        void eachParent_uniqueParentId() {
            String longText = "这是一段较长的测试文本，用于验证父子文档分块策略。".repeat(200);
            Document doc = new Document(longText);

            List<Document> chunks = strategy.chunk(List.of(doc), "test.txt");

            List<Document> parents = filterByIsParent(chunks, true);
            Set<String> parentIds = new HashSet<>();
            for (Document parent : parents) {
                String id = (String) parent.getMetadata().get(ParentChildChunkStrategy.META_PARENT_ID);
                assertThat(id).isNotNull();
                assertThat(parentIds).doesNotContain(id);
                parentIds.add(id);
            }
        }

        @Test
        @DisplayName("同一 parent 的 children 共享相同 parentId")
        void childrenOfSameParent_shareParentId() {
            String longText = "这是一段较长的测试文本，用于验证父子文档分块策略。".repeat(100);
            Document doc = new Document(longText);

            List<Document> chunks = strategy.chunk(List.of(doc), "test.txt");

            List<Document> parents = filterByIsParent(chunks, true);
            List<Document> children = filterByIsParent(chunks, false);

            // 至少有一个 parent
            assertThat(parents).isNotEmpty();

            // 每个 child 的 parentId 应能在 parent 列表中找到
            Set<String> parentIds = parents.stream()
                    .map(p -> (String) p.getMetadata().get(ParentChildChunkStrategy.META_PARENT_ID))
                    .collect(java.util.stream.Collectors.toSet());

            for (Document child : children) {
                String childParentId = (String) child.getMetadata().get(ParentChildChunkStrategy.META_PARENT_ID);
                assertThat(parentIds).contains(childParentId);
            }
        }
    }

    @Nested
    @DisplayName("child metadata 完整性")
    class ChildMetadata {

        @Test
        @DisplayName("child 有 childIndexInParent 和 childCount")
        void child_hasChildIndexAndCount() {
            String longText = "这是一段较长的测试文本，用于验证父子文档分块策略。".repeat(100);
            Document doc = new Document(longText);

            List<Document> chunks = strategy.chunk(List.of(doc), "test.txt");

            List<Document> children = filterByIsParent(chunks, false);
            for (Document child : children) {
                assertThat(child.getMetadata()).containsKey(ParentChildChunkStrategy.META_CHILD_INDEX_IN_PARENT);
                assertThat(child.getMetadata()).containsKey(ParentChildChunkStrategy.META_CHILD_COUNT);
            }
        }

        @Test
        @DisplayName("child 的 childIndexInParent 从 0 开始递增")
        void childIndex_startsFromZero() {
            String longText = "这是一段较长的测试文本，用于验证父子文档分块策略。".repeat(100);
            Document doc = new Document(longText);

            List<Document> chunks = strategy.chunk(List.of(doc), "test.txt");

            List<Document> children = filterByIsParent(chunks, false);
            // 按 parentId 分组，每组内 childIndex 应从 0 递增
            java.util.Map<String, List<Document>> byParent = new java.util.LinkedHashMap<>();
            for (Document child : children) {
                String parentId = (String) child.getMetadata().get(ParentChildChunkStrategy.META_PARENT_ID);
                byParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(child);
            }

            for (List<Document> group : byParent.values()) {
                for (int i = 0; i < group.size(); i++) {
                    int childIndex = (int) group.get(i).getMetadata().get(ParentChildChunkStrategy.META_CHILD_INDEX_IN_PARENT);
                    assertThat(childIndex).isEqualTo(i);
                }
            }
        }

        @Test
        @DisplayName("child 的 chunkIndex 全局递增")
        void childChunkIndex_globalIncrement() {
            String longText = "这是一段较长的测试文本，用于验证父子文档分块策略。".repeat(100);
            Document doc = new Document(longText);

            List<Document> chunks = strategy.chunk(List.of(doc), "test.txt");

            List<Document> children = filterByIsParent(chunks, false);
            List<Integer> indices = children.stream()
                    .map(c -> (Integer) c.getMetadata().get("chunkIndex"))
                    .toList();

            for (int i = 0; i < indices.size(); i++) {
                assertThat(indices.get(i)).isEqualTo(i);
            }
        }
    }

    @Nested
    @DisplayName("边界条件")
    class EdgeCases {

        @Test
        @DisplayName("空文档列表返回空列表")
        void emptyDocList_returnsEmpty() {
            List<Document> result = strategy.chunk(List.of(), "empty.txt");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("所有 chunk 的 totalChunks 一致")
        void allChunks_haveConsistentTotalChunks() {
            String longText = "这是一段较长的测试文本，用于验证父子文档分块策略。".repeat(100);
            Document doc = new Document(longText);

            List<Document> chunks = strategy.chunk(List.of(doc), "test.txt");

            for (Document chunk : chunks) {
                assertThat(chunk.getMetadata()).containsEntry("totalChunks", chunks.size());
            }
        }

        @Test
        @DisplayName("所有 chunk 包含 source metadata")
        void allChunks_haveSource() {
            String longText = "这是一段较长的测试文本，用于验证父子文档分块策略。".repeat(100);
            Document doc = new Document(longText);

            List<Document> chunks = strategy.chunk(List.of(doc), "source.txt");

            for (Document chunk : chunks) {
                assertThat(chunk.getMetadata()).containsEntry("source", "source.txt");
            }
        }
    }

    // ==================== 工具方法 ====================

    private List<Document> filterByIsParent(List<Document> chunks, boolean isParent) {
        return chunks.stream()
                .filter(c -> Boolean.TRUE.equals(c.getMetadata().get(ParentChildChunkStrategy.META_IS_PARENT)) == isParent)
                .toList();
    }
}
