package com.smart.rag.rag.retrieval;

import com.smart.rag.rag.config.RagRetrievalProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Vector (pgvector HNSW) retrieval path.
 * <p>
 * Uses semantic similarity search with userId/teamId isolation filter.
 */
@Component
public class VectorRetrievalPath implements RetrievalPath {

    private static final Logger log = LoggerFactory.getLogger(VectorRetrievalPath.class);

    /**
     * WHY 0.5：pgvector 相似度缺失（null score）时的中性兜底——RRF SCORE_WEIGHTED 融合中
     * 不抬高也不压低该 chunk 的贡献，避免缺分文档被系统性排除或过度加权。
     */
    private static final double DEFAULT_FALLBACK_SCORE = 0.5;

    private final VectorStore vectorStore;
    private final RagRetrievalProperties properties;

    public VectorRetrievalPath(VectorStore vectorStore, RagRetrievalProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "vector-search";
    }

    @Override
    public List<ScoredDocument> search(String query, long userId, @Nullable Long teamId) {
        int topK = properties.vectorTopK();
        FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
        var filter = teamId != null
                ? filterBuilder.eq("teamId", String.valueOf(teamId)).build()
                : filterBuilder.eq("userId", String.valueOf(userId)).build();

        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(properties.similarityThreshold())
                        .filterExpression(filter)
                        .build()
        );

        List<ScoredDocument> results = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            double vectorScore = doc.getScore() != null ? doc.getScore() : DEFAULT_FALLBACK_SCORE;
            results.add(new ScoredDocument(doc, i + 1, vectorScore, name()));
        }
        return results;
    }

    @Override
    public RrfWeighting rrfWeighting() {
        return RrfWeighting.SCORE_WEIGHTED;
    }
}
