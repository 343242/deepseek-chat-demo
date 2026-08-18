package com.smart.rag.evaluation.testset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.evaluation.dataset.DatasetRepository;
import com.smart.rag.evaluation.dataset.EvaluationDataset;
import com.smart.rag.evaluation.dataset.EvaluationDatasetItem;
import com.smart.rag.evaluation.testset.graph.KnowledgeGraph;
import com.smart.rag.evaluation.testset.graph.Node;
import com.smart.rag.evaluation.testset.graph.RelationshipType;
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
public class TestsetGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(TestsetGeneratorService.class);

    /**
     * 合成器随机种子：固定值保证同输入可复现（对齐 ragas 默认 42 的实验可复现语义）。
     * 三个合成器共用同一 seed。
     */
    private static final long RANDOM_SEED = 42L;

    /** 主题抽取进度的事件频率（每 N 个 chunk 报一次，避免事件风暴） */
    private static final int THEME_PROGRESS_EVERY = 20;

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
        addRelationships(kg, progress);

        // 5. 合成器（按数据可用性降级）
        var personas = datasetProps.getPersonas().stream()
                .map(p -> new Persona(p.getName(), p.getRoleDescription()))
                .toList();
        var synthesizers = buildSynthesizers(kg);
        if (synthesizers.isEmpty()) {
            log.warn("No synthesizer available: kg has neither entities nor relationships");
            datasetRepo.updateDatasetItemCount(datasetId, 0);
            return withItems(dataset, List.of());
        }
        var availableNames = synthesizers.stream().map(QuerySynthesizer::name).toList();
        log.info("Synthesizers available: {}", availableNames);
        progress.onProgress("scenarios", 0, 1, "合成器: " + String.join(", ", availableNames));

        // 6-7. 场景生成 + 样本合成（失败贡献空）
        var pending = generateScenarios(synthesizers, kg, personas, progress);
        var samples = synthesizeSamples(pending, progress);

        // 8. 后处理与落库
        var items = postProcess(samples, datasetId, name, progress);
        if (!items.isEmpty()) {
            items = new ArrayList<>(datasetRepo.insertItems(items));
            datasetRepo.updateDatasetItemCount(datasetId, items.size());
        }
        return withItems(dataset, items);
    }

    private void addRelationships(KnowledgeGraph kg, ProgressListener progress) {
        var datasetProps = props.getDataset();
        progress.onProgress("edges", 0, 2, "构建关系边");
        kg.addRelationships(new EntityOverlapBuilder().build(kg.nodes()));
        kg.addRelationships(new VectorCosineBuilder(datasetProps.getCosineThreshold())
                .build(kg.nodes()));
        progress.onProgress("edges", 2, 2, "实体边 " + kg.relationshipCount(
                RelationshipType.ENTITY_OVERLAP)
                + "，相似边 " + kg.relationshipCount(RelationshipType.SIMILARITY));
    }

    private List<QuerySynthesizer> buildSynthesizers(KnowledgeGraph kg) {
        var cheapClient = clientResolver.resolve(props.getGenerationModel());
        var mainClient = clientResolver.resolve(props.getDataset().getSynthesisModel());

        var synthesizers = new ArrayList<QuerySynthesizer>();
        var multiAbstract = new MultiHopAbstractSynthesizer(cheapClient, mainClient, objectMapper, RANDOM_SEED);
        var multiSpecific = new MultiHopSpecificSynthesizer(cheapClient, mainClient, objectMapper, RANDOM_SEED);
        var single = new SingleHopSpecificSynthesizer(cheapClient, mainClient, objectMapper, RANDOM_SEED);
        if (multiAbstract.isAvailable(kg)) {
            synthesizers.add(multiAbstract);
        }
        if (multiSpecific.isAvailable(kg)) {
            synthesizers.add(multiSpecific);
        }
        if (single.isAvailable(kg)) {
            synthesizers.add(single);
        }
        return synthesizers;
    }

    /** 场景 + 其所属合成器（fork 返回值自带配对，不依赖 join 结果的下标与提交顺序一致） */
    private record PendingScenario(QuerySynthesizer synthesizer, Scenario scenario) {
    }

    private List<PendingScenario> generateScenarios(List<QuerySynthesizer> synthesizers,
                                                    KnowledgeGraph kg,
                                                    List<Persona> personas,
                                                    ProgressListener progress) {
        record ScenariosOf(QuerySynthesizer synthesizer, List<Scenario> scenarios) {
        }
        int target = props.getDataset().getSize();
        var splits = splitValues(target, synthesizers.size());

        List<PendingScenario> pending = new ArrayList<>();
        try (TaskScope scope = openScope("testset-scenarios")) {
            for (int i = 0; i < synthesizers.size(); i++) {
                var synthesizer = synthesizers.get(i);
                int n = splits[i];
                scope.fork("scenarios-" + synthesizer.name(), () -> {
                    try {
                        return new ScenariosOf(synthesizer, synthesizer.generateScenarios(n, kg, personas));
                    } catch (Exception e) {
                        log.warn("场景生成失败，跳过 {}", synthesizer.name(), e);
                        return new ScenariosOf(synthesizer, List.of());
                    }
                });
            }
            @SuppressWarnings("unchecked")
            var results = (List<ScenariosOf>) (List<?>)
                    scope.join(ScopeJoiner.successfulResults(Object.class));
            for (var r : results) {
                for (var scenario : r.scenarios()) {
                    pending.add(new PendingScenario(r.synthesizer(), scenario));
                }
            }
        }
        progress.onProgress("scenarios", pending.size(), pending.size(),
                "场景 " + pending.size() + " 个");
        return pending;
    }

    private List<GeneratedSample> synthesizeSamples(List<PendingScenario> pending,
                                                    ProgressListener progress) {
        List<GeneratedSample> samples = new ArrayList<>();
        try (TaskScope scope = openScope("testset-synthesize")) {
            for (var p : pending) {
                scope.fork("sample-" + p.synthesizer().name(), () -> {
                    try {
                        return p.synthesizer().generateSample(p.scenario());
                    } catch (Exception e) {
                        log.warn("样本合成失败，跳过: {}", e);
                        return null;
                    }
                });
            }
            @SuppressWarnings("unchecked")
            var results = (List<GeneratedSample>) (List<?>)
                    scope.join(ScopeJoiner.successfulResults(Object.class));
            progress.onProgress("synthesis", pending.size(), pending.size(), "样本合成完成");
            results.stream().filter(Objects::nonNull).forEach(samples::add);
        }
        return samples;
    }

    /** 空条目过滤 + 问题去重（对应 Python 参照实现）。返回带 seq 的待落库条目。 */
    private List<EvaluationDatasetItem> postProcess(List<GeneratedSample> samples,
                                                    long datasetId, String name,
                                                    ProgressListener progress) {
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
        return items;
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
            var content = chunk.get("content") == null ? "" : String.valueOf(chunk.get("content"));
            var metadata = parseMetadata(chunk.get("metadata"));
            // 单 chunk 的 embedding 文本损坏只降级为无向量（不参与相似边），不废掉整批
            double[] embedding = null;
            if (chunk.get("embedding") != null) {
                try {
                    embedding = PgVectorParser.parse(String.valueOf(chunk.get("embedding")));
                } catch (Exception e) {
                    log.warn("Embedding 解析失败，chunk {} 降级为无向量", id, e);
                }
            }
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
                        log.warn("Themes 抽取失败: chunk={}", node.id(), e);
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
                if (current % THEME_PROGRESS_EVERY == 0 || current == total) {
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
