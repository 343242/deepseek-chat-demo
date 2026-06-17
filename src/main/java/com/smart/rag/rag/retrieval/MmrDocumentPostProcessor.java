package com.smart.rag.rag.retrieval;

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
 */
public class MmrDocumentPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(MmrDocumentPostProcessor.class);

    private final double lambda;
    private final int topK;
    private final VectorStoreMapper vectorStoreMapper;

    public MmrDocumentPostProcessor(double lambda, int topK, VectorStoreMapper vectorStoreMapper) {
        this.lambda = lambda;
        this.topK = topK;
        this.vectorStoreMapper = vectorStoreMapper;
        log.info("MmrDocumentPostProcessor initialized: lambda={}, topK={}, similarity=pgvector_cosine",
                lambda, topK);
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }

        if (documents.size() <= topK) {
            return documents;
        }

        int n = documents.size();

        // 提取相关性分数（优先 rerankScore > rrfScore > 默认 0.5）
        double[] relevanceScores = new double[n];
        for (int i = 0; i < n; i++) {
            relevanceScores[i] = resolveRelevanceScore(documents.get(i));
        }

        // 数据库层计算文档间 cosine distance 矩阵
        // TODO: 热门文档可考虑短时缓存距离矩阵，避免重复 DB 查询（当前文档量小，暂不缓存）
        List<String> docIds = documents.stream().map(Document::getId).toList();
        Map<String, Double> distanceMatrix = vectorStoreMapper.pairwiseCosineDistance(docIds);

        // 贪心 MMR 选择
        List<Integer> selected = new ArrayList<>(topK);
        boolean[] used = new boolean[n];

        // 第一步：选相关性最高的
        int bestIdx = 0;
        for (int i = 1; i < n; i++) {
            if (relevanceScores[i] > relevanceScores[bestIdx]) {
                bestIdx = i;
            }
        }
        selected.add(bestIdx);
        used[bestIdx] = true;

        // 后续步：MMR 公式迭代选择
        while (selected.size() < topK && selected.size() < n) {
            int nextIdx = -1;
            double bestMmr = Double.NEGATIVE_INFINITY;

            for (int i = 0; i < n; i++) {
                if (used[i]) continue;

                // 相关性项
                double relevance = relevanceScores[i];

                // 冗余项：与已选文档的最大相似度（cosine similarity = 1 - cosine distance）
                double maxSim = 0.0;
                for (int selIdx : selected) {
                    String key = docIds.get(i) + "|" + docIds.get(selIdx);
                    Double dist = distanceMatrix.get(key);
                    double sim = dist != null ? 1.0 - dist : 0.0;
                    maxSim = Math.max(maxSim, sim);
                }

                double mmr = lambda * relevance - (1 - lambda) * maxSim;
                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    nextIdx = i;
                }
            }

            if (nextIdx == -1) break;
            selected.add(nextIdx);
            used[nextIdx] = true;
        }

        // 构建结果
        List<Document> result = new ArrayList<>(selected.size());
        for (int idx : selected) {
            documents.get(idx).getMetadata().put("mmrSelected", true);
            result.add(documents.get(idx));
        }

        log.debug("MMR: {} docs → {} docs (lambda={}, db_cosine)", documents.size(), result.size(), lambda);
        return result;
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
        log.debug("No rerankScore/rrfScore in metadata for doc {}, falling back to 0.5 " +
                "(expected when MMR runs before Rerank)", doc.getId());
        return 0.5;
    }
}
