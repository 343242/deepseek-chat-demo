package com.smart.rag.rag.retrieval;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Pluggable retrieval path for hybrid search.
 * <p>
 * Each path provides its own retrieval strategy and declares how its results
 * should be weighted during RRF fusion. New paths (e.g. entity retrieval) are
 * added by implementing this interface and registering a {@code @Component}.
 */
public interface RetrievalPath {

    /**
     * Logical name used for ScopedTasks fork naming and logging.
     */
    String name();

    /**
     * Execute retrieval for the given normalized query.
     *
     * @param query  normalized query text
     * @param userId user ID for isolation
     * @param teamId team ID for isolation (null = use userId)
     * @return scored documents ordered by relevance
     */
    List<ScoredDocument> search(String query, long userId, @Nullable Long teamId);

    /**
     * Declares how this path's scores participate in RRF fusion.
     */
    RrfWeighting rrfWeighting();

    enum RrfWeighting {
        /** Vector / entity paths: score * 1/(k+rank) */
        SCORE_WEIGHTED,
        /** BM25 / keyword paths: 1/(k+rank) */
        RANK_ONLY
    }
}
