package com.demo.chat.rag.evaluation.controller;

import com.demo.chat.rag.evaluation.config.EvaluationProperties;
import com.demo.chat.rag.evaluation.dataset.DatasetRepository;
import com.demo.chat.rag.evaluation.dataset.EvaluationDatasetItem;
import com.demo.chat.rag.evaluation.result.EvaluationResultRepository;
import com.demo.chat.rag.evaluation.runner.EvaluationRun;
import com.demo.chat.rag.evaluation.runner.EvaluationRunner;
import com.demo.chat.rag.evaluation.runner.EvaluationRunner.EvalConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 评估运行管理 REST API
 */
@RestController
@RequestMapping("/api/evaluation/runs")
@PreAuthorize("hasAuthority('evaluation:manage')")
public class EvaluationRunController {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunController.class);

    private final EvaluationRunner evaluationRunner;
    private final EvaluationResultRepository resultRepo;
    private final DatasetRepository datasetRepo;
    private final EvaluationProperties evalProps;
    private final ObjectMapper objectMapper;

    public EvaluationRunController(EvaluationRunner evaluationRunner,
                                   EvaluationResultRepository resultRepo,
                                   DatasetRepository datasetRepo,
                                   EvaluationProperties evalProps,
                                   ObjectMapper objectMapper) {
        this.evaluationRunner = evaluationRunner;
        this.resultRepo = resultRepo;
        this.datasetRepo = datasetRepo;
        this.evalProps = evalProps;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动评估运行
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> startRun(
            @RequestBody Map<String, Object> request) {
        Long datasetId = ((Number) request.get("datasetId")).longValue();
        String name = (String) request.getOrDefault("name", "run-" + System.currentTimeMillis());

        @SuppressWarnings("unchecked")
        Map<String, Object> configOverride = (Map<String, Object>) request.get("configOverride");

        // 验证数据集存在
        var dataset = datasetRepo.findDatasetById(datasetId);
        if (dataset.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Dataset not found: " + datasetId));
        }

        // 创建运行记录（record 构造器）
        String configSnapshot;
        try {
            configSnapshot = objectMapper.writeValueAsString(configOverride != null ? configOverride : Map.of());
        } catch (Exception e) {
            configSnapshot = "{}";
        }

        EvaluationRun run = new EvaluationRun(
                null, datasetId, name, configSnapshot, null,
                evalProps.getGenerationModel(), evalProps.getJudgeModel(),
                null, null, null, null);

        run = resultRepo.insertRun(run);
        resultRepo.markRunStarted(run.id());

        // 构建评估配置
        EvalConfig config = buildEvalConfig(configOverride);

        // 获取数据项
        List<EvaluationDatasetItem> items = datasetRepo.listItemsByDatasetId(datasetId);

        // 执行评估（串行）
        int successCount = 0;
        int failCount = 0;
        long totalLatency = 0;

        for (EvaluationDatasetItem item : items) {
            try {
                var result = evaluationRunner.evaluate(item, config);
                resultRepo.insertResult(new com.demo.chat.rag.evaluation.result.EvaluationResult(
                        null, run.id(), result.itemId(), result.itemQuestionSnapshot(),
                        result.itemGroundTruthSnapshot(), result.itemRelevantChunkIdsSnapshot(),
                        result.queryRewritten(), result.retrievedDocIds(),
                        result.generatedAnswer(), result.stageSnapshots(),
                        result.retrievalMetrics(), result.generationMetrics(),
                        result.error(), result.latencyMs()));
                successCount++;
                totalLatency += result.latencyMs();
            } catch (Exception e) {
                log.error("Failed to evaluate item {}: {}", item.id(), e.getMessage(), e);
                failCount++;
            }
        }

        // 更新运行状态
        String status = failCount == 0 ? "completed" : (successCount > 0 ? "completed" : "failed");
        Map<String, Object> summary = Map.of(
                "totalItems", items.size(),
                "successCount", successCount,
                "failCount", failCount,
                "avgLatencyMs", successCount > 0 ? totalLatency / successCount : 0
        );
        try {
            resultRepo.updateRunStatus(run.id(), status, objectMapper.writeValueAsString(summary));
        } catch (Exception e) {
            resultRepo.updateRunStatus(run.id(), status, "{}");
        }

        return ResponseEntity.ok(Map.of(
                "runId", run.id(),
                "status", status,
                "summary", summary
        ));
    }

    /**
     * 获取运行详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getRun(@PathVariable long id) {
        return resultRepo.findRunById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(404)
                        .body(Map.of("error", "Run not found: " + id)));
    }

    /**
     * 列出运行（按数据集）
     */
    @GetMapping(params = "datasetId")
    public ResponseEntity<Map<String, Object>> listRuns(@RequestParam long datasetId) {
        List<EvaluationRun> runs = resultRepo.listRunsByDatasetId(datasetId);
        return ResponseEntity.ok(Map.of("runs", runs));
    }

    /**
     * 获取运行结果
     */
    @GetMapping("/{runId}/results")
    public ResponseEntity<Map<String, Object>> listResults(
            @PathVariable long runId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<Map<String, Object>> results = resultRepo.listResultsByRunId(runId, page, size);
        int total = resultRepo.countResultsByRunId(runId);
        return ResponseEntity.ok(Map.of("results", results, "total", total));
    }

    /**
     * 对比多次运行
     */
    @PostMapping("/compare")
    public ResponseEntity<Map<String, Object>> compareRuns(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Number> runIds = (List<Number>) request.get("runIds");
        if (runIds == null || runIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "runIds is required"));
        }

        Map<String, Object> comparison = new HashMap<>();
        for (Number runId : runIds) {
            var run = resultRepo.findRunById(runId.longValue());
            if (run.isPresent()) {
                comparison.put(run.get().name(), Map.of(
                        "runId", runId,
                        "summary", run.get().summary()
                ));
            }
        }
        return ResponseEntity.ok(comparison);
    }

    private EvalConfig buildEvalConfig(Map<String, Object> override) {
        EvalConfig config = new EvalConfig();
        if (override == null) return config;

        if (override.containsKey("vectorTopK"))
            config.setVectorTopK(((Number) override.get("vectorTopK")).intValue());
        if (override.containsKey("bm25TopK"))
            config.setBm25TopK(((Number) override.get("bm25TopK")).intValue());
        if (override.containsKey("rrfK"))
            config.setRrfK(((Number) override.get("rrfK")).intValue());
        if (override.containsKey("rerankEnabled"))
            config.setRerankEnabled((Boolean) override.get("rerankEnabled"));
        if (override.containsKey("mmrEnabled"))
            config.setMmrEnabled((Boolean) override.get("mmrEnabled"));
        if (override.containsKey("parentChildEnabled"))
            config.setParentChildEnabled((Boolean) override.get("parentChildEnabled"));
        if (override.containsKey("queryRewriteEnabled"))
            config.setQueryRewriteEnabled((Boolean) override.get("queryRewriteEnabled"));
        if (override.containsKey("generationEnabled"))
            config.setGenerationEnabled((Boolean) override.get("generationEnabled"));
        if (override.containsKey("topK"))
            config.setTopK(((Number) override.get("topK")).intValue());
        if (override.containsKey("testUserId"))
            config.setTestUserId(((Number) override.get("testUserId")).longValue());

        return config;
    }
}
