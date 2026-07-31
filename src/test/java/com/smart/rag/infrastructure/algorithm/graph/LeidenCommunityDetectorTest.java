package com.smart.rag.infrastructure.algorithm.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LeidenCommunityDetector}.
 *
 * <p>Covers AC1 (Zachary Karate Club headline), AC4 (K5 edge case),
 * AC5 (modularity range), AC6 (performance budget), the headline Leiden guarantee
 * (every community internally connected), determinism, and the resolution parameter
 * (design.md OQ1/OQ2), plus synthetic and edge cases.</p>
 */
class LeidenCommunityDetectorTest {

    // === AC1: Zachary Karate Club headline ===

    /**
     * Zachary Karate Club standard graph: 34 nodes, 78 edges.
     * Edges are 1-indexed per the standard dataset (Wayne Zachary, 1977).
     *
     * <p>Deterministic multi-level Leiden on this canonical benchmark detects a small
     * number of communities: the classic ground-truth result is 2, but the deterministic
     * θ→0 refinement splits badly-connected sub-structures into well-connected
     * sub-communities, landing in the 2–7 range (per design.md OQ2, AC1 asserts structural
     * properties rather than a brittle exact count). The two structurally-guaranteed
     * properties — the headline acceptance — are that (a) Mr. Hi (node 1) and John A.
     * (node 34) split into different communities, and (b) modularity Q is significantly
     * positive (a degenerate all-singletons split would give Q=0).</p>
     */
    @Test
    @DisplayName("AC1: Zachary Karate Club — node 1 vs 34 separate, Q > 0.3")
    void zacharyKarateClub_twoCommunities_node1Vs34Separate() {
        var graph = new AdjacencyListGraph();
        loadKarateClub(graph);

        var detector = new LeidenCommunityDetector(graph);
        var communities = detector.detect();

        // Count unique communities.
        var uniqueComms = new HashSet<Integer>();
        for (int c : communities.values()) {
            uniqueComms.add(c);
        }

        // Deterministic multi-level Leiden produces ≥2 meaningful communities.
        assertThat(uniqueComms).as("community count").hasSizeBetween(2, 7);

        // Mr. Hi and John A. must land in different communities.
        assertThat(communities.get(1)).as("node 1 community")
                .isNotEqualTo(communities.get(34));

        // Modularity must be significantly positive — proves the partition is structural,
        // not a degenerate singletons split (which would yield Q=0).
        double q = modularity(graph, communities);
        assertThat(q).as("modularity Q significantly positive").isGreaterThan(0.3);
    }

    // === Headline Leiden guarantee: well-connected communities ===

    /**
     * The whole point of replacing Louvain with Leiden (§5.2 ③): every detected community
     * must be internally connected, so {@code bridge_score} (§5.2 ④) counts only genuine
     * cross-community bridges. Verified by BFS over intra-community edges on a noisy
     * synthetic graph (3 cliques + bridges + random noise edges).
     */
    @Test
    @DisplayName("well-connectedness — every community is internally connected")
    void allCommunitiesInternallyConnected() {
        var graph = new AdjacencyListGraph();
        var rng = new Random(7);

        // 3 cliques of 10 nodes, intra weight 1.
        for (int clique = 0; clique < 3; clique++) {
            int base = clique * 10;
            for (int i = 0; i < 10; i++) {
                for (int j = i + 1; j < 10; j++) {
                    graph.addEdge(base + i, base + j, 1.0);
                }
            }
        }
        // Bridge edges between cliques.
        graph.addEdge(9, 10, 0.1);
        graph.addEdge(19, 20, 0.1);
        // Noise edges with low weight — makes the partition non-trivial.
        for (int e = 0; e < 40; e++) {
            long a = rng.nextInt(30);
            long b = rng.nextInt(30);
            if (a != b) {
                graph.addEdge(a, b, 0.05);
            }
        }

        var detector = new LeidenCommunityDetector(graph);
        var communities = detector.detect();

        assertThat(communities).isNotEmpty();
        assertThat(allCommunitiesConnected(graph, communities))
                .as("every community internally connected")
                .isTrue();
    }

    // === Determinism ===

    @Test
    @DisplayName("determinism — two runs produce identical partitions")
    void determinism_twoRunsIdentical() {
        var graph = new AdjacencyListGraph();
        loadKarateClub(graph);
        graph.addEdge(100, 101, 3.0);
        graph.addEdge(101, 102, 3.0);
        graph.addEdge(100, 102, 3.0);
        graph.addNode(999); // isolated node

        var first = new LeidenCommunityDetector(graph).detect();
        var second = new LeidenCommunityDetector(graph).detect();

        assertThat(first).as("identical partitions across runs").isEqualTo(second);
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

        var detector = new LeidenCommunityDetector(graph);
        var communities = detector.detect();

        var uniqueComms = new HashSet<Integer>();
        for (int c : communities.values()) {
            uniqueComms.add(c);
        }

        assertThat(uniqueComms).as("community count").hasSizeGreaterThanOrEqualTo(3);
    }

    // === Resolution parameter (design.md OQ1) ===

    @Test
    @DisplayName("resolution — γ=2.0 splits K5 into singletons, γ=0.01 merges it into one")
    void resolution_controlsGranularity() {
        var graph = new AdjacencyListGraph();
        for (int i = 1; i <= 5; i++) {
            for (int j = i + 1; j <= 5; j++) {
                graph.addEdge(i, j, 1.0);
            }
        }

        // High resolution: every node prefers its own community (ΔQ of any merge is
        // 1/10 − γ·4·4/200 = 0.1 − 0.08·γ < 0 for γ = 2).
        var fine = new LeidenCommunityDetector(graph, 2.0).detect();
        assertThat(new HashSet<>(fine.values())).as("γ=2.0 community count").hasSize(5);

        // Low resolution: everything merges into a single community.
        var coarse = new LeidenCommunityDetector(graph, 0.01).detect();
        assertThat(new HashSet<>(coarse.values())).as("γ=0.01 community count").hasSize(1);
    }

    // === Edge cases ===

    @Test
    @DisplayName("empty graph — empty result")
    void emptyGraph_emptyResult() {
        var graph = new AdjacencyListGraph();
        var detector = new LeidenCommunityDetector(graph);

        var communities = detector.detect();
        assertThat(communities).isEmpty();
    }

    @Test
    @DisplayName("single node — 1 community")
    void singleNode_oneCommunity() {
        var graph = new AdjacencyListGraph();
        graph.addNode(1);

        var detector = new LeidenCommunityDetector(graph);
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

        var detector = new LeidenCommunityDetector(graph);
        var communities = detector.detect();

        // Should not crash and should assign all nodes
        assertThat(communities).hasSize(4);
    }

    // === AC4: K5 edge case ===

    @Test
    @DisplayName("AC4: K5 — Leiden does not throw")
    void completeGraph_K5_noException() {
        var graph = new AdjacencyListGraph();
        for (int i = 1; i <= 5; i++) {
            for (int j = i + 1; j <= 5; j++) {
                graph.addEdge(i, j, 1.0);
            }
        }

        var detector = new LeidenCommunityDetector(graph);
        var communities = detector.detect();

        // All 5 nodes assigned, no exception.
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

        var detector = new LeidenCommunityDetector(graph);
        var communities = detector.detect();

        double q = modularity(graph, communities);
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

        var detector = new LeidenCommunityDetector(graph);

        long start = System.nanoTime();
        var communities = detector.detect();
        long elapsed = System.nanoTime() - start;

        assertThat(communities).isNotEmpty();
        assertThat(elapsed).as("elapsed ms")
                .isLessThan(1_000_000_000L); // 1s for CI slack
    }

    // === Helpers ===

    /**
     * Modularity Q of the partition on the original graph.
     */
    private static double modularity(WeightedGraph graph, Map<Long, Integer> communities) {
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
        return q / twoM;
    }

    /**
     * True iff every community induces a connected subgraph (BFS over intra-community
     * edges). Singleton communities are trivially connected.
     */
    private static boolean allCommunitiesConnected(WeightedGraph graph, Map<Long, Integer> communities) {
        Map<Integer, Set<Long>> members = new HashMap<>();
        for (var e : communities.entrySet()) {
            members.computeIfAbsent(e.getValue(), k -> new HashSet<>()).add(e.getKey());
        }
        for (var entry : members.entrySet()) {
            Set<Long> comm = entry.getValue();
            Set<Long> seen = new HashSet<>();
            java.util.ArrayDeque<Long> stack = new java.util.ArrayDeque<>();
            long start = comm.iterator().next();
            stack.push(start);
            seen.add(start);
            while (!stack.isEmpty()) {
                long v = stack.pop();
                for (long u : graph.neighbors(v).keySet()) {
                    if (comm.contains(u) && seen.add(u)) {
                        stack.push(u);
                    }
                }
            }
            if (!seen.equals(comm)) {
                return false;
            }
        }
        return true;
    }

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
