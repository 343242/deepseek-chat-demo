package com.smart.rag.rag.retrieval;

import com.smart.rag.rag.config.RagRetrievalProperties;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * BM25 (PostgreSQL tsvector full-text) retrieval path.
 * <p>
 * Only registered when {@code app.rag.hybridRetrievalEnabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "app.rag", name = "hybridRetrievalEnabled", havingValue = "true")
public class Bm25RetrievalPath implements RetrievalPath {

    private static final Logger log = LoggerFactory.getLogger(Bm25RetrievalPath.class);

    private final VectorStoreMapper vectorStoreMapper;
    private final QueryNormalizer queryNormalizer;
    private final RagRetrievalProperties properties;

    public Bm25RetrievalPath(VectorStoreMapper vectorStoreMapper,
                             QueryNormalizer queryNormalizer,
                             RagRetrievalProperties properties) {
        this.vectorStoreMapper = vectorStoreMapper;
        this.queryNormalizer = queryNormalizer;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "bm25-search";
    }

    @Override
    public List<ScoredDocument> search(String query, long userId, @Nullable Long teamId) {
        int topK = properties.bm25TopK();
        String sanitized = queryNormalizer.sanitizeForTsQuery(query);
        if (sanitized.isBlank()) {
            return List.of();
        }

        String isolationField = teamId != null ? "teamId" : "userId";
        String isolationValue = teamId != null ? String.valueOf(teamId) : String.valueOf(userId);
        String ftsConfig = properties.ftsConfig();

        List<Document> docs = vectorStoreMapper.bm25Search(
                ftsConfig, sanitized, isolationField, isolationValue, topK);

        List<ScoredDocument> results = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            results.add(new ScoredDocument(docs.get(i), i + 1, 0.0, name()));
        }
        return results;
    }

    @Override
    public RrfWeighting rrfWeighting() {
        return RrfWeighting.RANK_ONLY;
    }
}
