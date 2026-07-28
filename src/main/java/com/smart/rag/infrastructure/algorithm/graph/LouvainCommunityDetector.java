package com.smart.rag.infrastructure.algorithm.graph;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Arrays;
import java.util.Set;

/**
 * Louvain community detection (Blondel et al., 2008) — multi-level modularity optimization.
 *
 * <p>Implements the full two-phase Blondel method required by the entity-centric retrieval
 * design (§5.2 ③):
 * <ol>
 *   <li><b>Local moving</b> — each node (visited in deterministic ascending-ID order) greedily
 *       moves to the neighbor community maximizing modularity gain ΔQ. Repeated until no node
 *       moves in a full pass.</li>
 *   <li><b>Aggregation</b> — communities collapse into super-nodes; intra-community edges become
 *       self-loops, inter-community edges are summed. The aggregated graph re-enters phase 1.</li>
 * </ol>
 * Levels repeat until a level's local-moving converges without any move (modularity at a local
 * optimum). The aggregation phase is essential: single-level local-moving over-segments (it
 * cannot merge communities of communities); the canonical Zachary Karate Club benchmark yields
 * a small community count (2–4) with the full method.</p>
 *
 * <p>ΔQ for moving node i from its community <em>old</em> to community C (i removed from old
 * first):
 * <pre>
 *   gain(i→C) = k_{i,C} / m − Σ_tot,C · k_i / (2·m²)
 * </pre>
 * where k_{i,C} = sum of edge weights from i to community C (i's self-loop counts toward its own
 * community), Σ_tot,C = sum of weighted degrees in C, k_i = weighted degree of i (self-loop
 * counts twice), m = Σ w / 2. The node joins the C with maximal gain over re-joining
 * <em>old</em>.</p>
 *
 * <p>Self-loops arise only in aggregated levels (intra-community edges). The initial input is a
 * simple graph read once via {@link WeightedGraph}; the detector then operates on an internal
 * {@link LevelGraph} representation so the {@link WeightedGraph} abstraction stays a clean
 * simple-graph contract for every other consumer (co-occurrence graph loading, weak-tie / bridge
 * scoring).</p>
 *
 * <p>Deterministic: nodes are visited in ascending-ID order at every level, so results are
 * reproducible across runs. No Spring annotations — constructed via {@code new} by downstream
 * services (§5.2 ⑤).</p>
 */
public class LouvainCommunityDetector {

    private static final double EPSILON = 1e-6;
    private static final int MAX_PASSES = 100;
    private static final int MAX_LEVELS = 100;

    private final WeightedGraph graph;

    public LouvainCommunityDetector(WeightedGraph graph) {
        this.graph = graph;
    }

    /**
     * Run multi-level Louvain community detection.
     *
     * @return node → community id mapping (community ids are dense integers starting from 0)
     */
    public Long2IntMap detect() {
        if (graph.nodeCount() == 0) {
            return new Long2IntOpenHashMap();
        }

        long[] origNodes = sortedNodes(graph.nodes());
        int n0 = origNodes.length;

        if (graph.totalWeight() <= 0.0) {
            // No edges — each node is its own community.
            var result = new Long2IntOpenHashMap(n0);
            for (int i = 0; i < n0; i++) {
                result.put(origNodes[i], i);
            }
            return result;
        }
        LevelGraph lg = LevelGraph.from(graph, origNodes);

        // lineage[i] = community index (in the active level graph) of original node origNodes[i].
        int[] lineage = new int[n0];
        for (int i = 0; i < n0; i++) {
            lineage[i] = i;
        }

        for (int level = 0; level < MAX_LEVELS; level++) {
            MoveResult mr = localMoving(lg);

            // Compact the level's community ids to dense [0, k) so they index the next level's
            // super-nodes directly.
            int[] remap = new int[lg.n];
            boolean[] seen = new boolean[lg.n];
            int k = 0;
            for (int i = 0; i < lg.n; i++) {
                int c = mr.community[i];
                if (!seen[c]) {
                    seen[c] = true;
                    remap[c] = k++;
                }
            }

            // Refine lineage: original node → dense community id of its current super-node.
            for (int i = 0; i < n0; i++) {
                lineage[i] = remap[mr.community[lineage[i]]];
            }

            // Termination: local-moving converged (no move) OR aggregation cannot reduce the
            // community count further (every node already singleton).
            if (!mr.moved || k == lg.n) {
                break;
            }

            lg = lg.aggregate(mr.community, remap, k);
        }


        var result = new Long2IntOpenHashMap(n0);
        for (int i = 0; i < n0; i++) {
            result.put(origNodes[i], lineage[i]);
        }
        return result;
    }

    // ---- Phase 1: local moving ----

    /**
     * Greedy single-level local moving on a {@link LevelGraph}. Nodes visited in ascending index
     * order (LevelGraph indices are sorted by node id) for determinism.
     *
     * @return community assignment (indexed) plus whether any node moved
     */
    private MoveResult localMoving(LevelGraph lg) {
        double m = lg.m;
        double twoMSq = 2.0 * m * m;

        int n = lg.n;
        int[] community = new int[n];
        for (int i = 0; i < n; i++) {
            community[i] = i;
        }

        // Σ_tot,C = sum of weighted degrees in community C.
        double[] sigmaTot = new double[n];
        for (int i = 0; i < n; i++) {
            sigmaTot[i] = lg.degree[i];
        }

        boolean globalMoved = false;

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean passMoved = false;

            for (int i = 0; i < n; i++) {
                int[] nbr = lg.nbr[i];
                if (nbr.length == 0 && lg.selfLoop[i] == 0.0) {
                    continue; // isolated node, nothing to join
                }
                double[] wt = lg.wt[i];
                double ki = lg.degree[i];
                int cOld = community[i];

                // k_{i,C}: weight from i to each neighbor community. Self-loop counts toward i's
                // own (old) community. Parallel-array accumulation (no per-node hashmap alloc).
                int[] commBuf = new int[nbr.length + 1];
                double[] wBuf = new double[nbr.length + 1];
                int distinct = 0;
                double kiInOld = lg.selfLoop[i];

                for (int e = 0; e < nbr.length; e++) {
                    int cj = community[nbr[e]];
                    double w = wt[e];
                    if (cj == cOld) {
                        kiInOld += w;
                    } else {
                        boolean found = false;
                        for (int t = 0; t < distinct; t++) {
                            if (commBuf[t] == cj) {
                                wBuf[t] += w;
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            commBuf[distinct] = cj;
                            wBuf[distinct] = w;
                            distinct++;
                        }
                    }
                }

                // Remove i from its community, then evaluate gain of (re)joining each candidate.
                sigmaTot[cOld] -= ki;
                double sigmaTotOldWithoutMe = sigmaTot[cOld];

                // Baseline: gain of re-joining old.
                double bestGain = kiInOld / m - sigmaTotOldWithoutMe * ki / twoMSq;
                int bestComm = cOld;

                for (int t = 0; t < distinct; t++) {
                    int c = commBuf[t];
                    double kiInC = wBuf[t];
                    double gain = kiInC / m - sigmaTot[c] * ki / twoMSq;
                    if (gain > bestGain + EPSILON) {
                        bestGain = gain;
                        bestComm = c;
                    }
                }

                // Place i in best community.
                sigmaTot[bestComm] += ki;
                if (bestComm != cOld) {
                    community[i] = bestComm;
                    passMoved = true;
                    globalMoved = true;
                }
            }

            if (!passMoved) {
                break;
            }
        }

        return new MoveResult(community, globalMoved);
    }

    // ---- Internal level representation (supports self-loops) ----

    /**
     * Immutable indexed level graph. Neighbor indices, parallel weights, explicit self-loops.
     * Built once from the input {@link WeightedGraph} (level 0, no self-loops) and rebuilt by
     * {@link #aggregate} for each subsequent level (with self-loops).
     */
    private static final class LevelGraph {
        final int n;
        final int[][] nbr;       // nbr[i] = neighbor indices (no self entries)
        final double[][] wt;     // wt[i] = parallel weights
        final double[] selfLoop; // selfLoop[i] = self-loop weight (0 at level 0)
        final double[] degree;   // degree[i] = sum(wt[i]) + 2·selfLoop[i]
        final double m;          // total edge weight = sum(degree) / 2

        private LevelGraph(int n, int[][] nbr, double[][] wt, double[] selfLoop) {
            this.n = n;
            this.nbr = nbr;
            this.wt = wt;
            this.selfLoop = selfLoop;
            this.degree = new double[n];
            double sumDeg = 0.0;
            for (int i = 0; i < n; i++) {
                double s = 0.0;
                for (double w : wt[i]) {
                    s += w;
                }
                degree[i] = s + 2.0 * selfLoop[i];
                sumDeg += degree[i];
            }
            this.m = sumDeg / 2.0;
        }

        /** Build level 0 from the simple-graph interface (no self-loops). */
        static LevelGraph from(WeightedGraph g, long[] sortedNodes) {
            int n = sortedNodes.length;
            var nodeToIndex = new Long2IntOpenHashMap(n);
            for (int i = 0; i < n; i++) {
                nodeToIndex.put(sortedNodes[i], i);
            }
            int[][] nbr = new int[n][];
            double[][] wt = new double[n][];
            for (int i = 0; i < n; i++) {
                Long2DoubleMap neigh = g.neighbors(sortedNodes[i]);
                int deg = neigh.size();
                nbr[i] = new int[deg];
                wt[i] = new double[deg];
                int e = 0;
                for (var entry : neigh.long2DoubleEntrySet()) {
                    nbr[i][e] = nodeToIndex.get(entry.getLongKey());
                    wt[i][e] = entry.getDoubleValue();
                    e++;
                }
            }
            return new LevelGraph(n, nbr, wt, new double[n]);
        }

        /**
         * Collapse communities into super-nodes. Intra-community edges (and carried self-loops)
         * become the super-node self-loop; inter-community edges sum between super-nodes.
         * Total weight m is conserved.
         *
         * @param community level community assignment (raw ids)
         * @param remap     raw id → dense super-node index [0, k)
         * @param k         number of super-nodes
         */
        LevelGraph aggregate(int[] community, int[] remap, int k) {
            // Accumulate inter-community edges into per-super-node weight maps keyed by neighbor
            // super-node index. Long2DoubleOpenHashMap keeps the hot path allocation-light.
            var acc = new Long2DoubleOpenHashMap[k];
            for (int s = 0; s < k; s++) {
                acc[s] = new Long2DoubleOpenHashMap();
            }
            double[] newSelfLoop = new double[k];

            for (int i = 0; i < n; i++) {
                int ci = remap[community[i]];
                int[] ni = nbr[i];
                double[] wi = wt[i];
                for (int e = 0; e < ni.length; e++) {
                    int j = ni[e];
                    if (j <= i) {
                        continue; // each undirected edge once (i < j); adjacency is symmetric
                    }
                    int cj = remap[community[j]];
                    double w = wi[e];
                    if (ci == cj) {
                        newSelfLoop[ci] += w;
                    } else {
                        acc[ci].merge((long) cj, w, Double::sum);
                        acc[cj].merge((long) ci, w, Double::sum); // undirected → both directions
                    }
                }
                // Carried self-loop of node i becomes intra weight of its super-node.
                newSelfLoop[ci] += selfLoop[i];
            }

            int[][] newNbr = new int[k][];
            double[][] newWt = new double[k][];
            for (int s = 0; s < k; s++) {
                var entries = acc[s].long2DoubleEntrySet();
                int deg = entries.size();
                newNbr[s] = new int[deg];
                newWt[s] = new double[deg];
                int e = 0;
                for (var entry : entries) {
                    newNbr[s][e] = (int) entry.getLongKey();
                    newWt[s][e] = entry.getDoubleValue();
                    e++;
                }
            }
            return new LevelGraph(k, newNbr, newWt, newSelfLoop);
        }
    }

    private record MoveResult(int[] community, boolean moved) {}

    // ---- Utility ----

    /**
     * Sort a {@code Set<Long>} into a sorted {@code long[]}.
     */
    private static long[] sortedNodes(Set<Long> nodes) {
        long[] arr = new long[nodes.size()];
        int i = 0;
        for (long n : nodes) {
            arr[i++] = n;
        }
        Arrays.sort(arr);
        return arr;
    }
}
