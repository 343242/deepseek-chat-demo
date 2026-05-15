package com.demo.chat.rag.evaluation.controller;

import com.demo.chat.rag.evaluation.config.EvaluationProperties;
import com.demo.chat.rag.evaluation.dataset.DatasetRepository;
import com.demo.chat.rag.evaluation.dataset.EvaluationDataset;
import com.demo.chat.rag.evaluation.dataset.EvaluationDatasetItem;
import com.demo.chat.rag.evaluation.result.EvaluationResult;
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

/**
 * 评估运行管理 REST API
 */
@RestController
@RequestMapping("/api/evaluation/runs")
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

        // 创建运行记录
        EvaluationRun run = new EvaluationRun();
        run.setDatasetId(datasetId);
        run.setName(name);
        run.setGenerationModel(evalProps.getGenerationModel());
        run.setJudgeModel(evalProps.getJudgeModel());

        // 构建配置快照
        try {
            run.setConfigSnapshot(objectMapper.writeValueAsString(configOverride != null ? configOverride : Map.of()));
        } catch (Exception e) {
            run.setConfigSnapshot("{}");
        }

        run = resultRepo.insertRun(run);
        resultRepo.markRunStarted(run.getId());

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
                EvaluationResult result = evaluationRunner.evaluate(item, config);
                result.setRunId(run.getId());
                resultRepo.insertResult(result);
                if (result.getError() == null) {
                    successCount++;
                } else {
                    failCount++;
                }
                totalLatency += result.getLatencyMs();
            } catch (Exception e) {
                log.error("Failed to evaluate item {}: {}", item.getId(), e.getMessage(), e);
                failCount++;
            }
        }

        // 构建汇总
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalItems", items.size());
        summary.put("successItems", successCount);
        summary.put("failedItems", failCount);
        summary.put("avgLatencyMs", items.isEmpty() ? 0 : totalLatency / items.size());

        try {
            String summaryJson = objectMapper.writeValueAsString(summary);
            resultRepo.updateRunStatus(run.getId(), "completed", summaryJson);
        } catch (Exception e) {
            resultRepo.updateRunStatus(run.getId(), "completed", "{}");
        }

        return ResponseEntity.ok(Map.of(
                "runId", run.getId(),
                "name", name,
                "status", "completed",
                "summary", summary
        ));
    }

    /**
     * 列出运行（分页+过滤）
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listRuns(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        List<EvaluationRun> runs = resultRepo.listRuns(page, size, status);
        int total = resultRepo.countRuns(status);
        return ResponseEntity.ok(Map.of(
                "runs", runs,
                "total", total,
                "page", page,
                "size", size
        ));
    }

    /**
     * 运行详情 + 聚合指标
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getRun(@PathVariable long id) {
        return resultRepo.findRunById(id)
                .<ResponseEntity<?>>map(run -> ResponseEntity.ok(Map.of(
                        "run", run,
                        "resultCount", resultRepo.countResultsByRunId(id)
                )))
                .orElse(ResponseEntity.status(404)
                        .body(Map.of("error", "Run not found: " + id)));
    }

    /**
     * 逐条结果（分页）
     */
    @GetMapping("/{id}/results")
    public ResponseEntity<Map<String, Object>> getResults(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<Map<String, Object>> results = resultRepo.listResultsByRunId(id, page, size);
        int total = resultRepo.countResultsByRunId(id);
        return ResponseEntity.ok(Map.of(
                "results", results,
                "total", total,
                "page", page,
                "size", size
        ));
    }

    /**
     * 多次运行对比
     */
    @GetMapping("/compare")
    public ResponseEntity<?> compareRuns(@RequestParam String ids) {
        String[] idArray = ids.split(",");
        Map<String, Object> comparison = new HashMap<>();
        for (String idStr : idArray) {
            long runId = Long.parseLong(idStr.trim());
            var run = resultRepo.findRunById(runId);
            if (run.isPresent()) {
                comparison.put(run.get().getName(), Map.of(
                        "runId", runId,
                        "summary", run.get().getSummary()
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
