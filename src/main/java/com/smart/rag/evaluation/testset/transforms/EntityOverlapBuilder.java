package com.smart.rag.evaluation.testset.transforms;

import com.smart.rag.evaluation.testset.graph.Node;
import com.smart.rag.evaluation.testset.graph.Relationship;
import com.smart.rag.evaluation.testset.graph.RelationshipType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体重叠关系构建器（翻译 ragas {@code OverlapScoreBuilder}，Jaro-Winkler 距离版）。
 * <p>
 * 算法语义与 ragas 一致：
 * <ol>
 *   <li>噪声剔除：全部节点实体中频次最高的前 5%（至少 1 个）不参与匹配；</li>
 *   <li>节点对 (i&lt;j) 逐实体对比较：{@code jaroWinkler(x.lower, y.lower) ≥ 0.9} 记一次重叠；</li>
 *   <li>重叠占比（重叠数 / 总比较数）≥ 0.01 时建立双向关系，properties 携带重叠实体对。</li>
 * </ol>
 * Jaro-Winkler 为手写实现（标准公式），与 rapidfuzz 的相似度语义等价（rapidfuzz distance = 1 - similarity）。
 * </p>
 */
public final class EntityOverlapBuilder {

    /** ragas 默认：实体名匹配阈值（Jaro-Winkler 相似度）。 */
    static final double DISTANCE_THRESHOLD = 0.9;

    /** ragas 默认：节点对重叠占比低于该值不建边。 */
    static final double OVERLAP_THRESHOLD = 0.01;

    /** ragas 默认：噪声实体频次截断比例。 */
    static final double NOISY_CUT_OFF = 0.05;

    public List<Relationship> build(List<Node> nodes) {
        var noisy = noisyEntities(nodes);
        var ordered = nodes.stream()
                .sorted(Comparator.comparing(Node::id))
                .toList();
        var relationships = new ArrayList<Relationship>();
        for (int i = 0; i < ordered.size(); i++) {
            for (int j = i + 1; j < ordered.size(); j++) {
                var rel = overlap(ordered.get(i), ordered.get(j), noisy);
                if (rel != null) {
                    relationships.add(rel);
                }
            }
        }
        return relationships;
    }

    private Relationship overlap(Node x, Node y, Map<String, Integer> noisy) {
        // 预过滤噪声实体，后续剪枝才能以剩余总比较数为上界
        var xs = new ArrayList<String>();
        for (String ex : x.entities()) {
            if (!noisy.containsKey(ex)) {
                xs.add(ex);
            }
        }
        var ys = new ArrayList<String>();
        for (String ey : y.entities()) {
            if (!noisy.containsKey(ey)) {
                ys.add(ey);
            }
        }
        int total = xs.size() * ys.size();
        if (total == 0) {
            return null;
        }
        int matches = 0;
        var matchedPairs = new ArrayList<Map.Entry<String, String>>();
        int comparisons = 0;
        for (String ex : xs) {
            for (String ey : ys) {
                // 剪枝：即使剩余比较全部命中也达不到 OVERLAP_THRESHOLD，提前放弃该节点对
                // （O(N²) 节点对 × O(实体²) 的主要放大场景是"完全不相关"的对，此处命中即跳过）
                int remaining = total - comparisons;
                if ((double) (matches + remaining) / total < OVERLAP_THRESHOLD) {
                    return null;
                }
                comparisons++;
                if (jaroWinkler(ex.toLowerCase(), ey.toLowerCase()) >= DISTANCE_THRESHOLD) {
                    matches++;
                    matchedPairs.add(Map.entry(ex, ey));
                }
            }
        }
        double score = comparisons > 0 ? (double) matches / comparisons : 0.0;
        if (score < OVERLAP_THRESHOLD) {
            return null;
        }
        var properties = new LinkedHashMap<String, Object>();
        properties.put("overlapScore", score);
        properties.put("overlappedItems", matchedPairs.stream()
                .map(p -> p.getKey() + "~" + p.getValue())
                .toList());
        return new Relationship(x.id(), y.id(), RelationshipType.ENTITY_OVERLAP,
                score, true, properties);
    }

    /**
     * 噪声实体：按出现频次降序取前 5%（至少 1 个），对应 ragas _get_noisy_items。
     * 同频次按名称字典序决胜，保证结果确定（rapidfuzz 版依赖 Counter 插入序）。
     */
    static Map<String, Integer> noisyEntities(List<Node> nodes) {
        Map<String, Integer> counts = new HashMap<>();
        nodes.forEach(n -> n.entities().forEach(e -> counts.merge(e, 1, Integer::sum)));
        int numNoisy = Math.max(1, (int) (counts.size() * NOISY_CUT_OFF));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(numNoisy)
                .collect(HashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), HashMap::putAll);
    }

    /**
     * 标准 Jaro-Winkler 相似度（前缀权重 0.1，缩放上限 4），手写实现。
     */
    static double jaroWinkler(String a, String b) {
        double jaro = jaro(a, b);
        if (jaro < 0.7) {
            return jaro;
        }
        int prefix = 0;
        int max = Math.min(4, Math.min(a.length(), b.length()));
        while (prefix < max && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    static double jaro(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int window = Math.max(a.length(), b.length()) / 2 - 1;
        window = Math.max(window, 0);
        boolean[] aMatched = new boolean[a.length()];
        boolean[] bMatched = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(i + window + 1, b.length());
            for (int k = from; k < to; k++) {
                if (!bMatched[k] && a.charAt(i) == b.charAt(k)) {
                    aMatched[i] = true;
                    bMatched[k] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (!aMatched[i]) {
                continue;
            }
            while (!bMatched[k]) {
                k++;
            }
            if (a.charAt(i) != b.charAt(k)) {
                transpositions++;
            }
            k++;
        }
        transpositions /= 2;
        double m = matches;
        return (m / a.length() + m / b.length() + (m - transpositions) / m) / 3.0;
    }
}
