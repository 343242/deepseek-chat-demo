package com.smart.rag.rag.evaluation.controller;

import com.smart.rag.rag.evaluation.dataset.DatasetExporter;
import com.smart.rag.rag.evaluation.dataset.DatasetGenerator;
import com.smart.rag.rag.evaluation.dataset.DatasetRepository;
import com.smart.rag.rag.evaluation.dataset.EvaluationDataset;
import com.smart.rag.rag.evaluation.dataset.EvaluationDatasetItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 评估数据集管理 REST API
 * <p>
 * 仅在 evaluation profile 激活时可用。
 * </p>
 */
@RestController
@Profile("evaluation")
@RequestMapping("/api/evaluation/datasets")
@PreAuthorize("hasAuthority('evaluation:manage')")
public class DatasetController {

    private static final Logger log = LoggerFactory.getLogger(DatasetController.class);

    private final DatasetRepository datasetRepo;
    private final DatasetGenerator datasetGenerator;
    private final DatasetExporter datasetExporter;

    public DatasetController(DatasetRepository datasetRepo,
                             DatasetGenerator datasetGenerator,
                             DatasetExporter datasetExporter) {
        this.datasetRepo = datasetRepo;
        this.datasetGenerator = datasetGenerator;
        this.datasetExporter = datasetExporter;
    }

    /**
     * LLM 自动生成数据集
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateDataset(
            @RequestBody Map<String, Object> request) {
        String name = (String) request.getOrDefault("name", "dataset-" + System.currentTimeMillis());
        Long userId = request.get("userId") != null
                ? ((Number) request.get("userId")).longValue()
                : null;

        if (userId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "userId is required"));
        }

        EvaluationDataset dataset = datasetGenerator.generate(name, userId);
        return ResponseEntity.ok(Map.of(
                "id", dataset.id(),
                "name", dataset.name(),
                "itemCount", dataset.itemCount(),
                "status", "generated"
        ));
    }

    /**
     * 列出数据集（分页）
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listDatasets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<EvaluationDataset> datasets = datasetRepo.listDatasets(page, size);
        int total = datasetRepo.countDatasets();
        return ResponseEntity.ok(Map.of(
                "datasets", datasets,
                "total", total,
                "page", page,
                "size", size
        ));
    }

    /**
     * 数据集详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getDataset(@PathVariable long id) {
        return datasetRepo.findDatasetById(id)
                .<ResponseEntity<?>>map(ds -> {
                    ds = new EvaluationDataset(
                            ds.id(), ds.name(), ds.description(), ds.version(),
                            ds.source(), ds.judgeModel(), ds.itemCount(),
                            ds.createdAt(), ds.updatedAt(),
                            datasetRepo.listItemsByDatasetId(id));
                    return ResponseEntity.ok(ds);
                })
                .orElse(ResponseEntity.status(404)
                        .body(Map.of("error", "Dataset not found: " + id)));
    }

    /**
     * 更新单条数据项（审核修正）
     */
    @PostMapping("/{datasetId}/items/{itemId}")
    public ResponseEntity<?> updateItem(
            @PathVariable long datasetId,
            @PathVariable long itemId,
            @RequestBody EvaluationDatasetItem update) {
        var existing = datasetRepo.findItemById(itemId);
        if (existing.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Item not found: " + itemId));
        }

        EvaluationDatasetItem item = existing.get();
        EvaluationDatasetItem updated = new EvaluationDatasetItem(
                item.id(),
                item.datasetId(),
                update.question() != null ? update.question() : item.question(),
                update.groundTruthAnswer() != null ? update.groundTruthAnswer() : item.groundTruthAnswer(),
                update.relevantChunkIds() != null ? update.relevantChunkIds() : item.relevantChunkIds(),
                update.relevantContent() != null ? update.relevantContent() : item.relevantContent(),
                update.tags() != null ? update.tags() : item.tags(),
                update.status() != null ? update.status() : item.status(),
                item.seq()
        );

        datasetRepo.updateItem(updated);
        return ResponseEntity.ok(Map.of("status", "updated"));
    }

    /**
     * 导出为 JSON（人工审核用）
     */
    @GetMapping(value = "/{id}/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> exportDataset(@PathVariable long id) {
        String json = datasetExporter.exportAsJson(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }
}
