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
import com.smart.rag.evaluation.testset.transforms.NodePotentialFilter;
import com.smart.rag.evaluation.testset.transforms.SummaryExtractor;
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
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * KG 式测试集生成编排器（翻译 ragas TestsetGenerator.generate_with_chunks 的编排流程）。
 * <p>
 * 管线序对齐 ragas {@code default_transforms_for_prechunked}：
 * 采样 chunk → 实体装载（唯一来源 rag_chunk_entity）→ 摘要（cheap 模型）→
 * 问题潜力过滤（≤minScore 剔除）→ 主题抽取 ∥ 摘要向量（cheap 模型并发 / 批量 embed）→
 * 本地建边（实体重叠 + 摘要余弦，零 LLM）→ persona（配置或自动生成）→
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
     * 三个合成器与 persona 补齐重采样共用同一 seed。
     */
    private static final long RANDOM_SEED = 42L;

    /** 主题抽取进度的事件频率（每 N 个 chunk 报一次，避免事件风暴） */
    private static final int THEME_PROGRESS_EVERY = 20;

    /** 摘要向量批量 embed 的批大小（对齐 embedding 候选 params.batch-size） */
    private static final int EMBED_BATCH_SIZE = 20;

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
    private final EmbeddingModel embeddingModel;
    private final TransactionTemplate transactionTemplate;

    public TestsetGeneratorService(JdbcTemplate jdbc,
                                   ChunkEntityLoader entityLoader,
                                   RewriteClientResolver clientResolver,
                                   EvaluationProperties props,
                                   DatasetRepository datasetRepo,
                                   ObjectMapper objectMapper,
                                   ScopedTasks scopedTasks,
                                   EmbeddingModel embeddingModel,
                                   TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.entityLoader = entityLoader;
        this.clientResolver = clientResolver;
        this.props = props;
        this.datasetRepo = datasetRepo;
        this.objectMapper = objectMapper;
        this.scopedTasks = scopedTasks;
        this.embeddingModel = embeddingModel;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 生成数据集并入库。
     * <p>
     * 数据集行不在开头插入：整条 LLM/Embedding 管线先在内存完成，最后通过
     * {@link #persist} 在单个事务内原子落库（数据集 + 数据项 + 条数统计）——
     * 中途任何硬失败（数据库、未预期的运行时异常）都不会留下孤儿数据集或部分 item，
     * 事务也只在快速写路径上短暂持有连接，不会跨越分钟级的 LLM 调用。
     * </p>
     *
     * @param name     数据集名称
     * @param userId   采样用户 ID（vector_store 与实体层都按该用户过滤）
     * @param listener 进度回调（可空）
     * @return 创建的数据集（含数据项）
     */
    public EvaluationDataset generate(String name, long userId, ProgressListener listener) {
        var progress = listener == null ? NO_OP : listener;
        var datasetProps = props.getDataset();

        // 1. 采样 chunk
        progress.onProgress("sampling", 0, 1, "采样知识库 chunk");
        var chunks = sampleChunks(userId, datasetProps.getMaxChunks());
        if (chunks.isEmpty()) {
            log.warn("No chunks found for userId={}, cannot generate dataset", userId);
            return persist(name, userId, List.of());
        }
        progress.onProgress("sampling", 1, 1, "采样 " + chunks.size() + " 个 chunk");

        // 2. 节点 + 实体（DB 唯一来源；chunk 无实体行 → 不参与实体边）
        var nodes = loadNodes(chunks);
        var entitiesByChunk = entityLoader.loadEntities(
                nodes.stream().map(Node::id).toList(), userId);
        nodes.forEach(node ->
                node.setEntities(entitiesByChunk.getOrDefault(node.id(), java.util.Set.of())));
        progress.onProgress("kg_build", nodes.size(), nodes.size(),
                "实体装载完成，" + entitiesByChunk.size() + "/" + nodes.size() + " 个 chunk 有实体");

        // 3. 摘要（cheap 模型并发，单 chunk 失败降级为空摘要）
        summarize(nodes, progress);

        // 4. 问题潜力过滤（ragas CustomNodeFilter：score ≤ minScore 剔除）
        var survivors = filterByPotential(nodes, progress);
        if (survivors.isEmpty()) {
            log.warn("All nodes filtered out by question potential filter");
            return persist(name, userId, List.of());
        }

        // 5. 主题（cheap 模型并发）+ 摘要向量（批量 embed）
        extractThemes(survivors, progress);
        embedSummaries(survivors, progress);

        // 6. KG + 本地建边：实体重叠 + 摘要余弦
        var kg = new KnowledgeGraph();
        survivors.forEach(kg::addNode);
        addRelationships(kg, progress);

        // 7. persona：配置列表非空用配置，否则自动生成（ragas persona_list=None 默认）
        var personas = resolvePersonas(kg, progress);

        // 8. 合成器（按数据可用性降级）
        var synthesizers = buildSynthesizers(kg);
        if (synthesizers.isEmpty()) {
            log.warn("No synthesizer available: kg has neither entities nor relationships");
            return persist(name, userId, List.of());
        }
        var availableNames = synthesizers.stream().map(QuerySynthesizer::name).toList();
        log.info("Synthesizers available: {}", availableNames);
        progress.onProgress("scenarios", 0, 1, "合成器: " + String.join(", ", availableNames));

        // 9-10. 场景生成 + 样本合成（失败贡献空）
        var pending = generateScenarios(synthesizers, kg, personas, progress);
        var samples = synthesizeSamples(pending, progress);

        // 11. 后处理与原子落库
        var items = postProcess(samples, name, progress);
        return persist(name, userId, items);
    }

    /**
     * 数据集 + 数据项 + 条数统计在单个事务内原子落库（写路径快速，事务不跨越 LLM 调用）。
     * 空结果（采样为空/全被过滤/无合成器可用）同样落一个 0 条的数据集，保持既有
     * "job completed + 空 dataset" 语义。
     */
    private EvaluationDataset persist(String name, long userId, List<EvaluationDatasetItem> items) {
        return transactionTemplate.execute(tx -> {
            var inserted = datasetRepo.insertDataset(new EvaluationDataset(
                    null, name, "Ragas KG multi-hop dataset for user " + userId,
                    0, "ragas_kg", props.getJudgeModel(), 0, null, null, null));
            var saved = items.isEmpty()
                    ? List.<EvaluationDatasetItem>of()
                    : datasetRepo.insertItems(bindDatasetId(items, inserted.id()));
            datasetRepo.updateDatasetItemCount(inserted.id(), saved.size());
            return withItems(inserted, saved);
        });
    }

    /** item 在管线内构建时 dataset 尚未创建（落库后置），此处统一绑定真实的 datasetId。 */
    private static List<EvaluationDatasetItem> bindDatasetId(List<EvaluationDatasetItem> items,
                                                             long datasetId) {
        return items.stream()
                .map(i -> new EvaluationDatasetItem(i.id(), datasetId, i.question(),
                        i.groundTruthAnswer(), i.relevantChunkIds(), i.relevantContent(),
                        i.tags(), i.status(), i.seq()))
                .toList();
    }

    private List<Node> loadNodes(List<Map<String, Object>> chunks) {
        var nodes = new ArrayList<Node>(chunks.size());
        for (var chunk : chunks) {
            var id = String.valueOf(chunk.get("id"));
            var content = chunk.get("content") == null ? "" : String.valueOf(chunk.get("content"));
            var metadata = parseMetadata(chunk.get("metadata"));
            nodes.add(new Node(id, content, metadata));
        }
        return nodes;
    }

    /** 摘要抽取（cheap 模型并发）。失败贡献空摘要（节点被过滤跳过、不参与相似边）。 */
    private void summarize(List<Node> nodes, ProgressListener progress) {
        var extractor = new SummaryExtractor(
                clientResolver.resolve(props.getGenerationModel()), objectMapper);
        try (TaskScope scope = openScope("testset-summary")) {
            for (var node : nodes) {
                scope.fork("summary-" + node.id(), () -> Map.<String, String>entry(
                        node.id(), extractor.extract(node)));
            }
            @SuppressWarnings("unchecked")
            var results = (List<Map.Entry<String, String>>) (List<?>)
                    scope.join(ScopeJoiner.successfulResults(Object.class));
            nodes.forEach(node -> node.setSummary(""));
            results.forEach(e -> nodeById(nodes, e.getKey()).ifPresent(
                    n -> n.setSummary(e.getValue() == null ? "" : e.getValue())));
        }
        long summarized = nodes.stream().filter(n -> !n.summary().isBlank()).count();
        progress.onProgress("summary", nodes.size(), nodes.size(),
                "摘要完成 " + summarized + "/" + nodes.size());
    }

    /** 问题潜力过滤（并发打分）。评分失败/无摘要 → 保留（ragas 同款跳过语义）。 */
    private List<Node> filterByPotential(List<Node> nodes, ProgressListener progress) {
        var filter = new NodePotentialFilter(
                clientResolver.resolve(props.getGenerationModel()), objectMapper,
                props.getDataset().getNodeFilter().getMinScore());
        Map<String, Boolean> removals = new HashMap<>();
        try (TaskScope scope = openScope("testset-filter")) {
            for (var node : nodes) {
                scope.fork("filter-" + node.id(), () -> Map.<String, Boolean>entry(
                        node.id(), filter.shouldRemove(node)));
            }
            @SuppressWarnings("unchecked")
            var results = (List<Map.Entry<String, Boolean>>) (List<?>)
                    scope.join(ScopeJoiner.successfulResults(Object.class));
            results.forEach(e -> removals.put(e.getKey(), e.getValue()));
        }
        var survivors = nodes.stream()
                .filter(n -> !removals.getOrDefault(n.id(), Boolean.FALSE))
                .toList();
        progress.onProgress("filter", survivors.size(), nodes.size(),
                "潜力过滤保留 " + survivors.size() + "/" + nodes.size() + " 个 chunk");
        return survivors;
    }

    /** 主题抽取（cheap 模型并发，单 chunk 失败贡献空）。 */
    private void extractThemes(List<Node> nodes, ProgressListener progress) {
        var extractor = new ThemesExtractor(
                clientResolver.resolve(props.getGenerationModel()), objectMapper);
        var themesByNode = new HashMap<String, List<String>>();
        var done = new AtomicInteger();
        try (TaskScope scope = openScope("testset-extract")) {
            int total = nodes.size();
            for (var node : nodes) {
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
        nodes.forEach(node -> node.setThemes(themesByNode.getOrDefault(node.id(), List.of())));
    }

    /** 摘要向量（批量 embed；无摘要/批量失败的节点不带向量，不参与相似边与 persona 分组）。 */
    private void embedSummaries(List<Node> nodes, ProgressListener progress) {
        var withSummary = nodes.stream().filter(n -> !n.summary().isBlank()).toList();
        for (int i = 0; i < withSummary.size(); i += EMBED_BATCH_SIZE) {
            var batch = withSummary.subList(i, Math.min(i + EMBED_BATCH_SIZE, withSummary.size()));
            try {
                var embeddings = embeddingModel.embed(
                        batch.stream().map(Node::summary).toList());
                for (int j = 0; j < batch.size() && j < embeddings.size(); j++) {
                    var vector = embeddings.get(j);
                    if (vector != null && vector.length > 0) {
                        batch.get(j).setSummaryEmbedding(convert(vector));
                    }
                }
            } catch (Exception e) {
                log.warn("摘要向量批量 embed 失败（batch {}），该批降级为无向量", i / EMBED_BATCH_SIZE, e);
            }
        }
        long embedded = nodes.stream().filter(n -> n.summaryEmbedding() != null).count();
        progress.onProgress("embed", (int) embedded, nodes.size(),
                "摘要向量完成 " + embedded + "/" + nodes.size());
    }

    private static double[] convert(float[] vector) {
        var result = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = vector[i];
        }
        return result;
    }

    private List<Persona> resolvePersonas(KnowledgeGraph kg, ProgressListener progress) {
        var configured = props.getDataset().getPersonas();
        if (configured != null && !configured.isEmpty()) {
            var personas = configured.stream()
                    .map(p -> new Persona(p.getName(), p.getRoleDescription()))
                    .toList();
            progress.onProgress("personas", personas.size(), personas.size(),
                    "使用配置 persona " + personas.size() + " 个");
            return personas;
        }
        var generator = new PersonaGenerator(
                clientResolver.resolve(props.getDataset().getSynthesisModel()),
                objectMapper, scopedTasks);
        var personas = generator.generate(kg.nodes(),
                props.getDataset().getNumPersonas(), RANDOM_SEED);
        progress.onProgress("personas", personas.size(), personas.size(),
                "自动生成 persona " + personas.size() + " 个");
        return personas;
    }

    private static java.util.Optional<Node> nodeById(List<Node> nodes, String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
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

    /** 空条目过滤 + 问题去重（对应 Python 参照实现）。datasetId 由 persist 落库时统一绑定。 */
    private List<EvaluationDatasetItem> postProcess(List<GeneratedSample> samples,
                                                    String name,
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
                    null, null, question, s.reference().strip(),
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

    /**
     * 从 vector_store 随机采样 chunk（相似边输入为摘要向量，无需取 embedding 列）。
     * <p>
     * 两段式避免 {@code ORDER BY RANDOM()} 对全部命中行排序：先用 {@code random() < 0.1}
     * 预筛收窄排序集（命中行数远大于 limit 时显著降低排序量），采样不足 limit 再退回
     * 无预筛查询兜底——两级每行入选概率均等，保持均匀无偏。
     * </p>
     */
    private List<Map<String, Object>> sampleChunks(long userId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        // vector_store.metadata 为 json 类型（V2，Spring AI pgvector schema），@> 包含操作符仅 jsonb 可用；
        // 过滤沿用全库惯例 metadata->>（VectorStoreMapper.xml），userId 入库为字符串（EtlPipelineServiceImpl）
        var prefiltered = jdbc.queryForList("""
                SELECT id, content, metadata
                FROM vector_store
                WHERE metadata->>'userId' = ? AND random() < 0.1
                ORDER BY RANDOM()
                LIMIT ?
                """, String.valueOf(userId), limit);
        if (prefiltered.size() >= limit) {
            return prefiltered;
        }
        return jdbc.queryForList("""
                SELECT id, content, metadata
                FROM vector_store
                WHERE metadata->>'userId' = ?
                ORDER BY RANDOM()
                LIMIT ?
                """, String.valueOf(userId), limit);
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
