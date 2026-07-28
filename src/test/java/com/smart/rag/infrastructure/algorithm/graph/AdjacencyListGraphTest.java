package com.smart.rag.infrastructure.algorithm.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AdjacencyListGraph}.
 *
 * <p>Covers AC2 (undirected weight accumulation), AC3 (isolated node), AC4 (K5),
 * and additional edge cases.</p>
 */
class AdjacencyListGraphTest {

    private AdjacencyListGraph graph;

    @BeforeEach
    void setUp() {
        graph = new AdjacencyListGraph();
    }

    // === AC3: Isolated node handling ===

    @Test
    @DisplayName("AC3: addNode registers isolated node")
    void addNode_isolatedNode_inNodes() {
        graph.addNode(99);

        assertThat(graph.nodes()).contains(99L);
        assertThat(graph.nodeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC3: isolated node has empty neighbors")
    void addNode_isolatedNode_neighborsEmpty() {
        graph.addNode(99);

        assertThat(graph.neighbors(99)).isEmpty();
    }

    @Test
    @DisplayName("AC3: isolated node has zero weighted degree")
    void addNode_isolatedNode_weightedDegreeZero() {
        graph.addNode(99);

        assertThat(graph.weightedDegree(99)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    // === AC2: Undirected weight accumulation ===

    @Test
    @DisplayName("AC2: addEdge undirected — weight accumulates (3+2=5)")
    void addEdge_undirectedWeightAccumulation() {
        graph.addEdge(1, 2, 3.0);
        graph.addEdge(2, 1, 2.0);

        assertThat(graph.edgeWeight(1, 2)).isCloseTo(5.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(graph.edgeWeight(2, 1)).isCloseTo(5.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(graph.weightedDegree(1)).isCloseTo(5.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(graph.weightedDegree(2)).isCloseTo(5.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    // === Basic edge cases ===

    @Test
    @DisplayName("edgeWeight returns 0 for non-existent edge")
    void edgeWeight_noEdge_returnsZero() {
        graph.addNode(1);
        graph.addNode(2);

        assertThat(graph.edgeWeight(1, 2)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("nodes returns all nodes including isolated")
    void nodes_returnsAllNodesIncludingIsolated() {
        graph.addEdge(1, 2, 1.0);
        graph.addNode(99);

        assertThat(graph.nodes()).containsExactlyInAnyOrder(1L, 2L, 99L);
        assertThat(graph.nodeCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("totalWeight returns m = sum(w)/2 for undirected graph")
    void totalWeight_singleEdge_returnsHalfSum() {
        graph.addEdge(1, 2, 3.0);

        assertThat(graph.totalWeight()).isCloseTo(3.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("empty graph has nodeCount zero")
    void emptyGraph_nodeCountZero() {
        assertThat(graph.nodeCount()).isZero();
        assertThat(graph.totalWeight()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(graph.nodes()).isEmpty();
    }

    // === AC4: Complete graph K5 ===

    @Test
    @DisplayName("AC4: K5 complete graph — neighbors and degrees correct")
    void completeGraph_K5_noException() {
        // K5: 5 nodes, all pairwise connected with weight 1
        for (int i = 1; i <= 5; i++) {
            for (int j = i + 1; j <= 5; j++) {
                graph.addEdge(i, j, 1.0);
            }
        }

        assertThat(graph.nodeCount()).isEqualTo(5);
        // Each node in K5 has 4 neighbors, each weight 1 → weighted degree = 4
        for (int i = 1; i <= 5; i++) {
            assertThat(graph.weightedDegree(i)).isCloseTo(4.0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(graph.neighbors(i)).hasSize(4);
        }
        // Total weight: C(5,2) = 10 edges, m = 10
        assertThat(graph.totalWeight()).isCloseTo(10.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("addEdge auto-registers both endpoints")
    void addEdge_autoRegistersEndpoints() {
        graph.addEdge(10, 20, 1.0);

        assertThat(graph.nodes()).containsExactlyInAnyOrder(10L, 20L);
        assertThat(graph.nodeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("self-loop is ignored (simple graph)")
    void addEdge_selfLoop_ignored() {
        graph.addEdge(1, 1, 5.0);

        assertThat(graph.edgeWeight(1, 1)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(graph.weightedDegree(1)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
    }
}
