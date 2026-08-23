package com.smart.rag.evaluation.controller;

import com.smart.rag.evaluation.dataset.DatasetExporter;
import com.smart.rag.evaluation.dataset.DatasetRepository;
import com.smart.rag.evaluation.dataset.EvaluationDataset;
import com.smart.rag.evaluation.dataset.EvaluationDatasetItem;
import com.smart.rag.evaluation.testset.GenerationJobRecord;
import com.smart.rag.evaluation.testset.GenerationJobService;
import com.smart.rag.evaluation.testset.GenerationProgressSink;
import com.smart.rag.evaluation.testset.GenerationSseBridge;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 评估数据集管理 REST API（评估模块全局恒装载，权限由 evaluation:manage 控制）。
 * <p>
 * generate 为 KG 式多跳生成（ragas 翻译），202 异步 + SSE 进度。
 * </p>
 */
@RestController
@RequestMapping("/api/evaluation/datasets")
@PreAuthorize("hasAuthority('evaluation:manage')")
public class DatasetController {

    private static final Logger log = LoggerFactory.getLogger(DatasetController.class);

    /** 分页 size 上限（与 EvaluationRunController 对齐） */
    private static final int MAX_PAGE_SIZE = 500;

    private final DatasetRepository datasetRepo;
    private final GenerationJobService generationJobService;
    private final GenerationProgressSink generationProgressSink;
    private final GenerationSseBridge generationSseBridge;
    private final DatasetExporter datasetExporter;

    public DatasetController(DatasetRepository datasetRepo,
                             GenerationJobService generationJobService,
                             GenerationProgressSink generationProgressSink,
                             GenerationSseBridge generationSseBridge,
                             DatasetExporter datasetExporter) {
        this.datasetRepo = datasetRepo;
        this.generationJobService = generationJobService;
        this.generationProgressSink = generationProgressSink;
        this.generationSseBridge = generationSseBridge;
        this.datasetExporter = datasetExporter;
    }

    /**
     * 提交 KG 式测试集生成任务（异步，返回 202 + jobId）。
     * <p>
     * 原始 Map 手工做类型校验：错误类型直接抛 {@link ClientException}（400），
     * 而非裸强转的 ClassCastException（500）。
     * </p>
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateDataset(
            @RequestBody Map<String, Object> request) {
        Object rawName = request.get("name");
        String name = rawName instanceof String s && !s.isBlank()
                ? s
                : "dataset-" + System.currentTimeMillis();
        Object rawUserId = request.get("userId");
        if (!(rawUserId instanceof Number n)) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST,
                    "userId 必须提供且为数字" + (rawUserId == null ? "" : "（收到 " + rawUserId.getClass().getSimpleName() + "）"));
        }
        long userId = n.longValue();

        long jobId = generationJobService.submit(name, userId);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "pending"));
    }

    /**
     * 生成任务状态查询
     */
    @GetMapping("/generate/{jobId}")
    public ResponseEntity<GenerationJobRecord> getGenerationJob(@PathVariable long jobId) {
        return ResponseEntity.ok(generationJobService.getJob(jobId));
    }

    /**
     * 生成任务 SSE 进度流。已结束的任务（completed/failed）不订阅 sink——
     * sink 已 complete 并移除，订阅会挂到超时；改为直接回放终态收尾。
     * 活任务的 sink 由 submit 预创建，此处只订阅不创建：sink 缺失（刚结束/重启后残留
     * running 态）时流为空立即收尾，客户端转 GET /generate/{jobId} 查终态。
     */
    @GetMapping(value = "/generate/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generationEvents(@PathVariable long jobId) {
        GenerationJobRecord job = generationJobService.getJob(jobId); // 404 语义：任务不存在直接报错
        if ("completed".equals(job.status()) || "failed".equals(job.status())) {
            return generationSseBridge.bridgeTerminated(job);
        }
        return generationSseBridge.bridge(jobId, generationProgressSink);
    }

    /**
     * 列出数据集（分页，page ≥ 0，size 1..500；与 EvaluationRunController 的钳制策略一致）
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listDatasets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        List<EvaluationDataset> datasets = datasetRepo.listDatasets(safePage, safeSize);
        int total = datasetRepo.countDatasets();
        return ResponseEntity.ok(Map.of(
                "datasets", datasets,
                "total", total,
                "page", safePage,
                "size", safeSize
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
        // 归属校验：datasetId 路径参数不是装饰——防止跨数据集改写他集 item
        if (existing.get().datasetId() == null || existing.get().datasetId() != datasetId) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Item " + itemId + " does not belong to dataset " + datasetId));
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
