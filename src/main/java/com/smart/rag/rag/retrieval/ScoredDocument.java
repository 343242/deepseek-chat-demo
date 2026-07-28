package com.smart.rag.rag.retrieval;

import org.springframework.ai.document.Document;

/**
 * A document annotated with its retrieval rank and score for RRF fusion.
 */
public record ScoredDocument(Document doc, int rank, double score) {}
