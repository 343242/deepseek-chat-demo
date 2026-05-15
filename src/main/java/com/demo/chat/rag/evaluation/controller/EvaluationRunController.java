package com.demo.chat.rag.evaluation.controller;

import com.demo.chat.rag.evaluation.config.EvaluationProperties;
import com.demo.chat.rag.evaluation.dataset.DatasetRepository;
import com.demo.chat.rag.evaluation.dataset.EvaluationDatasetItem;
import com.demo.chat.rag.evaluation.result.EvaluationResultRepository;
import com.demo.chat.rag.evaluation.runner.EvaluationRun;
import com.demo.chat.rag.evaluation.runner.EvaluationRunStatus;
import com.demo.chat.rag.evaluation.runner.EvaluationExecutionService;
import com.demo.chat.rag.evaluation.runner.EvaluationExecutionService.RunSummary;
import com.demo.chat.rag.evaluation.runner.EvaluationRunner.EvalConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 评估运行管理 REST API
 */
@RestController
@RequestMapping("/api/evaluation/runs")
@PreAuthorize("hasAuthority('evaluation:manage')")
public class EvaluationRunController {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunController.class);

    private final EvaluationExecutionService executionService;
    private final EvaluationResultRepository resultRepo;
    private final DatasetRepository datasetRepo;
    private final EvaluationProperties evalProps;

    public EvaluationRunController(EvaluationExecutionService executionService,
                                   EvaluationResultRepository resultRepo,
                                   DatasetRepository datasetRepo,
                                   EvaluationProperties evalProps) {
        this.executionService = executionService;
        this.resultRepo = resultRepo;
        this.datasetRepo = datasetRepo;
        this.evalProps = evalProps;
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
     * 比较多次运行结果
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
            if (run.isPresent()) {
                comparison.put(run.get().name(), Map.of(
                        "runId", runId,
                        "summary", run.get().summary()
                ));
            }
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
