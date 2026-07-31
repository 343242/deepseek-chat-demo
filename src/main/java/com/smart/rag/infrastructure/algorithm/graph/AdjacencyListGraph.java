package com.smart.rag.infrastructure.algorithm.graph;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleMaps;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Collections;
import java.util.Set;

/**
 * Undirected weighted graph backed by a packed {@code long[]} adjacency list.
 *
 * <p>Each node maps to a {@code long[]} where neighbor IDs and bit-converted weights
 * are interleaved: {@code [neighbor0, weight0_bits, neighbor1, weight1_bits, ...]}.
 * This eliminates per-edge object allocation, reducing GC pressure compared to
 * object-based edge representations.</p>
 *
 * <p>Thread-unsafe — intended for single-threaded batch construction followed by read-only
 * consumption by Leiden community detection.</p>
 */
public class AdjacencyListGraph implements WeightedGraph {

    /** node → packed adjacency array [neighbor0, weight0_bits, neighbor1, weight1_bits, ...] */
    private final Long2ObjectMap<long[]> adjacency = new Long2ObjectOpenHashMap<>();

    /** running total of sum-of-all-edge-weights (counting both directions) */
    private double weightSum = 0.0;

    @Override
    public void addNode(long node) {
        adjacency.putIfAbsent(node, new long[0]);
    }

    @Override
    public void addEdge(long a, long b, double weight) {
        if (a == b) {
            return; // no self-loops in simple graph
        }
        adjacency.putIfAbsent(a, new long[0]);
        adjacency.putIfAbsent(b, new long[0]);

        putPackedWeight(a, b, weight);
        putPackedWeight(b, a, weight);

        weightSum += 2.0 * weight; // both directions
    }

    @Override
    public Set<Long> nodes() {
        return Collections.unmodifiableSet(new LongOpenHashSet(adjacency.keySet()));
    }

    @Override
    public Long2DoubleMap neighbors(long node) {
        var packed = adjacency.get(node);
        if (packed == null || packed.length == 0) {
            return Long2DoubleMaps.EMPTY_MAP;
        }
        var map = new Long2DoubleOpenHashMap(packed.length / 2);
        for (int i = 0; i < packed.length; i += 2) {
            map.put(packed[i], Double.longBitsToDouble(packed[i + 1]));
        }
        return Long2DoubleMaps.unmodifiable(map);
    }

    @Override
    public double edgeWeight(long a, long b) {
        var packed = adjacency.get(a);
        if (packed == null) {
            return 0.0;
        }
        for (int i = 0; i < packed.length; i += 2) {
            if (packed[i] == b) {
                return Double.longBitsToDouble(packed[i + 1]);
            }
        }
        return 0.0;
    }

    @Override
    public double weightedDegree(long node) {
        var packed = adjacency.get(node);
        if (packed == null || packed.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 1; i < packed.length; i += 2) {
            sum += Double.longBitsToDouble(packed[i]);
        }
        return sum;
    }

    @Override
    public double totalWeight() {
        return weightSum / 2.0; // each undirected edge counted once
    }

    @Override
    public int nodeCount() {
        return adjacency.size();
    }

    /**
     * Add or accumulate weight in the packed array for a→b.
     */
    private void putPackedWeight(long from, long to, double weight) {
        var packed = adjacency.get(from);
        for (int i = 0; i < packed.length; i += 2) {
            if (packed[i] == to) {
                packed[i + 1] = Double.doubleToRawLongBits(
                        Double.longBitsToDouble(packed[i + 1]) + weight);
                return;
            }
        }
        // not found — grow array
        var newPacked = new long[packed.length + 2];
        System.arraycopy(packed, 0, newPacked, 0, packed.length);
        newPacked[packed.length] = to;
        newPacked[packed.length + 1] = Double.doubleToRawLongBits(weight);
        adjacency.put(from, newPacked);
    }
}
