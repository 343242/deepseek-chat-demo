package com.demo.chat.rag.evaluation.controller;

import com.demo.chat.rag.evaluation.dataset.DatasetExporter;
import com.demo.chat.rag.evaluation.dataset.DatasetGenerator;
import com.demo.chat.rag.evaluation.dataset.DatasetRepository;
import com.demo.chat.rag.evaluation.dataset.EvaluationDataset;
import com.demo.chat.rag.evaluation.dataset.EvaluationDatasetItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 评估数据集管理 REST API
 * <p>
 * 仅在 evaluation profile 激活时可用。
 * </p>
 */
@RestController
@RequestMapping("/api/evaluation/datasets")
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
                "id", dataset.getId(),
                "name", dataset.getName(),
                "itemCount", dataset.getItemCount(),
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
                .<ResponseEntity<?>>map(dataset -> {
                    var items = datasetRepo.listItemsByDatasetId(id);
                    dataset.setItems(items);
                    return ResponseEntity.ok(dataset);
                })
                .orElse(ResponseEntity.status(404)
                        .body(Map.of("error", "Dataset not found: " + id)));
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

    /**
     * 更新单条数据项（审核修正）
     */
    @PutMapping("/{datasetId}/items/{itemId}")
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
        if (update.getQuestion() != null) item.setQuestion(update.getQuestion());
        if (update.getGroundTruthAnswer() != null) item.setGroundTruthAnswer(update.getGroundTruthAnswer());
        if (update.getRelevantChunkIds() != null) item.setRelevantChunkIds(update.getRelevantChunkIds());
        if (update.getRelevantContent() != null) item.setRelevantContent(update.getRelevantContent());
        if (update.getTags() != null) item.setTags(update.getTags());
        if (update.getStatus() != null) item.setStatus(update.getStatus());

        datasetRepo.updateItem(item);
        return ResponseEntity.ok(Map.of("status", "updated"));
    }
}
