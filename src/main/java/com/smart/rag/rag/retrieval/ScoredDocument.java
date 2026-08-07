package com.smart.rag.rag.retrieval;

import org.springframework.ai.document.Document;

/**
 * A document annotated with its retrieval rank, score, and originating path name for RRF fusion.
 *
 * @param doc      the retrieved document
 * @param rank     rank within its retrieval path (1-based)
 * @param score    raw similarity/path score (vector=similarity; bm25=0.0)
 * @param pathName the {@link RetrievalPath#name()} that produced this document
 *                 (e.g. "bm25-search", "vector-search"), carried through fusion so
 *                 per-path provenance survives the RRF merge.
 */
public record ScoredDocument(Document doc, int rank, double score, String pathName) {}
