package com.smart.rag.infrastructure.algorithm.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LouvainCommunityDetector}.
 *
 * <p>Covers AC1 (Zachary Karate Club headline), AC4 (K5 edge case),
 * AC5 (modularity range), AC6 (performance budget), plus synthetic and edge cases.</p>
 */
class LouvainCommunityDetectorTest {

    // === AC1: Zachary Karate Club headline ===

    /**
     * Zachary Karate Club standard graph: 34 nodes, 78 edges.
     * Edges are 1-indexed per the standard dataset (Wayne Zachary, 1977).
     *
     * <p>Full multi-level Louvain (local-moving + aggregation, §5.2 ③) on this canonical
     * benchmark detects a small number of communities whose count depends on traversal order;
     * deterministic ascending-ID traversal lands in the 2–7 range (the classic randomized
     * result is 2). The two structurally-guaranteed properties — the headline acceptance —
     * are that (a) Mr. Hi (node 1) and John A. (node 34) split into different communities,
     * and (b) modularity Q is significantly positive (a degenerate all-singletons split would
     * give Q=0). Per design.md OQ2, AC1 asserts these structural properties rather than a
     * brittle exact community count.</p>
     */
    @Test
    @DisplayName("AC1: Zachary Karate Club — node 1 vs 34 separate, Q > 0.3")
    void zacharyKarateClub_twoCommunities_node1Vs34Separate() {
        var graph = new AdjacencyListGraph();
        loadKarateClub(graph);

        var detector = new LouvainCommunityDetector(graph);
        var communities = detector.detect();

        // Count unique communities.
        var uniqueComms = new java.util.HashSet<Integer>();
        for (int c : communities.values()) {
            uniqueComms.add(c);
        }

        // Deterministic multi-level Louvain produces ≥2 meaningful communities.
        assertThat(uniqueComms).as("community count").hasSizeBetween(2, 7);

        // Mr. Hi and John A. must land in different communities.
        assertThat(communities.get(1)).as("node 1 community")
                .isNotEqualTo(communities.get(34));

        // Modularity must be significantly positive — proves the partition is structural,
        // not a degenerate singletons split (which would yield Q=0).
        double m = graph.totalWeight();
        double twoM = 2.0 * m;
        double q = 0.0;
        for (long node : graph.nodes()) {
            int ci = communities.get(node);
            double ki = graph.weightedDegree(node);
            for (var e : graph.neighbors(node).long2DoubleEntrySet()) {
                long nb = e.getLongKey();
                if (communities.get(nb) == ci) {
                    double kj = graph.weightedDegree(nb);
                    q += e.getDoubleValue() - (ki * kj) / twoM;
                }
            }
        }
        q /= twoM;
        assertThat(q).as("modularity Q significantly positive").isGreaterThan(0.3);
    }

    // === Synthetic 3-clique graph ===

    @Test
    @DisplayName("3-clique synthetic graph — at least 3 communities detected")
    void threeCliques_atLeast3Communities() {
        var graph = new AdjacencyListGraph();

        // 3 cliques of 10 nodes each, intra-clique weight = 1
        for (int clique = 0; clique < 3; clique++) {
            int base = clique * 10;
            for (int i = 0; i < 10; i++) {
                for (int j = i + 1; j < 10; j++) {
                    graph.addEdge(base + i, base + j, 1.0);
                }
            }
        }

        // Bridge edges between cliques with low weight
        graph.addEdge(9, 10, 0.1);  // clique0 → clique1
        graph.addEdge(19, 20, 0.1); // clique1 → clique2

        var detector = new LouvainCommunityDetector(graph);
        var communities = detector.detect();

        var uniqueComms = new java.util.HashSet<Integer>();
        for (int c : communities.values()) {
            uniqueComms.add(c);
        }

        assertThat(uniqueComms).as("community count").hasSizeGreaterThanOrEqualTo(3);
    }

    // === Edge cases ===

    @Test
    @DisplayName("empty graph — empty result")
    void emptyGraph_emptyResult() {
        var graph = new AdjacencyListGraph();
        var detector = new LouvainCommunityDetector(graph);

        var communities = detector.detect();
        assertThat(communities).isEmpty();
    }

    @Test
    @DisplayName("single node — 1 community")
    void singleNode_oneCommunity() {
        var graph = new AdjacencyListGraph();
        graph.addNode(1);

        var detector = new LouvainCommunityDetector(graph);
        var communities = detector.detect();

        assertThat(communities).hasSize(1);
        assertThat(communities.get(1)).isEqualTo(0);
    }

    @Test
    @DisplayName("isolated node + connected component — no crash")
    void isolatedNode_withConnectedComponent_noCrash() {
        var graph = new AdjacencyListGraph();

        // Connected component: 1-2-3 triangle
        graph.addEdge(1, 2, 1.0);
        graph.addEdge(2, 3, 1.0);
        graph.addEdge(1, 3, 1.0);

        // Isolated node
        graph.addNode(99);

        var detector = new LouvainCommunityDetector(graph);
        var communities = detector.detect();

        // Should not crash and should assign all nodes
        assertThat(communities).hasSize(4);
    }

    // === AC4: K5 edge case ===

    @Test
    @DisplayName("AC4: K5 — Louvain does not throw")
    void completeGraph_K5_noException() {
        var graph = new AdjacencyListGraph();
        for (int i = 1; i <= 5; i++) {
            for (int j = i + 1; j <= 5; j++) {
                graph.addEdge(i, j, 1.0);
            }
        }

        var detector = new LouvainCommunityDetector(graph);
        var communities = detector.detect();

        assertThat(communities).hasSize(5);
    }

    // === AC5: Modularity in [0, 1] ===

    @Test
    @DisplayName("AC5: modularity of known 2-community graph is in [0, 1]")
    void modularity_twoCommunityGraph_inRange() {
        var graph = new AdjacencyListGraph();

        // Two clear communities: A={1,2,3} B={4,5,6}
        // Dense intra-community, sparse inter-community
        graph.addEdge(1, 2, 1.0);
        graph.addEdge(1, 3, 1.0);
        graph.addEdge(2, 3, 1.0);

        graph.addEdge(4, 5, 1.0);
        graph.addEdge(4, 6, 1.0);
        graph.addEdge(5, 6, 1.0);

        // Single bridge
        graph.addEdge(3, 4, 0.1);

        var detector = new LouvainCommunityDetector(graph);
        var communities = detector.detect();

        // Compute modularity Q
        double m = graph.totalWeight();
        double twoM = 2.0 * m;
        double q = 0.0;
        for (long node : graph.nodes()) {
            int ci = communities.get(node);
            double ki = graph.weightedDegree(node);
            var neighborMap = graph.neighbors(node);
            for (var entry : neighborMap.long2DoubleEntrySet()) {
                long neighbor = entry.getLongKey();
                int cj = communities.get(neighbor);
                if (ci == cj) {
                    double aij = entry.getDoubleValue();
                    double kj = graph.weightedDegree(neighbor);
                    q += aij - (ki * kj) / twoM;
                }
            }
        }
        q /= twoM;

        assertThat(q).as("modularity Q").isBetween(0.0, 1.0);
        // For this clearly 2-community graph, Q should be significantly positive
        assertThat(q).as("modularity Q significantly positive").isGreaterThan(0.1);
    }

    // === AC6: Performance budget ===

    @Test
    @DisplayName("AC6: V=10⁴ E=10⁵ — detect() < 1s")
    @Timeout(5) // JUnit timeout as safety net
    void performance_V10k_E100k_underBudget() {
        var rng = new Random(42);
        var graph = new AdjacencyListGraph();
        int v = 10_000;
        int e = 100_000;

        // Generate random edges with fixed seed for reproducibility
        for (int i = 0; i < e; i++) {
            long a = rng.nextLong(v);
            long b = rng.nextLong(v);
            if (a == b) { a = (a + 1) % v; }
            if (a == b) { a = (a + 1) % v; }
            graph.addEdge(a, b, 1.0);
        }

        var detector = new LouvainCommunityDetector(graph);

        long start = System.nanoTime();
        var communities = detector.detect();
        long elapsed = System.nanoTime() - start;

        assertThat(communities).isNotEmpty();
        assertThat(elapsed).as("elapsed ms")
                .isLessThan(1_000_000_000L); // 1s for CI slack
    }

    // === Helpers ===

    /**
     * Load the canonical Zachary Karate Club edges (34 nodes, 78 edges, unweighted).
     * Standard 1-indexed edge list derived from networkx's {@code karate_club_graph()}.
     * Reference: Wayne W. Zachary, "An Information Flow Model for Conflict and Fission
     * in Small Groups," J. Anthropological Research 33, 452–473 (1977).
     * The two factions: node 1 = Mr. Hi (instructor), node 34 = John A. (officer).
     */
    private static void loadKarateClub(AdjacencyListGraph graph) {
        int[][] edges = {
            {1, 2}, {1, 3}, {1, 4}, {1, 5}, {1, 6}, {1, 7},
            {1, 8}, {1, 9}, {1, 11}, {1, 12}, {1, 13}, {1, 14},
            {1, 18}, {1, 20}, {1, 22}, {1, 32}, {2, 3}, {2, 4},
            {2, 8}, {2, 14}, {2, 18}, {2, 20}, {2, 22}, {2, 31},
            {3, 4}, {3, 8}, {3, 9}, {3, 10}, {3, 14}, {3, 28},
            {3, 29}, {3, 33}, {4, 8}, {4, 13}, {4, 14}, {5, 7},
            {5, 11}, {6, 7}, {6, 11}, {6, 17}, {7, 17}, {9, 31},
            {9, 33}, {9, 34}, {10, 34}, {14, 34}, {15, 33}, {15, 34},
            {16, 33}, {16, 34}, {19, 33}, {19, 34}, {20, 34}, {21, 33},
            {21, 34}, {23, 33}, {23, 34}, {24, 26}, {24, 28}, {24, 30},
            {24, 33}, {24, 34}, {25, 26}, {25, 28}, {25, 32}, {26, 32},
            {27, 30}, {27, 34}, {28, 34}, {29, 32}, {29, 34}, {30, 33},
            {30, 34}, {31, 33}, {31, 34}, {32, 33}, {32, 34}, {33, 34}
        };
        for (var e : edges) {
            graph.addEdge(e[0], e[1], 1.0);
        }
    }
}
