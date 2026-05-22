package com.smart.rag.rag.evaluation.dataset;

import com.smart.rag.common.util.JsonExtractor;
import com.smart.rag.config.NamedThreadFactory;
import com.smart.rag.rag.evaluation.config.EvaluationProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * LLM 自动生成评估数据集
 * <p>
 * 从 vector_store 表按 userId 随机采样文档 chunk，
 * 对每个 chunk 调用 LLM 生成问题和标准答案。
 * </p>
 */
@Component
@Profile("evaluation")
public class DatasetGenerator {

    private static final Logger log = LoggerFactory.getLogger(DatasetGenerator.class);

    private final JdbcTemplate jdbc;
    private final ChatClient.Builder chatClientBuilder;
    private final EvaluationProperties props;
    private final DatasetRepository datasetRepo;
    private final ObjectMapper objectMapper;

    public DatasetGenerator(JdbcTemplate jdbc,
                            ChatClient.Builder chatClientBuilder,
                            EvaluationProperties props,
                            DatasetRepository datasetRepo,
                            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.chatClientBuilder = chatClientBuilder;
        this.props = props;
        this.datasetRepo = datasetRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成数据集
     *
     * @param name   数据集名称
     * @param userId 采样用户 ID
     * @return 创建的数据集（含数据项）
     */
    public EvaluationDataset generate(String name, Long userId) {
        // 1. 创建数据集记录
        EvaluationDataset dataset = new EvaluationDataset(
                null, name, "LLM auto-generated dataset for user " + userId,
                0, "llm_generated", props.getJudgeModel(), 0, null, null, null);
        dataset = datasetRepo.insertDataset(dataset);
        final long datasetId = dataset.id();
        final String datasetName = dataset.name();
        final String datasetDesc = dataset.description();
        final String datasetSource = dataset.source();
        final String datasetJudgeModel = dataset.judgeModel();
        final var datasetCreatedAt = dataset.createdAt();
        final var datasetUpdatedAt = dataset.updatedAt();

        // 2. 从 vector_store 随机采样 chunk
        List<Map<String, Object>> chunks = sampleChunks(userId, props.getDataset().getSampleSize());
        if (chunks.isEmpty()) {
            log.warn("No chunks found for userId={}, cannot generate dataset", userId);
            return dataset;
        }

        log.info("Sampled {} chunks for dataset generation", chunks.size());

        // 3. 对每个 chunk 并发生成问题（最大并发数从配置读取）
        ChatClient chatClient = chatClientBuilder.build();
        int concurrency = props.getRunner().getConcurrency();
        ExecutorService executor = new ThreadPoolExecutor(
                concurrency, concurrency, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(concurrency * 10),
                new NamedThreadFactory("eval-dataset"),
                new ThreadPoolExecutor.CallerRunsPolicy());

        try {
            List<CompletableFuture<List<EvaluationDatasetItem>>> futures = new ArrayList<>();
            int[] seqCounter = {0};

            for (Map<String, Object> chunk : chunks) {
                String chunkId = String.valueOf(chunk.get("id"));
                String content = (String) chunk.get("content");
                int seq = seqCounter[0]++;

                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        List<GeneratedQuestion> questions = generateQuestions(chatClient, content);
                        List<EvaluationDatasetItem> items = new ArrayList<>();
                        for (GeneratedQuestion q : questions) {
                            items.add(new EvaluationDatasetItem(
                                    null, datasetId, q.question(), q.groundTruthAnswer(),
                                    new HashSet<>(List.of(chunkId)), content,
                                    List.of(q.difficulty(), q.tag()), null, seq));
                        }
                        return items;
                    } catch (Exception e) {
                        log.error("Failed to generate questions for chunk {}: {}", chunkId, e.getMessage(), e);
                        return Collections.<EvaluationDatasetItem>emptyList();
                    }
                }, executor));
            }

            List<EvaluationDatasetItem> allItems = futures.stream()
                    .map(CompletableFuture::join)
                    .flatMap(List::stream)
                    .filter(Objects::nonNull)
                    .toList();

            // 4. 批量插入
            if (!allItems.isEmpty()) {
                allItems = datasetRepo.insertItems(allItems);
                datasetRepo.updateDatasetItemCount(datasetId, allItems.size());
                dataset = new EvaluationDataset(
                        datasetId, datasetName, datasetDesc,
                        dataset.version(), datasetSource, datasetJudgeModel,
                        allItems.size(), datasetCreatedAt, datasetUpdatedAt, allItems);
            }

            log.info("Generated dataset '{}' with {} items", name, allItems.size());
            return dataset;
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 从 vector_store 按用户采样
     */
    private List<Map<String, Object>> sampleChunks(Long userId, int sampleSize) {
        String sql = """
                SELECT id, content FROM vector_store
                WHERE metadata @> ?::jsonb
                ORDER BY RANDOM()
                LIMIT ?
                """;
        String filterJson;
        try {
            filterJson = objectMapper.writeValueAsString(Map.of("userId", String.valueOf(userId)));
        } catch (Exception e) {
            filterJson = "{\"userId\": \"" + userId + "\"}";
        }
        return jdbc.queryForList(sql, filterJson, sampleSize);
    }

    /**
     * 调用 LLM 生成问题
     */
    private List<GeneratedQuestion> generateQuestions(ChatClient chatClient, String chunkContent) {
        String prompt = """
                给定以下文档片段，生成 %d 个该片段可以回答的问题。
                要求：
                - 问题应多样化：包括事实查询、概括总结、推理分析
                - 每个问题附带简短的标准答案
                - 标注难度：easy（直接引用）、medium（需要概括）、hard（需要推理）

                文档片段：
                %s

                输出 JSON 数组（不要输出其他内容）：
                [
                  {
                    "question": "...",
                    "ground_truth_answer": "...",
                    "difficulty": "easy",
                    "tags": ["事实查询"]
                  }
                ]
                """.formatted(props.getDataset().getQuestionsPerChunk(), chunkContent);

        try {
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            if (response == null || response.isBlank()) {
                return Collections.emptyList();
            }
            String json = JsonExtractor.extractJson(response);
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to generate questions for chunk: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * LLM 生成的问题结构
     */
    public record GeneratedQuestion(
            String question,
            String ground_truth_answer,
            String difficulty,
            String tag
    ) {
        public String groundTruthAnswer() {
            return ground_truth_answer;
        }
    }
}
