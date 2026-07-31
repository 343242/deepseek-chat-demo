package com.smart.rag.rag.retrieval.path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.Subtask;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import com.smart.rag.rag.retrieval.RetrievalPath;
import com.smart.rag.rag.retrieval.ScoredDocument;
import com.smart.rag.rag.retrieval.entity.EntityExpansionRetriever;
import com.smart.rag.rag.retrieval.entity.EntityFrontierRanker;
import com.smart.rag.rag.retrieval.entity.EntitySeedExtractor;
import com.smart.rag.rag.retrieval.entity.EntityVoteRetriever;
import com.smart.rag.rag.retrieval.entity.ExpandedChunk;
import com.smart.rag.rag.retrieval.entity.ScoredEntity;
import com.smart.rag.rag.retrieval.entity.VotedChunk;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Path C 编排入口（§6.1 / §6.5）。
 * <p>
 * 实现 {@link RetrievalPath}，当 {@code app.rag.entity.enabled=true} 时由 Spring 自动注册到
 * {@code HybridSearchService.paths}（构造注入 {@code List<RetrievalPath>}），HybridSearchService 零改动。
 * <p>
 * CARP：合成 4 个组件（seed extractor / frontier ranker / vote retriever / expansion retriever），
 * 自身仅编排 PC1→PC2-3→PC4a∥PC4b→PC5 + trace。
 */
@Component
@ConditionalOnProperty(prefix = "app.rag.entity", name = "enabled", havingValue = "true")
public class EntityRetrievalPath implements RetrievalPath {

    private static final Logger log = LoggerFactory.getLogger(EntityRetrievalPath.class);
    private static final ObjectMapper TRACE_MAPPER = new ObjectMapper();
    private static final long STEP_TIMEOUT_MS = 5000;

    private final EntitySeedExtractor seedExtractor;
    private final EntityFrontierRanker frontierRanker;
    private final EntityVoteRetriever voteRetriever;
    private final EntityExpansionRetriever expansionRetriever;
    private final ScopedTasks scopedTasks;

    public EntityRetrievalPath(EntitySeedExtractor seedExtractor,
                               EntityFrontierRanker frontierRanker,
                               EntityVoteRetriever voteRetriever,
                               EntityExpansionRetriever expansionRetriever,
                               ScopedTasks scopedTasks) {
        this.seedExtractor = seedExtractor;
        this.frontierRanker = frontierRanker;
        this.voteRetriever = voteRetriever;
        this.expansionRetriever = expansionRetriever;
        this.scopedTasks = scopedTasks;
    }

    @Override
    public String name() {
        return "entity-search";
    }

    @Override
    public RrfWeighting rrfWeighting() {
        return RrfWeighting.SCORE_WEIGHTED;
    }

    @Override
    public List<ScoredDocument> search(String query, long userId, @Nullable Long teamId) {
        long totalStart = System.currentTimeMillis();
        List<Map<String, Object>> steps = new ArrayList<>();

        // PC1: query → seed entities
        long t = System.currentTimeMillis();
        List<String> seeds = seedExtractor.extract(query);
        steps.add(step("entity_extraction", System.currentTimeMillis() - t,
                Map.of("seedEntities", seeds)));

        if (seeds.isEmpty()) {
            log.debug("Path C: no seed entities extracted, returning empty");
            return finishTrace(steps, totalStart, 0);
        }

        // PC2-3: seed → vector match → fusion → frontier
        t = System.currentTimeMillis();
        List<ScoredEntity> frontier = frontierRanker.rank(seeds, userId, teamId);
        steps.add(step("fusion_ranking", System.currentTimeMillis() - t,
                Map.of("frontierSize", frontier.size())));

        if (frontier.isEmpty()) {
            log.debug("Path C: no frontier entities matched, returning empty");
            return finishTrace(steps, totalStart, 0);
        }

        // PC4a ∥ PC4b: vote backlink ∥ SAG expansion (parallel via ScopedTasks)
        t = System.currentTimeMillis();
        ScopeOptions options = ScopeOptions.builder("entity-path-c4")
                .policy(ScopePolicy.COLLECT_ALL)
                .defaultTimeout(Duration.ofMillis(STEP_TIMEOUT_MS))
                .build();
        List<VotedChunk> voted;
        List<ExpandedChunk> expanded;
        try (var scope = scopedTasks.open("entity-path-c4", options)) {
            Subtask<List<VotedChunk>> voteTask =
                    scope.fork("vote", () -> voteRetriever.retrieve(frontier, userId));
            Subtask<List<ExpandedChunk>> expandTask =
                    scope.fork("expand", () -> expansionRetriever.retrieve(frontier, userId, teamId));
            scope.join();
            voted = taskResult(voteTask, "vote");
            expanded = taskResult(expandTask, "expand");
        }
        long c4Ms = System.currentTimeMillis() - t;
        steps.add(step("vote_backlink", c4Ms, Map.of("voteChunks", voted.size())));
        steps.add(step("sag_expansion", c4Ms, Map.of("expandChunks", expanded.size())));

        // PC5: merge + dedup (by chunkId, keep max chunkScore)
        t = System.currentTimeMillis();
        List<ScoredDocument> merged = mergeAndDedup(voted, expanded);
        steps.add(step("merge", System.currentTimeMillis() - t,
                Map.of("voteChunks", voted.size(), "expandChunks", expanded.size(),
                        "mergedChunks", merged.size())));

        return finishTrace(steps, totalStart, merged.size(), merged);
    }

    /**
     * PC5：vote + expand 合并去重（按 chunkId，取 max chunkScore），重新排序并封装为 ScoredDocument。
     */
    private List<ScoredDocument> mergeAndDedup(List<VotedChunk> voted, List<ExpandedChunk> expanded) {
        Map<UUID, Double> bestScore = new HashMap<>();
        Map<UUID, VotedChunk> votedById = new HashMap<>();
        Map<UUID, ExpandedChunk> expandedById = new HashMap<>();

        for (VotedChunk vc : voted) {
            bestScore.merge(vc.chunkId(), vc.chunkScore(), Math::max);
            votedById.putIfAbsent(vc.chunkId(), vc);
        }
        for (ExpandedChunk ec : expanded) {
            bestScore.merge(ec.chunkId(), ec.chunkScore(), Math::max);
            expandedById.putIfAbsent(ec.chunkId(), ec);
        }

        // 优先 vote chunk（有 content），其次 expand chunk
        List<Map.Entry<UUID, Double>> sorted = bestScore.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .toList();

        List<ScoredDocument> result = new ArrayList<>(sorted.size());
        int rank = 1;
        for (var entry : sorted) {
            UUID chunkId = entry.getKey();
            double score = entry.getValue();
            VotedChunk vc = votedById.get(chunkId);
            String content = vc != null ? vc.content()
                    : (expandedById.containsKey(chunkId) ? expandedById.get(chunkId).content() : "");
            String metadataJson = vc != null ? vc.metadata()
                    : (expandedById.containsKey(chunkId) ? expandedById.get(chunkId).metadata() : "{}");

            Map<String, Object> meta = parseMetadata(metadataJson);
            meta.put("chunkId", chunkId.toString());
            meta.put("entityScore", score);
            meta.put("path", "C");
            if (vc != null && vc.votedByEntities() != null) {
                meta.put("votedByEntities", vc.votedByEntities());
            }
            result.add(new ScoredDocument(new Document(chunkId.toString(), content, meta), rank++, score));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return TRACE_MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T taskResult(Subtask<T> task, String name) {
        if (task.exception() != null) {
            log.warn("Path C {} subtask failed (degraded to empty): {}", name, task.exception().getMessage(), task.exception());
            return (T) List.of();
        }
        return task.result();
    }

    private Map<String, Object> step(String name, long durationMs, Map<String, Object> extra) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("step", name);
        s.put("durationMs", durationMs);
        s.putAll(extra);
        return s;
    }

    private List<ScoredDocument> finishTrace(List<Map<String, Object>> steps, long totalStart, int resultSize) {
        return finishTrace(steps, totalStart, resultSize, List.of());
    }

    private List<ScoredDocument> finishTrace(List<Map<String, Object>> steps, long totalStart,
                                              int resultSize, List<ScoredDocument> result) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("path", "C");
        trace.put("steps", steps);
        trace.put("totalDurationMs", System.currentTimeMillis() - totalStart);
        trace.put("resultSize", resultSize);
        log.info("Path C trace: {}", TRACE_MAPPER.valueToTree(trace).toString());
        return result;
    }
}
