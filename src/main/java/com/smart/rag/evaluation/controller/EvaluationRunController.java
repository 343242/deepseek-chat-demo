package com.smart.rag.evaluation.controller;

import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.evaluation.dataset.DatasetRepository;
import com.smart.rag.evaluation.dataset.EvaluationDatasetItem;
import com.smart.rag.evaluation.result.EvaluationResultRepository;
import com.smart.rag.evaluation.runner.EvaluationRun;
import com.smart.rag.evaluation.runner.EvaluationExecutionService;
import com.smart.rag.evaluation.runner.EvaluationExecutionService.RunSummary;
import com.smart.rag.evaluation.runner.EvaluationRunner.EvalConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 评估运行管理 REST API
 */
@RestController
@Profile("evaluation")
@RequestMapping("/api/evaluation/runs")
@PreAuthorize("hasAuthority('evaluation:manage')")
public class EvaluationRunController {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunController.class);

    private final EvaluationExecutionService executionService;
    private final EvaluationResultRepository resultRepo;
    private final DatasetRepository datasetRepo;
    private final EvaluationProperties evalProps;
    private final ObjectMapper objectMapper;

    public EvaluationRunController(EvaluationExecutionService executionService,
                                   EvaluationResultRepository resultRepo,
                                   DatasetRepository datasetRepo,
                                   EvaluationProperties evalProps,
                                   ObjectMapper objectMapper) {
        this.executionService = executionService;
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
        EvaluationRun run = executionService.createRun(datasetId, name, configOverride);

        // 构建评估配置
        EvalConfig config = buildEvalConfig(configOverride);

        // 获取数据项
        List<EvaluationDatasetItem> items = datasetRepo.listItemsByDatasetId(datasetId);

        // 执行评估
        RunSummary summary = executionService.executeRun(run, items, config);

        return ResponseEntity.ok(Map.of(
                "runId", summary.runId(),
                "status", summary.status().getValue(),
                "summary", summary.summary()
        ));
    }

    /**
     * 列出某数据集的所有运行
     */
    @GetMapping("/dataset/{datasetId}")
    public ResponseEntity<Map<String, Object>> listRuns(@PathVariable long datasetId) {
        List<EvaluationRun> runs = resultRepo.listRunsByDatasetId(datasetId);
        return ResponseEntity.ok(Map.of("runs", runs));
    }

    /**
     * 查看运行结果（分页）
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
     * 比较多次运行结果（按指标并列对比）
     * <p>
     * 对每个 runId 聚合 retrieval/generation 指标均值，并把 summary 从 JSON 字符串解析为对象，
     * 避免返回转义字符串。生成侧指标的 -1 哨兵在 SQL 聚合时已过滤。
     * </p>
     */
    @PostMapping("/compare")
    public ResponseEntity<Map<String, Object>> compareRuns(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Number> runIds = (List<Number>) request.get("runIds");
        if (runIds == null || runIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "runIds is required"));
        }

        Map<String, Object> comparison = new LinkedHashMap<>();
        for (Number runId : runIds) {
            var run = resultRepo.findRunById(runId.longValue());
            if (run.isEmpty()) {
                continue;
            }
            EvaluationRun r = run.get();

            // summary 列存的是 jsonb 字符串，解析为对象避免双重转义
            Object summaryObj;
            if (r.summary() == null || r.summary().isBlank()) {
                summaryObj = null;
            } else {
                try {
                    summaryObj = objectMapper.readValue(r.summary(), Map.class);
                } catch (Exception e) {
                    log.warn("Failed to parse summary for run {}: {}", r.id(), e.getMessage());
                    summaryObj = r.summary(); // 降级为原始字符串
                }
            }

            Map<String, Object> metrics = resultRepo.aggregateMetricsByRunId(r.id());
            comparison.put(r.name(), Map.of(
                    "runId", r.id(),
                    "summary", summaryObj,
                    "metrics", metrics
            ));
        }
        return ResponseEntity.ok(Map.of("comparison", comparison));
    }

    private EvalConfig buildEvalConfig(Map<String, Object> override) {
        EvalConfig config = new EvalConfig();
        config.setVectorTopK(evalProps.getRunner().getDefaultK());
        config.setBm25TopK(evalProps.getRunner().getDefaultK());

        if (override != null) {
            if (override.containsKey("topK")) {
                int topK = ((Number) override.get("topK")).intValue();
                config.setVectorTopK(topK);
                config.setBm25TopK(topK);
                config.setTopK(topK);
            }
            if (override.containsKey("rerankEnabled")) {
                config.setRerankEnabled((Boolean) override.get("rerankEnabled"));
            }
            if (override.containsKey("mmrEnabled")) {
                config.setMmrEnabled((Boolean) override.get("mmrEnabled"));
            }
            if (override.containsKey("queryRewriteEnabled")) {
                config.setQueryRewriteEnabled((Boolean) override.get("queryRewriteEnabled"));
            }
            if (override.containsKey("generationEnabled")) {
                config.setGenerationEnabled((Boolean) override.get("generationEnabled"));
            }
        }

        return config;
    }
}
