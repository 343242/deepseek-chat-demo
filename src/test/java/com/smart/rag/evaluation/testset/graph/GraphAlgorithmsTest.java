package com.smart.rag.evaluation.testset.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GraphAlgorithms} 单元测试（翻译自 ragas graph.py 的算法行为对照）。
 */
@DisplayName("知识图谱算法")
class GraphAlgorithmsTest {

    private static Node node(String id) {
        return new Node(id, "content-" + id, Map.of("userId", "1"), new double[]{0.1, 0.2});
    }

    private static KnowledgeGraph graph(Relationship... relationships) {
        var kg = new KnowledgeGraph();
        for (var rel : relationships) {
            kg.addNode(node(rel.source()));
            kg.addNode(node(rel.target()));
            kg.addRelationship(rel);
        }
        return kg;
    }

    @Nested
    @DisplayName("findTwoNodesSingleRel 实体重叠三元组")
    class FindTwoNodesSingleRel {

        @Test
        @DisplayName("三元组按小 id 在前归一化，关系方向随节点翻转为 小id→大id")
        void normalizesTripletOrder() {
            var kg = graph(Relationship.of("chunk-b", "chunk-a",
                    RelationshipType.ENTITY_OVERLAP, 0.95));

            var triplets = GraphAlgorithms.findTwoNodesSingleRel(
                    kg, rel -> rel.type() == RelationshipType.ENTITY_OVERLAP);

            assertThat(triplets).hasSize(1);
            var triplet = triplets.getFirst();
            assertThat(triplet.a().id()).isEqualTo("chunk-a");
            assertThat(triplet.b().id()).isEqualTo("chunk-b");
            // 归一化后关系方向与节点顺序一致：小 id → 大 id
            assertThat(triplet.relationship().source()).isEqualTo("chunk-a");
            assertThat(triplet.relationship().target()).isEqualTo("chunk-b");
        }

        @Test
        @DisplayName("自环剔除；同一对节点双向重复只保留一个三元组")
        void dedupesAndSkipsSelfLoops() {
            var kg = graph(
                    Relationship.of("chunk-a", "chunk-b", RelationshipType.ENTITY_OVERLAP, 0.9),
                    Relationship.of("chunk-b", "chunk-a", RelationshipType.ENTITY_OVERLAP, 0.9),
                    Relationship.of("chunk-c", "chunk-c", RelationshipType.ENTITY_OVERLAP, 1.0));

            var triplets = GraphAlgorithms.findTwoNodesSingleRel(
                    kg, rel -> rel.type() == RelationshipType.ENTITY_OVERLAP);

            assertThat(triplets).hasSize(1);
        }

        @Test
        @DisplayName("关系条件过滤：相似边不进实体重叠三元组")
        void respectsCondition() {
            var kg = graph(
                    Relationship.of("chunk-a", "chunk-b", RelationshipType.ENTITY_OVERLAP, 0.9),
                    Relationship.of("chunk-b", "chunk-c", RelationshipType.SIMILARITY, 0.8));

            var all = GraphAlgorithms.findTwoNodesSingleRel(kg, rel -> true);
            var overlapOnly = GraphAlgorithms.findTwoNodesSingleRel(
                    kg, rel -> rel.type() == RelationshipType.ENTITY_OVERLAP);

            assertThat(all).hasSize(2);
            assertThat(overlapOnly).hasSize(1);
            assertThat(overlapOnly.getFirst().a().id()).isEqualTo("chunk-a");
        }
    }

    @Nested
    @DisplayName("findNIndirectClusters 间接簇查找（MultiHopAbstract 数据源）")
    class FindNIndirectClusters {

        @Test
        @DisplayName("参数校验：depthLimit<2 / n<1 / 无匹配关系 抛 IllegalArgumentException")
        void validatesArguments() {
            var kg = graph(Relationship.of("a", "b", RelationshipType.SIMILARITY, 0.9));
            assertThatThrownBy(() -> GraphAlgorithms.findNIndirectClusters(
                    kg, rel -> true, 1, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> GraphAlgorithms.findNIndirectClusters(
                    kg, rel -> true, 0, 3))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> GraphAlgorithms.findNIndirectClusters(
                    kg, rel -> rel.type() == RelationshipType.ENTITY_OVERLAP, 1, 3))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No relationships match");
        }

        @Test
        @DisplayName("链式图 A→B→C：簇为路径节点集，全部是 {a,b,c} 的子集且 ≥2 节点")
        void chainGraphProducesPathNodeSets() {
            var kg = graph(
                    Relationship.of("a", "b", RelationshipType.SIMILARITY, 0.9),
                    Relationship.of("b", "c", RelationshipType.SIMILARITY, 0.9));

            var clusters = GraphAlgorithms.findNIndirectClusters(
                    kg, rel -> rel.type() == RelationshipType.SIMILARITY, 5, 3);

            assertThat(clusters).isNotEmpty();
            assertThat(clusters).allSatisfy(set -> {
                assertThat(set.size()).isBetween(2, 3);
                assertThat(set.stream().map(Node::id)).isSubsetOf("a", "b", "c");
            });
        }

        @Test
        @DisplayName("双向边允许反向通行：A↔B 双向 + B→C 可形成 {a,b,c} 簇")
        void bidirectionalEdgesTraverseBothWays() {
            var kg = new KnowledgeGraph();
            kg.addNode(node("a"));
            kg.addNode(node("b"));
            kg.addNode(node("c"));
            kg.addRelationship(new Relationship("a", "b", RelationshipType.SIMILARITY,
                    0.9, true, Map.of()));
            kg.addRelationship(Relationship.of("b", "c", RelationshipType.SIMILARITY, 0.9));

            var clusters = GraphAlgorithms.findNIndirectClusters(kg, rel -> true, 5, 3);

            assertThat(clusters).anySatisfy(set ->
                    assertThat(set.stream().map(Node::id))
                            .containsExactlyInAnyOrder("a", "b", "c"));
        }

        @Test
        @DisplayName("自环边不崩溃：Set.of 拒绝重复键，守卫跳过后簇结果不受影响")
        void selfLoopRelationshipDoesNotCrash() {
            var kg = new KnowledgeGraph();
            kg.addNode(node("a"));
            kg.addNode(node("b"));
            kg.addRelationship(new Relationship("a", "a", RelationshipType.SIMILARITY,
                    0.9, false, Map.of()));
            kg.addRelationship(Relationship.of("a", "b", RelationshipType.SIMILARITY, 0.9));

            var clusters = GraphAlgorithms.findNIndirectClusters(kg, rel -> true, 3, 3);

            assertThat(clusters).isNotEmpty();
            assertThat(clusters).allSatisfy(set ->
                    assertThat(set.stream().map(Node::id)).isSubsetOf("a", "b"));
        }

        @Test
        @DisplayName("同图可复现：种子由节点 id 派生，两次调用结果一致")
        void deterministicForSameGraph() {
            var kg = new KnowledgeGraph();
            for (int i = 0; i < 10; i++) {
                kg.addNode(node("n" + i));
            }
            for (int i = 0; i < 10; i++) {
                kg.addRelationship(Relationship.of("n" + i, "n" + (i + 1) % 10,
                        RelationshipType.SIMILARITY, 0.9));
            }

            var first = GraphAlgorithms.findNIndirectClusters(kg, rel -> true, 4, 3);
            var second = GraphAlgorithms.findNIndirectClusters(kg, rel -> true, 4, 3);

            var ids = clustersToIds(first);
            assertThat(ids).isEqualTo(clustersToIds(second));
            assertThat(first).hasSize(4);
        }

        private Set<Set<String>> clustersToIds(List<Set<Node>> clusters) {
            var result = new java.util.LinkedHashSet<Set<String>>();
            clusters.forEach(c -> result.add(
                    Set.copyOf(c.stream().map(Node::id).toList())));
            return result;
        }
    }
}
