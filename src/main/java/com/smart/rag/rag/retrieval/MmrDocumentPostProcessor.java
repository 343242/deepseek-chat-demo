package com.smart.rag.rag.retrieval;

import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.*;

/**
 * MMR (Maximal Marginal Relevance) 多样性重排处理器
 * <p>
 * 在 Rerank 精排之后执行，消除语义冗余的检索结果。
 * 文档间相似度通过 pgvector 在数据库层计算 cosine distance，零额外 API 调用。
 * </p>
 *
 * <p>MMR 算法：</p>
 * <pre>
 * MMR(d) = argmax_{d∈R\S} [ λ·sim(q,d) - (1-λ)·max_{d'∈S} sim(d,d') ]
 * </pre>
 *
 * <p>其中：</p>
 * <ul>
 *   <li>R = 候选文档集（Rerank 后的结果）</li>
 *   <li>S = 已选文档集（逐步贪心构建）</li>
 *   <li>λ = 平衡参数（0=最大多样性，1=最大相关性）</li>
 *   <li>sim(q,d) = 查询-文档相关性（使用 rerankScore）</li>
 *   <li>sim(d,d') = 文档间相似度（pgvector cosine distance → 1 - distance）</li>
 * </ul>
 *
 * <p>核心逻辑拆为包级 {@link #fetchDistanceMatrix(List)} + {@link #selectByMmr(Query, List, Map)}，
 * 供 {@link RerankThenMmrPostProcessor} 并行编排（B3 无状态：纯函数 + 只读字段，跨请求共享安全）。</p>
 */
public class MmrDocumentPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(MmrDocumentPostProcessor.class);

    private final double lambda;
    private final int topK;
    /**
     * 召回上限，联动 pairwiseCosineDistance 截断阈值 max(MAX_PAIRWISE_DOCS, fusionTopK)：
     * 截断下限在 mapper 侧取 {@code max(MAX_PAIRWISE_DOCS, fusionTopK)}，
     * 保证召回 fusionTopK 条时距离矩阵覆盖全量候选，避免 distance key miss。
     */
    private final int fusionTopK;
    private final VectorStoreMapper vectorStoreMapper;

    public MmrDocumentPostProcessor(double lambda, int topK, int fusionTopK, VectorStoreMapper vectorStoreMapper) {
        if (lambda < 0.0 || lambda > 1.0) {
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR,
                    "MMR lambda must be in [0, 1], got: " + lambda);
        }
        if (topK <= 0) {
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR,
                    "MMR topK must be > 0, got: " + topK);
        }
        this.lambda = lambda;
        this.topK = topK;
        this.fusionTopK = fusionTopK;
        this.vectorStoreMapper = vectorStoreMapper;
        log.info("MmrDocumentPostProcessor initialized: lambda={}, topK={}, fusionTopK={}, similarity=pgvector_cosine",
                lambda, topK, fusionTopK);
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }
        if (documents.size() <= topK) {
            return documents;
        }
        Map<String, Double> distance = fetchDistanceMatrix(documents);
        return selectByMmr(query, documents, distance);
    }

    /**
     * 预取文档间 cosine 距离矩阵（DB pgvector），失败→null（调用方走 relevance-only 降级）。
     * <p>
     * 截断阈值联动 fusionTopK（mapper 侧实际下限 {@code max(MAX_PAIRWISE_DOCS, fusionTopK)}），召回 60 时覆盖全 60 条，
     * 避免 Rerank top20 落在 RRF 51-60 位时 distance key miss（被误判无冗余）。
     * <p>
     * B3 无状态：只读 vectorStoreMapper/fusionTopK 字段，无中间实例状态。
     *
     * @param documents 待计算距离的文档（复合处理器传召回全量，独立调用传 MMR 候选）
     * @return 对称距离矩阵 key="idA|idB"，或 null（DB 失败降级）
     */
    Map<String, Double> fetchDistanceMatrix(List<Document> documents) {
        List<String> docIds = documents.stream().map(Document::getId).toList();
        try {
            return vectorStoreMapper.pairwiseCosineDistance(docIds, fusionTopK);
        } catch (Exception e) {
            log.warn("MMR pairwise distance fetch failed, degrading to relevance-only: {}", e.getMessage());
            return null;
        }
    }

    /**
     * MMR 贪心选择。
     * <ul>
     *   <li>distance=null → relevance-only 降级（rerankScore/rrfScore 排序取 topK）</li>
     *   <li>doc数 <= topK → 原样返回（保持原序，节省计算）</li>
     *   <li>否则 → λ·relevance - (1-λ)·maxSim 贪心迭代</li>
     * </ul>
     * B3 无状态：只读 lambda/topK 字段。
     *
     * @param distance {@link #fetchDistanceMatrix} 预取的距离矩阵，null 走 relevance-only
     */
    List<Document> selectByMmr(Query query, List<Document> documents, Map<String, Double> distance) {
        int n = documents.size();
        if (n == 0) {
            return documents;
        }
        if (n <= topK) {
            return documents;
        }

        if (distance == null) {
            log.info("MMR relevance-only (distance unavailable): {} docs → {}", n, topK);
            return documents.stream()
                    .sorted(Comparator.comparingDouble(this::resolveRelevanceScore).reversed())
                    .limit(topK)
                    .toList();
        }

        double[] relevanceScores = new double[n];
        for (int i = 0; i < n; i++) {
            relevanceScores[i] = resolveRelevanceScore(documents.get(i));
        }

        double[][] similarity = buildSimilarityMatrix(documents, distance);

        // 贪心 MMR 选择
        List<Integer> selected = new ArrayList<>(topK);
        boolean[] used = new boolean[n];
        // maxSimToSelected[i] = 候选 i 与已选集 S 的最大相似度（增量维护，避免每轮重扫 selected）
        double[] maxSimToSelected = new double[n];

        // 第一步：选相关性最高的
        int bestIdx = 0;
        for (int i = 1; i < n; i++) {
            if (relevanceScores[i] > relevanceScores[bestIdx]) {
                bestIdx = i;
            }
        }
        selected.add(bestIdx);
        used[bestIdx] = true;
        updateMaxSimilarity(maxSimToSelected, similarity, used, bestIdx);

        // 后续步：MMR 公式迭代选择
        while (selected.size() < topK && selected.size() < n) {
            int nextIdx = selectNextCandidate(relevanceScores, maxSimToSelected, used);
            if (nextIdx == -1) break;
            selected.add(nextIdx);
            used[nextIdx] = true;
            updateMaxSimilarity(maxSimToSelected, similarity, used, nextIdx);
        }

        List<Document> result = new ArrayList<>(selected.size());
        for (int idx : selected) {
            result.add(documents.get(idx));
        }

        log.info("MMR: {} docs → {} docs (lambda={}, db_cosine)", documents.size(), result.size(), lambda);
        return result;
    }

    /**
     * 贪心选择下一个 MMR 候选：argmax λ·relevance - (1-λ)·maxSim。
     *
     * @return 最优候选下标；无可选候选时 -1
     */
    private int selectNextCandidate(double[] relevanceScores, double[] maxSimToSelected, boolean[] used) {
        int nextIdx = -1;
        double bestMmr = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < relevanceScores.length; i++) {
            if (used[i]) continue;
            double mmr = lambda * relevanceScores[i] - (1 - lambda) * maxSimToSelected[i];
            if (mmr > bestMmr) {
                bestMmr = mmr;
                nextIdx = i;
            }
        }
        return nextIdx;
    }

    /** 新选中 newlySelected 后，增量更新各未选候选与已选集的最大相似度。 */
    private void updateMaxSimilarity(double[] maxSimToSelected, double[][] similarity,
                                     boolean[] used, int newlySelected) {
        for (int i = 0; i < maxSimToSelected.length; i++) {
            if (used[i]) continue;
            maxSimToSelected[i] = Math.max(maxSimToSelected[i], similarity[i][newlySelected]);
        }
    }

    /**
     * 将 "idA|idB" 字符串 key 的距离矩阵预处理为下标对称相似度矩阵，
     * 避免贪心循环内反复字符串拼接 + Map 查找；缺失项相似度按 0（无冗余）处理。
     * <p>docId 为 UUID（不含分隔符），indexOf('|') 拆分安全。
     */
    private double[][] buildSimilarityMatrix(List<Document> documents, Map<String, Double> distance) {
        int n = documents.size();
        Map<String, Integer> idxById = new HashMap<>(n);
        for (int i = 0; i < n; i++) {
            idxById.put(documents.get(i).getId(), i);
        }
        double[][] similarity = new double[n][n];
        for (var entry : distance.entrySet()) {
            int sep = entry.getKey().indexOf('|');
            if (sep < 0) continue;
            Integer a = idxById.get(entry.getKey().substring(0, sep));
            Integer b = idxById.get(entry.getKey().substring(sep + 1));
            if (a == null || b == null) continue;
            double sim = 1.0 - entry.getValue();
            similarity[a][b] = sim;
            similarity[b][a] = sim;
        }
        return similarity;
    }

    /**
     * 从 metadata 中解析文档相关性分数。
     * 优先级：rerankScore > rrfScore > 默认 0.5
     */
    private double resolveRelevanceScore(Document doc) {
        Object rerankScore = doc.getMetadata().get("rerankScore");
        if (rerankScore instanceof Number) {
            return ((Number) rerankScore).doubleValue();
        }
        Object rrfScore = doc.getMetadata().get("rrfScore");
        if (rrfScore instanceof Number) {
            return ((Number) rrfScore).doubleValue();
        }
        log.info("No rerankScore/rrfScore in metadata for doc {}, falling back to 0.5 " +
                "(Rerank→MMR 顺序下罕见：仅 Rerank 关闭/透传且 rrfScore 缺失时)", doc.getId());
        return 0.5;
    }
}
