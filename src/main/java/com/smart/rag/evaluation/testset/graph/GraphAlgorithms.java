package com.smart.rag.evaluation.testset.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 知识图谱算法（翻译自 ragas {@code testset/graph.py}，v0.4.3 冻结版）。
 * <p>
 * 仅翻译合成器真实调用的两个算法；ragas 的 Leiden 变体（find_indirect_clusters）
 * 不在任何合成器调用路径上（0.4.3 的 MultiHopAbstract 走的是本类翻译的
 * find_n_indirect_clusters 纯 DFS 版），故不移植，避免死代码。
 * </p>
 */
public final class GraphAlgorithms {

    private GraphAlgorithms() {
    }

    /**
     * 三元组：(节点A, 关系, 节点B)。id 较小者在前（对应 ragas 的归一化去重）。
     */
    public record NodeTriplet(Node a, Relationship relationship, Node b) {
    }

    /**
     * 按关系条件找出 (A, rel, B) 三元组（翻译 {@code find_two_nodes_single_rel}）。
     * 自环剔除；同一对节点按 (小id, 大id) 归一化后去重。
     */
    public static List<NodeTriplet> findTwoNodesSingleRel(
            KnowledgeGraph kg, Predicate<Relationship> condition) {
        var seen = new LinkedHashSet<String>();
        var triplets = new ArrayList<NodeTriplet>();
        for (var rel : kg.relationships(condition)) {
            if (rel.source().equals(rel.target())) {
                continue;
            }
            String first;
            String second;
            Relationship normalized;
            if (rel.source().compareTo(rel.target()) < 0) {
                first = rel.source();
                second = rel.target();
                normalized = rel;
            } else {
                first = rel.target();
                second = rel.source();
                normalized = new Relationship(first, second, rel.type(), rel.weight(),
                        rel.bidirectional(), rel.properties());
            }
            if (seen.add(first + "|" + second + "|" + normalized.type())) {
                kg.node(first).ifPresent(a -> kg.node(second).ifPresent(b ->
                        triplets.add(new NodeTriplet(a, normalized, b))));
            }
        }
        return triplets;
    }

    /**
     * 间接簇查找（翻译 {@code find_n_indirect_clusters}，MultiHopAbstract 的真实数据源）。
     * <p>
     * 簇 = 图上一条路径的节点集合。算法：随机起点 DFS 收集路径（每起点限流）→
     * 各起点的簇集合 round-robin 轮转取出 → 去重（子集让位于超集）直至凑满 n 个。
     * 起点洗牌种子由节点 id 拼接的 SHA256 前 8 位十六进制派生，保证同图可复现。
     * </p>
     *
     * @param n          目标簇数（≥ 1）
     * @param depthLimit 路径最大深度（≥ 2）
     * @throws IllegalArgumentException 参数非法或无匹配关系
     */
    public static List<Set<Node>> findNIndirectClusters(
            KnowledgeGraph kg, Predicate<Relationship> condition, int n, int depthLimit) {
        if (depthLimit < 2) {
            throw new IllegalArgumentException("depth_limit must be at least 2");
        }
        if (n < 1) {
            throw new IllegalArgumentException("n must be at least 1");
        }
        var filtered = kg.relationships(condition);
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException(
                    "No relationships match the provided condition. Cannot form clusters.");
        }

        // 有向邻接 + 无向去重边统计（双向边可反向通行）
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        Set<Set<String>> uniqueEdges = new HashSet<>();
        for (var rel : filtered) {
            if (rel.source().equals(rel.target())) {
                continue; // 自环：Python frozenset([a,a]) 塌缩为单点不构成簇边，Set.of 则抛重复键
            }
            adjacency.computeIfAbsent(rel.source(), k -> new LinkedHashSet<>())
                    .add(rel.target());
            uniqueEdges.add(Set.of(rel.source(), rel.target()));
            if (rel.bidirectional()) {
                adjacency.computeIfAbsent(rel.target(), k -> new LinkedHashSet<>())
                        .add(rel.source());
            }
        }

        // 采样起点数（稀疏图取更多起点兜底独立小簇）
        Set<String> connectedNodes = new HashSet<>();
        uniqueEdges.forEach(connectedNodes::addAll);
        int sampleSize = uniqueEdges.size() < connectedNodes.size()
                ? (n - 1) * depthLimit + 1
                : Math.max(n, Math.max(depthLimit, 10));

        // 每起点 DFS 收集路径簇（对应 ragas dfs：叶节点/达深度/邻居全在路径上 即成簇）
        Map<String, Set<Set<String>>> startNodeClusters = new LinkedHashMap<>();
        var startNodes = new ArrayList<>(adjacency.keySet());
        Collections.sort(startNodes);
        Collections.shuffle(startNodes, new Random(seedFromNodeIds(startNodes)));
        var samples = startNodes.subList(0, Math.min(sampleSize, startNodes.size()));
        for (var start : samples) {
            dfs(start, start, new LinkedHashSet<>(), adjacency, startNodeClusters,
                    sampleSize, depthLimit);
        }

        // round-robin 轮转取簇，子集让位于超集，直至 n 个或取尽
        var groups = new ArrayList<>(startNodeClusters.values().stream()
                .map(HashSet::new).toList());
        Set<Set<String>> uniqueClusters = new LinkedHashSet<>();
        int i = 0;
        while (uniqueClusters.size() < n && !groups.isEmpty()) {
            int index = i % groups.size();
            var current = groups.get(index);
            if (current.isEmpty()) {
                groups.remove(index);
                continue;
            }
            var cluster = popArbitrary(current);
            boolean isSubset = false;
            var subsetsToRemove = new LinkedHashSet<Set<String>>();
            for (var existing : uniqueClusters) {
                if (existing.containsAll(cluster)) {
                    isSubset = true;
                    break;
                }
                if (cluster.containsAll(existing)) {
                    subsetsToRemove.add(existing);
                }
            }
            if (!isSubset) {
                uniqueClusters.removeAll(subsetsToRemove);
                uniqueClusters.add(cluster);
            }
            i++;
        }
        return uniqueClusters.stream()
                .map(kg::nodesByIds)
                .toList();
    }

    private static void dfs(String node, String startNode, Set<String> currentPath,
                            Map<String, Set<String>> adjacency,
                            Map<String, Set<Set<String>>> startNodeClusters,
                            int sampleSize, int depthLimit) {
        if (startNodeClusters.getOrDefault(startNode, Set.of()).size() > sampleSize) {
            return;
        }
        currentPath.add(node);
        var neighbors = adjacency.get(node);
        boolean atMaxDepth = currentPath.size() >= depthLimit;
        boolean noWayForward = neighbors == null || neighbors.isEmpty()
                || currentPath.containsAll(neighbors);
        if (currentPath.size() > 1 && (atMaxDepth || noWayForward)) {
            startNodeClusters.computeIfAbsent(startNode, k -> new HashSet<>())
                    .add(Set.copyOf(currentPath));
        } else if (neighbors != null) {
            for (var neighbor : neighbors) {
                if (!currentPath.contains(neighbor)) {
                    dfs(neighbor, startNode, currentPath, adjacency, startNodeClusters,
                            sampleSize, depthLimit);
                }
            }
        }
        currentPath.remove(node);
    }

    private static <T> T popArbitrary(Set<T> source) {
        var iterator = source.iterator();
        var item = iterator.next();
        iterator.remove();
        return item;
    }

    /** 起点洗牌种子：排序后节点 id 拼接的 SHA256 前 8 位十六进制（对应 ragas 的确定性洗牌）。 */
    static int seedFromNodeIds(List<String> sortedNodeIds) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("", sortedNodeIds).getBytes(StandardCharsets.UTF_8));
            return Integer.parseUnsignedInt(
                    HexFormat.of().formatHex(digest, 0, 4), 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
