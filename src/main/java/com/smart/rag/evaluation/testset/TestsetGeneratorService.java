package com.smart.rag.evaluation.testset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.evaluation.dataset.DatasetRepository;
import com.smart.rag.evaluation.dataset.EvaluationDataset;
import com.smart.rag.evaluation.dataset.EvaluationDatasetItem;
import com.smart.rag.evaluation.testset.graph.KnowledgeGraph;
import com.smart.rag.evaluation.testset.graph.Node;
import com.smart.rag.evaluation.testset.synthesizers.GeneratedSample;
import com.smart.rag.evaluation.testset.synthesizers.MultiHopAbstractSynthesizer;
import com.smart.rag.evaluation.testset.synthesizers.MultiHopSpecificSynthesizer;
import com.smart.rag.evaluation.testset.synthesizers.Persona;
import com.smart.rag.evaluation.testset.synthesizers.QuerySynthesizer;
import com.smart.rag.evaluation.testset.synthesizers.Scenario;
import com.smart.rag.evaluation.testset.synthesizers.SingleHopSpecificSynthesizer;
import com.smart.rag.evaluation.testset.transforms.ChunkEntityLoader;
import com.smart.rag.evaluation.testset.transforms.EntityOverlapBuilder;
import com.smart.rag.evaluation.testset.transforms.PgVectorParser;
import com.smart.rag.evaluation.testset.transforms.ThemesExtractor;
import com.smart.rag.evaluation.testset.transforms.VectorCosineBuilder;
import com.smart.rag.infrastructure.concurrent.ScopeJoiner;
import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import com.smart.rag.infrastructure.llm.adapter.RewriteClientResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * KG 式测试集生成编排器（翻译 ragas TestsetGenerator.generate_with_chunks 的编排流程，
 * 取代旧的单 chunk 生成路径）。
 * <p>
 * 阶段：采样 chunk（含现成向量）→ 实体装载（唯一来源 rag_chunk_entity）→ 主题抽取
 * （cheap 模型，ScopedTasks 并发）→ 本地建边（实体重叠 + 向量余弦，零 LLM）→
 * 合成器可用性检查与 ceil 分配 → 场景生成 + 样本合成（场景 cheap、样本主模型）→
 * 去重落库。单条失败不中断批次（fork 内吞异常为空贡献，同 ragas raise_exceptions=False
 * 的设计意图；NaN 占位 bug 不翻译）。
 * </p>
 */
@Service
@Profile("evaluation")
public class TestsetGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(TestsetGeneratorService.class);

    /** 生成阶段进度回调（供异步任务 SSE 转发）。 */
    public interface ProgressListener {
        void onProgress(String phase, int current, int total, String message);
    }

    private static final ProgressListener NO_OP = (phase, current, total, message) -> {
    };

    private final JdbcTemplate jdbc;
    private final ChunkEntityLoader entityLoader;
    private final RewriteClientResolver clientResolver;
    private final EvaluationProperties props;
    private final DatasetRepository datasetRepo;
    private final ObjectMapper objectMapper;
    private final ScopedTasks scopedTasks;

    public TestsetGeneratorService(JdbcTemplate jdbc,
                                   ChunkEntityLoader entityLoader,
                                   RewriteClientResolver clientResolver,
                                   EvaluationProperties props,
                                   DatasetRepository datasetRepo,
                                   ObjectMapper objectMapper,
                                   ScopedTasks scopedTasks) {
        this.jdbc = jdbc;
        this.entityLoader = entityLoader;
        this.clientResolver = clientResolver;
        this.props = props;
        this.datasetRepo = datasetRepo;
        this.objectMapper = objectMapper;
        this.scopedTasks = scopedTasks;
    }

    /**
     * 生成数据集并入库。
     *
     * @param name     数据集名称
     * @param userId   采样用户 ID（vector_store 与实体层都按该用户过滤）
     * @param listener 进度回调（可空）
     * @return 创建的数据集（含数据项）
     */
    public EvaluationDataset generate(String name, long userId, ProgressListener listener) {
        var progress = listener == null ? NO_OP : listener;
        var datasetProps = props.getDataset();

        // 1. 数据集记录（source 标识生成方式）
        var dataset = datasetRepo.insertDataset(new EvaluationDataset(
                null, name, "Ragas KG multi-hop dataset for user " + userId,
                0, "ragas_kg", props.getJudgeModel(), 0, null, null, null));
        final long datasetId = dataset.id();

        // 2. 采样 chunk（含现成向量）
        progress.onProgress("sampling", 0, 1, "采样知识库 chunk");
        var chunks = sampleChunks(userId, datasetProps.getMaxChunks());
        if (chunks.isEmpty()) {
            log.warn("No chunks found for userId={}, cannot generate dataset", userId);
            datasetRepo.updateDatasetItemCount(datasetId, 0);
            return withItems(dataset, List.of());
        }
        progress.onProgress("sampling", 1, 1, "采样 " + chunks.size() + " 个 chunk");

        // 3. 建 KG：实体（DB 唯一来源）→ 主题（cheap 模型并发抽取）
        var kg = buildKnowledgeGraph(chunks, userId, progress);

        // 4. 本地建边：实体重叠 + 向量余弦
        progress.onProgress("edges", 0, 2, "构建关系边");
        kg.addRelationships(new EntityOverlapBuilder().build(kg.nodes()));
        kg.addRelationships(new VectorCosineBuilder(datasetProps.getCosineThreshold())
                .build(kg.nodes()));
        progress.onProgress("edges", 2, 2, "实体边 " + kg.relationshipCount(
                com.smart.rag.evaluation.testset.graph.RelationshipType.ENTITY_OVERLAP)
                + "，相似边 " + kg.relationshipCount(
                com.smart.rag.evaluation.testset.graph.RelationshipType.SIMILARITY));

        // 5. 合成器（按数据可用性降级）+ ceil 分配（翻译 calculate_split_values）
        var personas = datasetProps.getPersonas().stream()
                .map(p -> new Persona(p.getName(), p.getRoleDescription()))
                .toList();
        var cheapClient = clientResolver.resolve(props.getGenerationModel());
        var mainClient = clientResolver.resolve(datasetProps.getSynthesisModel());

        var synthesizers = new ArrayList<QuerySynthesizer>();
        var single = new SingleHopSpecificSynthesizer(cheapClient, mainClient, objectMapper, 42);
        var multiSpecific = new MultiHopSpecificSynthesizer(cheapClient, mainClient, objectMapper, 42);
        var multiAbstract = new MultiHopAbstractSynthesizer(cheapClient, mainClient, objectMapper, 42);
        if (multiAbstract.isAvailable(kg)) {
            synthesizers.add(multiAbstract);
        }
        if (multiSpecific.isAvailable(kg)) {
            synthesizers.add(multiSpecific);
        }
        if (single.isAvailable(kg)) {
            synthesizers.add(single);
        }
        if (synthesizers.isEmpty()) {
            log.warn("No synthesizer available: kg has neither entities nor relationships");
            datasetRepo.updateDatasetItemCount(datasetId, 0);
            return withItems(dataset, List.of());
        }
        var availableNames = synthesizers.stream().map(QuerySynthesizer::name).toList();
        log.info("Synthesizers available: {}", availableNames);
        progress.onProgress("scenarios", 0, 1, "合成器: " + String.join(", ", availableNames));

        int target = datasetProps.getSize();
        var splits = splitValues(target, synthesizers.size());

        // 6. 场景生成（逐合成器 fork，失败贡献空）
        record PendingScenario(QuerySynthesizer synthesizer, Scenario scenario) {
        }
        List<PendingScenario> pending = new ArrayList<>();
        try (TaskScope scope = openScope("testset-scenarios")) {
            for (int i = 0; i < synthesizers.size(); i++) {
                var synthesizer = synthesizers.get(i);
                int n = splits[i];
                scope.fork("scenarios-" + synthesizer.name(), () -> {
                    try {
                        return synthesizer.generateScenarios(n, kg, personas);
                    } catch (Exception e) {
                        log.warn("场景生成失败，跳过 {}: {}", synthesizer.name(), e.getMessage());
                        return List.<Scenario>of();
                    }
                });
            }
            @SuppressWarnings("unchecked")
            var results = (List<List<Scenario>>) (List<?>)
                    scope.join(ScopeJoiner.successfulResults(List.class));
            for (int i = 0; i < results.size(); i++) {
                for (var scenario : results.get(i)) {
                    pending.add(new PendingScenario(synthesizers.get(i), scenario));
                }
            }
        }
        progress.onProgress("scenarios", pending.size(), pending.size(),
                "场景 " + pending.size() + " 个");

        // 7. 样本合成（逐场景 fork，失败贡献空）
        List<GeneratedSample> samples = new ArrayList<>();
        try (TaskScope scope = openScope("testset-synthesize")) {
            int total = pending.size();
            for (var p : pending) {
                scope.fork("sample-" + p.scenario().toString(), () -> {
                    try {
                        return p.synthesizer().generateSample(p.scenario());
                    } catch (Exception e) {
                        log.warn("样本合成失败，跳过: {}", e.getMessage());
                        return null;
                    }
                });
            }
            @SuppressWarnings("unchecked")
            var results = (List<GeneratedSample>) (List<?>)
                    scope.join(ScopeJoiner.successfulResults(Object.class));
            progress.onProgress("synthesis", total, total, "样本合成完成");
            results.stream().filter(Objects::nonNull).forEach(samples::add);
        }

        // 8. 后处理：空条目过滤 + 问题去重（对应 Python 参照实现）
        var seen = new LinkedHashSet<String>();
        var items = new ArrayList<EvaluationDatasetItem>();
        int dropped = 0;
        for (var s : samples) {
            var question = s.userInput() == null ? "" : s.userInput().strip();
            if (question.isEmpty() || s.reference() == null || s.reference().isBlank()) {
                dropped++;
                continue;
            }
            if (!seen.add(question)) {
                dropped++;
                continue;
            }
            items.add(new EvaluationDatasetItem(
                    null, datasetId, question, s.reference().strip(),
                    new LinkedHashSet<>(s.relevantChunkIds()),
                    String.join("\n\n", s.referenceContexts()),
                    List.of(s.synthesizerName()), null, items.size()));
        }
        log.info("Generated dataset '{}': {} items ({} dropped)", name, items.size(), dropped);
        progress.onProgress("done", items.size(), items.size(),
                "有效 " + items.size() + " 条，丢弃 " + dropped + " 条");

        if (!items.isEmpty()) {
            items = new ArrayList<>(datasetRepo.insertItems(items));
            datasetRepo.updateDatasetItemCount(datasetId, items.size());
        }
        return withItems(dataset, items);
    }

    private static EvaluationDataset withItems(EvaluationDataset dataset,
                                               List<EvaluationDatasetItem> items) {
        return new EvaluationDataset(dataset.id(), dataset.name(), dataset.description(),
                dataset.version(), dataset.source(), dataset.judgeModel(),
                items.size(), dataset.createdAt(), dataset.updatedAt(), items);
    }

    private KnowledgeGraph buildKnowledgeGraph(List<Map<String, Object>> chunks,
                                               long userId, ProgressListener progress) {
        var kg = new KnowledgeGraph();
        for (var chunk : chunks) {
            var id = String.valueOf(chunk.get("id"));
            var content = (String) chunk.get("content");
            var metadata = parseMetadata(chunk.get("metadata"));
            var embedding = chunk.get("embedding") == null
                    ? null : PgVectorParser.parse(String.valueOf(chunk.get("embedding")));
            kg.addNode(new Node(id, content, metadata, embedding));
        }
        // 实体：唯一来源（无兜底；chunk 无实体行 → 不参与实体边）
        var entitiesByChunk = entityLoader.loadEntities(
                kg.nodes().stream().map(Node::id).toList(), userId);
        kg.nodes().forEach(node ->
                node.setEntities(entitiesByChunk.getOrDefault(node.id(), java.util.Set.of())));
        progress.onProgress("kg_build", kg.nodeCount(), kg.nodeCount(),
                "实体装载完成，" + entitiesByChunk.size() + "/" + kg.nodeCount() + " 个 chunk 有实体");

        // 主题：cheap 模型并发抽取，单 chunk 失败贡献空
        var extractor = new ThemesExtractor(
                clientResolver.resolve(props.getGenerationModel()), objectMapper);
        var themesByNode = new HashMap<String, List<String>>();
        var done = new AtomicInteger();
        try (TaskScope scope = openScope("testset-extract")) {
            int total = kg.nodeCount();
            for (var node : kg.nodes()) {
                scope.fork("themes-" + node.id(), () -> {
                    try {
                        return Map.<String, List<String>>entry(node.id(), extractor.extract(node));
                    } catch (Exception e) {
                        log.warn("Themes 抽取失败: chunk={}, err={}", node.id(), e.getMessage());
                        return Map.<String, List<String>>entry(node.id(), List.of());
                    }
                });
            }
            @SuppressWarnings("unchecked")
            var results = (List<Map.Entry<String, List<String>>>) (List<?>)
                    scope.join(ScopeJoiner.successfulResults(Object.class));
            results.forEach(e -> {
                themesByNode.put(e.getKey(), e.getValue());
                int current = done.incrementAndGet();
                if (current % 20 == 0 || current == total) {
                    progress.onProgress("kg_build", current, total, "主题抽取 " + current + "/" + total);
                }
            });
        }
        kg.nodes().forEach(node -> node.setThemes(themesByNode.getOrDefault(node.id(), List.of())));
        return kg;
    }

    /** 从 vector_store 随机采样 chunk（含 embedding 列，pgvector 文本格式）。 */
    private List<Map<String, Object>> sampleChunks(long userId, int limit) {
        String filterJson;
        try {
            filterJson = objectMapper.writeValueAsString(Map.of("userId", String.valueOf(userId)));
        } catch (Exception e) {
            filterJson = "{\"userId\": \"" + userId + "\"}";
        }
        return jdbc.queryForList("""
                SELECT id, content, metadata, embedding::text AS embedding
                FROM vector_store
                WHERE metadata @> ?::jsonb
                ORDER BY RANDOM()
                LIMIT ?
                """, filterJson, limit);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMetadata(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    /** ceil 分配（翻译 ragas calculate_split_values：n 均分为 k 份，每份 ceil(n/k)，总和可略超 n）。 */
    private static int[] splitValues(int n, int parts) {
        var splits = new int[parts];
        int per = (int) Math.ceil((double) n / parts);
        java.util.Arrays.fill(splits, per);
        return splits;
    }

    private TaskScope openScope(String name) {
        var options = ScopeOptions.builder(name)
                .policy(ScopePolicy.COLLECT_ALL)
                .maxConcurrency(props.getRunner().getConcurrency())
                .defaultTimeout(Duration.ofSeconds(props.getRunner().getItemTimeoutSeconds()))
                .build();
        return scopedTasks.open(name, options);
    }
}
