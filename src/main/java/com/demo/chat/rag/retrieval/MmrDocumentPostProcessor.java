package com.demo.chat.rag.retrieval;

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
 *   <li>sim(q,d) = 查询-文档相关性（使用 rerankScore 或 metadata 分数）</li>
 *   <li>sim(d,d') = 文档间相似度（基于词频 Jaccard）</li>
 * </ul>
 */
public class MmrDocumentPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(MmrDocumentPostProcessor.class);

    private final double lambda;
    private final int topK;

    public MmrDocumentPostProcessor(double lambda, int topK) {
        this.lambda = lambda;
        this.topK = topK;
        log.info("MmrDocumentPostProcessor initialized: lambda={}, topK={}", lambda, topK);
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }

        if (documents.size() <= topK) {
            return documents;
        }

        String queryText = query.text().toLowerCase();
        Set<String> queryTokens = tokenize(queryText);

        // 预计算：每个文档的 token 集合和相关性分数
        int n = documents.size();
        List<Set<String>> docTokens = new ArrayList<>(n);
        double[] relevanceScores = new double[n];

        for (int i = 0; i < n; i++) {
            Document doc = documents.get(i);
            docTokens.add(tokenize(doc.getText().toLowerCase()));

            // 优先使用 rerankScore，其次 rrfScore，最后默认 0.5
            Object rerankScore = doc.getMetadata().get("rerankScore");
            Object rrfScore = doc.getMetadata().get("rrfScore");
            if (rerankScore instanceof Number) {
                relevanceScores[i] = ((Number) rerankScore).doubleValue();
            } else if (rrfScore instanceof Number) {
                relevanceScores[i] = ((Number) rrfScore).doubleValue();
            } else {
                // 使用与查询的 Jaccard 相似度作为相关性
                relevanceScores[i] = jaccard(queryTokens, docTokens.get(i));
            }
        }

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

                // 冗余项：与已选文档的最大相似度
                double maxSim = 0.0;
                for (int selIdx : selected) {
                    double sim = jaccard(docTokens.get(i), docTokens.get(selIdx));
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

        log.debug("MMR: {} docs → {} docs (lambda={})", documents.size(), result.size(), lambda);
        return result;
    }

    // === 工具方法 ===

    /**
     * 简单分词：基于空格和标点的字符级分词
     * <p>
     * 对中文做逐字拆分（bigram），对英文按空格分词。
     * </p>
     */
    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();

        Set<String> tokens = new HashSet<>();
        StringBuilder current = new StringBuilder();

        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                current.append(c);
            } else {
                if (current.length() > 0) {
                    String word = current.toString();
                    tokens.add(word);
                    // 中文 bigram
                    if (isChinese(word)) {
                        for (int i = 0; i < word.length() - 1; i++) {
                            tokens.add(word.substring(i, i + 2));
                        }
                    }
                    current.setLength(0);
                }
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private boolean isChinese(String s) {
        return s.chars().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    }

    /**
     * Jaccard 相似度：|A∩B| / |A∪B|
     */
    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }
}
