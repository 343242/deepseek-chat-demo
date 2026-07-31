package com.smart.rag.infrastructure.algorithm.graph;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Arrays;
import java.util.Set;

/**
 * Leiden community detection (Traag, Waltman &amp; van Eck, 2019, "From Louvain to Leiden:
 * guaranteeing well-connected communities") — multi-level modularity optimization that
 * replaces {@code LouvainCommunityDetector} (§5.2 ③).
 *
 * <p>Unlike Louvain, Leiden inserts a <b>refinement</b> phase between local moving and
 * aggregation (Algorithm A.2 of the paper):
 * <ol>
 *   <li><b>Fast local moving</b> — queue-based greedy moves (only nodes whose neighborhood
 *       changed are revisited), including a "move to an empty community" candidate so a
 *       badly connected node can leave its community.</li>
 *   <li><b>Refinement</b> — every community of the local-moving partition is split into
 *       well-connected sub-communities. Each sub-community must satisfy
 *       {@code E(C, S−C) ≥ γ·K_C·(K_S − K_C)/(2m)} (well connected to its parent), each
 *       merging node {@code E(v, S−v) ≥ γ·k_v·(K_S − k_v)/(2m)}.</li>
 *   <li><b>Aggregation</b> — the aggregate graph is built on the <em>refined</em> partition,
 *       while the next level's initial partition groups super-nodes by the <em>unrefined</em>
 *       partition (line 8 of Algorithm A.2).</li>
 * </ol>
 * This guarantees every detected community is internally connected (γ-connected), the
 * property Louvain lacks — critical for {@code bridge_score} (§5.2 ④), which counts
 * cross-community neighbors: a disconnected "community" would misattribute bridges.
 *
 * <p><b>Determinism</b>: the paper's refinement selects the merge target randomly with
 * probability ∝ exp(ΔQ/θ); we use the θ→0 limit (always the max-ΔQ well-connected target)
 * and visit nodes in ascending-ID order at every phase, so results are reproducible across
 * runs (design.md OQ2: deterministic traversal). This keeps the per-iteration guarantees
 * (γ-separation, γ-connectivity); the paper's asymptotic subset-optimality guarantee relies
 * on θ &gt; 0 and is deliberately traded for reproducibility.
 *
 * <p><b>Quality function</b>: modularity with resolution γ (constructor parameter, default
 * 1.0 per design.md OQ1), using the paper's node-weight convention ∥v∥ = k_v (weighted
 * degree) and threshold normalization γ/(2m) — identical to the reference implementation
 * (igraph {@code igraph_community_leiden}). ΔQ for moving node i to community C (i removed
 * from its old community first):
 * <pre>
 *   add(i→C) = k_{i,C}/m − γ·Σ_tot,C·k_i/(2m²)
 * </pre>
 * where k_{i,C} = edge weight from i to C, Σ_tot,C = sum of weighted degrees in C,
 * k_i = weighted degree of i (self-loops count twice), m = total weight. The move happens
 * iff {@code add(best) > add(old) + ε}; the "empty community" candidate has add-gain 0.
 * Self-loops arise only in aggregated levels; they count toward the node's own community
 * (same convention as the previous Louvain detector).
 *
 * <p><b>Reference implementation parity</b>: queue semantics, empty-cluster id recycling,
 * the "refinement merged nothing → aggregate on the unrefined partition" fallback, and the
 * strictly-positive-improvement rule follow the igraph C implementation of Leiden.
 *
 * <p>Deterministic multi-level pass (single pass, not the paper's iterated variant), capped
 * by {@link #MAX_LEVELS} levels and {@link #MAX_EVALS_PER_NODE} local-moving evaluations per
 * node per level. No Spring annotations — constructed via {@code new} (§5.2 ⑤).
 */
public class LeidenCommunityDetector {

    private static final double EPSILON = 1e-6;
    private static final int MAX_LEVELS = 100;

    /** Safety cap on local-moving queue evaluations per node (igraph has no cap; this bounds pathological churn). */
    private static final int MAX_EVALS_PER_NODE = 100;

    private final WeightedGraph graph;
    private final double resolution;

    public LeidenCommunityDetector(WeightedGraph graph) {
        this(graph, 1.0);
    }

    /**
     * @param graph      undirected weighted simple graph
     * @param resolution resolution parameter γ of the modularity quality function
     *                   (higher → finer communities); must be &gt; 0
     */
    public LeidenCommunityDetector(WeightedGraph graph, double resolution) {
        if (resolution <= 0.0) {
            throw new IllegalArgumentException("resolution must be positive: " + resolution);
        }
        this.graph = graph;
        this.resolution = resolution;
    }

    /**
     * Run multi-level Leiden community detection.
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

        // lineage[i] = node index (in the active level graph) of original node origNodes[i].
        int[] lineage = new int[n0];
        for (int i = 0; i < n0; i++) {
            lineage[i] = i;
        }

        // Level 0 starts from the singleton partition; later levels start grouped by the
        // previous level's unrefined (pre-refinement) partition (Algorithm A.2, line 8).
        int[] initComm = new int[n0];
        for (int i = 0; i < n0; i++) {
            initComm[i] = i;
        }

        for (int level = 0; level < MAX_LEVELS; level++) {
            MoveResult mr = moveNodesFast(lg, initComm);

            // Terminate when local moving yields an all-singleton partition (|P| = |V|):
            // flat*(P) is then exactly the current level nodes themselves.
            if (mr.k == lg.n) {
                return toMap(origNodes, lineage);
            }

            Refinement ref = refinePartition(lg, mr.community);

            // igraph parity: if refinement merged nothing, aggregate on the unrefined
            // partition instead (avoids a pointless aggregation step).
            if (ref.count == lg.n) {
                ref = new Refinement(densify(mr.community), mr.k);
            }

            // Map base nodes into the next level graph: lineage[i] = the refined
            // super-node of the current level node containing base node i.
            for (int i = 0; i < n0; i++) {
                lineage[i] = ref.dense[lineage[i]];
            }

            lg = lg.aggregate(ref.dense, ref.count);
            initComm = nextInitialPartition(mr.community, mr.k, ref.dense, ref.count);
        }

        // MAX_LEVELS safety cap: the current level nodes are the communities.
        return toMap(origNodes, lineage);
    }

    // ---- Phase 1: fast local moving (MoveNodesFast, Algorithm A.2 lines 13-25) ----

    /**
     * Queue-based greedy local moving. Every node is enqueued once initially; a moved node
     * re-enqueues its neighbors that are not in its new community (fast local move). Nodes
     * are popped in ascending-index order (determinism). A node may be processed multiple
     * times, never while already queued.
     *
     * @param initComm initial cluster id per node (ids in [0, n); level 0: identity,
     *                 later levels: groups from {@link #nextInitialPartition})
     * @return raw cluster assignment plus the number of occupied clusters
     */
    private MoveResult moveNodesFast(LevelGraph lg, int[] initComm) {
        int n = lg.n;
        double m = lg.m;
        double twoMSq = 2.0 * m * m;

        // Cluster ids are recycled integers in [0, n): occupied ids have size > 0, freed
        // ids live on the empty stack (igraph parity). This gives the "move to empty
        // community" candidate an id distinct from every live cluster.
        int[] community = initComm.clone();
        double[] sigmaTot = new double[n];
        int[] size = new int[n];
        for (int v = 0; v < n; v++) {
            sigmaTot[community[v]] += lg.degree[v];
            size[community[v]]++;
        }
        int[] emptyStack = new int[n];
        int emptyTop = 0;
        for (int c = 0; c < n; c++) {
            if (size[c] == 0) {
                emptyStack[emptyTop++] = c;
            }
        }

        boolean[] inQueue = new boolean[n];
        int[] queue = new int[n];
        int head = 0;
        int tail = 0;
        for (int v = 0; v < n; v++) {
            queue[tail++] = v;
            inQueue[v] = true;
        }

        int maxEvals = MAX_EVALS_PER_NODE * n + n;
        int evals = 0;
        while (head < tail && evals < maxEvals) {
            int v = queue[head++];
            inQueue[v] = false;
            evals++;

            int cOld = community[v];
            double k_v = lg.degree[v];
            int[] nbr = lg.nbr[v];
            double[] wt = lg.wt[v];

            // Remove v from its current cluster.
            sigmaTot[cOld] -= k_v;
            size[cOld]--;
            boolean freed = false;
            if (size[cOld] == 0) {
                emptyStack[emptyTop++] = cOld;
                freed = true;
            }

            // k_{v,C}: weight from v to each neighbor cluster. Self-loop counts toward v's
            // own (old) cluster (modularity convention, same as the previous detector).
            int[] commBuf = new int[nbr.length + 1];
            double[] wBuf = new double[nbr.length + 1];
            int distinct = 0;
            double kiInOld = lg.selfLoop[v];
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

            // Baseline: gain of re-joining the old cluster (v removed).
            double bestGain = kiInOld / m - resolution * sigmaTot[cOld] * k_v / twoMSq;
            int bestComm = cOld;

            for (int t = 0; t < distinct; t++) {
                int c = commBuf[t];
                double gain = wBuf[t] / m - resolution * sigmaTot[c] * k_v / twoMSq;
                if (gain > bestGain + EPSILON) {
                    bestGain = gain;
                    bestComm = c;
                }
            }

            // Empty-community candidate (add-gain 0, Algorithm A.2 line 17 "P ∪ {∅}"):
            // lets a badly connected node leave its community instead of being stuck.
            if (0.0 > bestGain + EPSILON && emptyTop > 0) {
                bestComm = emptyStack[emptyTop - 1];
            }

            if (bestComm == cOld) {
                if (freed) {
                    emptyTop--; // undo: v stays in the just-vacated cluster
                }
                sigmaTot[cOld] += k_v;
                size[cOld]++;
                continue;
            }

            if (emptyTop > 0 && bestComm == emptyStack[emptyTop - 1]) {
                emptyTop--; // occupy the freed id
            }
            community[v] = bestComm;
            sigmaTot[bestComm] += k_v;
            size[bestComm]++;

            // Fast local move: re-evaluate neighbors not in v's new cluster.
            for (int e = 0; e < nbr.length; e++) {
                int u = nbr[e];
                if (community[u] != bestComm && !inQueue[u]) {
                    inQueue[u] = true;
                    if (tail == queue.length) {
                        queue = Arrays.copyOf(queue, queue.length * 2); // nodes may re-enter many times
                    }
                    queue[tail++] = u;
                }
            }
        }

        return new MoveResult(community, n - emptyTop);
    }

    // ---- Phase 2: refinement (RefinePartition / MergeNodesSubset, Algorithm A.2 lines 26-43) ----

    private record Refinement(int[] dense, int count) {}

    /**
     * Split every community of the local-moving partition into well-connected sub-communities.
     *
     * <p>Within a parent community S (K_S = Σ degrees, m = total weight), a node v may only
     * start a sub-community if {@code E(v, S−v) ≥ γ·k_v·(K_S − k_v)/(2m)} (line 34), and may
     * only join an existing sub-community C (all of whose members are in S) if
     * {@code E(C, S−C) ≥ γ·K_C·(K_S − K_C)/(2m)} (line 37). Among the eligible targets the
     * merge goes to the max-ΔQ one with ΔQ = {@code k_{v,C} − γ·k_v·K_C/(2m)} strictly
     * positive — the θ→0 limit of the paper's random selection (line 38), deterministic.
     *
     * @return dense sub-community id per node plus the number of sub-communities
     */
    private Refinement refinePartition(LevelGraph lg, int[] community) {
        int n = lg.n;
        double m = lg.m;

        // Rep semantics within a parent cluster: a sub-community's rep is the smallest
        // node index among its members; the rep's arrays hold the community state.
        int[] refinedRep = new int[n];
        int[] size = new int[n];
        double[] K = new double[n];    // Σ weighted degrees of the sub-community
        double[] Eout = new double[n]; // E(sub-community, S − sub-community) within parent S
        double[] extV = new double[n]; // E(v, S − v) within parent S
        for (int v = 0; v < n; v++) {
            refinedRep[v] = v;
            size[v] = 1;
            K[v] = lg.degree[v];
        }

        // Bucket nodes by cluster id (raw ids in [0, n)).
        int[] head = new int[n];
        Arrays.fill(head, -1);
        int[] next = new int[n];
        for (int v = 0; v < n; v++) {
            int c = community[v];
            next[v] = head[c];
            head[c] = v;
        }

        int denseCount = 0;
        int[] denseOfRep = new int[n];
        for (int c = 0; c < n; c++) {
            if (head[c] == -1) {
                continue;
            }
            // Collect members in ascending node order (determinism).
            int s = 0;
            for (int v = head[c]; v != -1; v = next[v]) {
                s++;
            }
            int[] members = new int[s];
            int idx = 0;
            for (int v = head[c]; v != -1; v = next[v]) {
                members[idx++] = v;
            }
            Arrays.sort(members);

            mergeNodesSubset(lg, members, c, community, refinedRep, size, K, Eout, extV);

            // Assign global dense ids to this parent's sub-communities (ascending rep order).
            for (int v : members) {
                if (refinedRep[v] == v) {
                    denseOfRep[v] = denseCount++;
                }
            }
        }

        int[] dense = new int[n];
        for (int v = 0; v < n; v++) {
            dense[v] = denseOfRep[refinedRep[v]];
        }
        return new Refinement(dense, denseCount);
    }

    /**
     * MergeNodesSubset for one parent cluster {@code members} (sorted ascending).
     *
     * @param clusterId parent cluster id — membership test {@code community[u] == clusterId}
     */
    private void mergeNodesSubset(LevelGraph lg, int[] members, int clusterId, int[] community,
                                  int[] refinedRep, int[] size, double[] K, double[] Eout, double[] extV) {
        double m = lg.m;
        double KS = 0.0;
        for (int v : members) {
            KS += lg.degree[v];
        }

        // Precompute E(v, S−v) and initialize singleton sub-community admin.
        for (int v : members) {
            double ext = 0.0;
            int[] nbr = lg.nbr[v];
            double[] wt = lg.wt[v];
            for (int e = 0; e < nbr.length; e++) {
                if (community[nbr[e]] == clusterId) {
                    ext += wt[e];
                }
            }
            extV[v] = ext;
            Eout[v] = ext;
        }

        for (int v : members) { // ascending node order (determinism)
            int repV = refinedRep[v];
            if (size[repV] > 1) {
                continue; // line 36: only singleton communities may merge
            }
            double k_v = lg.degree[v];

            // Line 34: v must be well connected within S.
            if (extV[v] < resolution * k_v * (KS - k_v) / (2.0 * m)) {
                continue;
            }

            // Accumulate k_{v,C} over neighbor sub-communities within S.
            int[] nbr = lg.nbr[v];
            double[] wt = lg.wt[v];
            int[] commBuf = new int[nbr.length + 1];
            double[] wBuf = new double[nbr.length + 1];
            int distinct = 0;
            for (int e = 0; e < nbr.length; e++) {
                int u = nbr[e];
                if (community[u] != clusterId) {
                    continue;
                }
                int repU = refinedRep[u];
                boolean found = false;
                for (int t = 0; t < distinct; t++) {
                    if (commBuf[t] == repU) {
                        wBuf[t] += wt[e];
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    commBuf[distinct] = repU;
                    wBuf[distinct] = wt[e];
                    distinct++;
                }
            }

            int bestRep = -1;
            double bestKVc = 0.0;
            double bestDiff = 0.0;
            for (int t = 0; t < distinct; t++) {
                int repC = commBuf[t];
                double kVC = wBuf[t];
                // Line 37: the candidate sub-community must be well connected within S.
                if (Eout[repC] < resolution * K[repC] * (KS - K[repC]) / (2.0 * m)) {
                    continue;
                }
                double diff = kVC - resolution * k_v * K[repC] / (2.0 * m);
                if (diff > bestDiff + EPSILON) {
                    bestDiff = diff;
                    bestRep = repC;
                    bestKVc = kVC;
                }
            }

            if (bestRep != -1) {
                // Merge v into the deterministic best target. Eout update: the new members
                // internalize edges from v to C (subtract k_{v,C} twice: once from C's side,
                // once from v's side) and gain v's edges to the rest of S.
                refinedRep[v] = bestRep;
                size[bestRep]++;
                K[bestRep] += k_v;
                Eout[bestRep] += extV[v] - 2.0 * bestKVc;
            }
            // Else v stays a singleton sub-community (admin already initialized).
        }
    }

    // ---- Phase 3: aggregation + next-level initial partition ----

    /**
     * Initial partition of the aggregate graph (Algorithm A.2 line 8): super-nodes are
     * grouped by the <em>unrefined</em> cluster of their members; each group's id is the
     * smallest super-node id in it (deterministic).
     */
    private static int[] nextInitialPartition(int[] community, int k, int[] refined, int refinedCount) {
        // Raw cluster ids live in [0, community.length): size arrays by that range.
        int[] commDense = new int[community.length];
        Arrays.fill(commDense, -1);
        int np = 0;
        for (int v = 0; v < community.length; v++) {
            int c = community[v];
            if (commDense[c] == -1) {
                commDense[c] = np++;
            }
        }
        int[] parentOfRefined = new int[refinedCount];
        Arrays.fill(parentOfRefined, -1);
        for (int v = 0; v < community.length; v++) {
            int dr = refined[v];
            if (parentOfRefined[dr] == -1) {
                parentOfRefined[dr] = commDense[community[v]];
            }
        }
        int[] groupRep = new int[np];
        Arrays.fill(groupRep, -1);
        int[] initComm = new int[refinedCount];
        for (int j = 0; j < refinedCount; j++) {
            int g = parentOfRefined[j];
            if (groupRep[g] == -1) {
                groupRep[g] = j;
            }
            initComm[j] = groupRep[g];
        }
        return initComm;
    }

    /** Dense renumbering of a raw cluster assignment (first-seen ascending order). */
    private static int[] densify(int[] community) {
        // Raw cluster ids live in [0, community.length): size remap by that range.
        int[] remap = new int[community.length];
        Arrays.fill(remap, -1);
        int[] dense = new int[community.length];
        int count = 0;
        for (int v = 0; v < community.length; v++) {
            int c = community[v];
            if (remap[c] == -1) {
                remap[c] = count++;
            }
            dense[v] = remap[c];
        }
        return dense;
    }

    private static Long2IntMap toMap(long[] origNodes, int[] community) {
        var result = new Long2IntOpenHashMap(origNodes.length);
        for (int i = 0; i < origNodes.length; i++) {
            result.put(origNodes[i], community[i]);
        }
        return result;
    }

    private record MoveResult(int[] community, int k) {}

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
         * Collapse communities into super-nodes (aggregation based on the <em>refined</em>
         * partition for Leiden). Intra-community edges (and carried self-loops) become the
         * super-node self-loop; inter-community edges sum between super-nodes.
         * Total weight m is conserved.
         *
         * @param assignment dense community assignment per node
         * @param k          number of super-nodes (= max dense id + 1)
         */
        LevelGraph aggregate(int[] assignment, int k) {
            // Accumulate inter-community edges into per-super-node weight maps keyed by neighbor
            // super-node index. Long2DoubleOpenHashMap keeps the hot path allocation-light.
            var acc = new Long2DoubleOpenHashMap[k];
            for (int s = 0; s < k; s++) {
                acc[s] = new Long2DoubleOpenHashMap();
            }
            double[] newSelfLoop = new double[k];

            for (int i = 0; i < n; i++) {
                int ci = assignment[i];
                int[] ni = nbr[i];
                double[] wi = wt[i];
                for (int e = 0; e < ni.length; e++) {
                    int j = ni[e];
                    if (j <= i) {
                        continue; // each undirected edge once (i < j); adjacency is symmetric
                    }
                    int cj = assignment[j];
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
