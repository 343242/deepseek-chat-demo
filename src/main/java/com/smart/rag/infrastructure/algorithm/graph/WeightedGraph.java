package com.smart.rag.infrastructure.algorithm.graph;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;

import java.util.Set;

/**
 * Undirected weighted simple graph abstraction.
 *
 * <p>Provides the minimal contract consumed by Leiden community detection, weak-tie scoring,
 * and bridge-score computation. Zero business dependency — depends only on JDK and fastutil.</p>
 *
 * <p>Edge semantics:</p>
 * <ul>
 *   <li>Undirected: {@code addEdge(a, b, w)} writes both directions.</li>
 *   <li>Accumulating: duplicate {@code addEdge} calls accumulate weight.</li>
 * </ul>
 *
 * @see AdjacencyListGraph
 */
public interface WeightedGraph {

    /**
     * Register an isolated node (no edges).
     *
     * @param node node identifier
     */
    void addNode(long node);

    /**
     * Add an undirected weighted edge. Both endpoints are registered automatically.
     * Duplicate calls accumulate weight.
     *
     * @param a      first endpoint
     * @param b      second endpoint
     * @param weight edge weight (must be positive)
     */
    void addEdge(long a, long b, double weight);

    /**
     * Return all registered nodes (including isolated nodes with no edges).
     *
     * @return immutable set of node identifiers
     */
    Set<Long> nodes();

    /**
     * Return a read-only view of the neighbor → weight mapping for the given node.
     *
     * @param node node identifier
     * @return neighbor→weight map (empty if isolated or unknown)
     */
    Long2DoubleMap neighbors(long node);

    /**
     * Return the weight of the edge between two nodes, or {@code 0.0} if no edge exists.
     *
     * @param a first endpoint
     * @param b second endpoint
     * @return edge weight
     */
    double edgeWeight(long a, long b);

    /**
     * Return the weighted degree (sum of incident edge weights) for the given node.
     * For undirected graphs, this is {@code 2m_i} where {@code m_i} counts each edge once.
     *
     * @param node node identifier
     * @return weighted degree (0.0 if isolated or unknown)
     */
    double weightedDegree(long node);

    /**
     * Return the total graph weight {@code m = Σ w / 2} (each undirected edge counted once).
     * This is the Leiden normalization factor.
     *
     * @return total weight
     */
    double totalWeight();

    /**
     * Return the number of nodes in the graph (including isolated nodes).
     *
     * @return node count
     */
    int nodeCount();
}
