package com.smart.rag.evaluation.controller;

import com.smart.rag.evaluation.dataset.DatasetRepository;
import com.smart.rag.evaluation.dataset.EvaluationDatasetItem;
import com.smart.rag.evaluation.result.EvaluationResultRepository;
import com.smart.rag.evaluation.runner.EvaluationRun;
import com.smart.rag.evaluation.runner.EvaluationRunStatus;
import com.smart.rag.evaluation.runner.EvaluationExecutionService;
import com.smart.rag.evaluation.runner.EvaluationProgressSink;
import com.smart.rag.evaluation.runner.EvaluationSseBridge;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/**
 * 评估运行管理 REST API
 */
@RestController
@RequestMapping("/api/evaluation/runs")
@PreAuthorize("hasAuthority('evaluation:manage')")
public class EvaluationRunController {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunController.class);

    /** compare 端点单次对比的 run 数上限（防一次性聚合过多 SQL） */
    private static final int MAX_COMPARE_RUNS = 10;
    /** 分页 size 上限 */
    private static final int MAX_PAGE_SIZE = 500;

    private final EvaluationExecutionService executionService;
    private final EvaluationResultRepository resultRepo;
    private final DatasetRepository datasetRepo;
    private final ObjectMapper objectMapper;
    private final EvaluationProgressSink progressSink;
    private final EvaluationSseBridge sseBridge;

    public EvaluationRunController(EvaluationExecutionService executionService,
                                   EvaluationResultRepository resultRepo,
                                   DatasetRepository datasetRepo,
                                   ObjectMapper objectMapper,
                                   EvaluationProgressSink progressSink,
                                   EvaluationSseBridge sseBridge) {
        this.executionService = executionService;
        this.resultRepo = resultRepo;
        this.datasetRepo = datasetRepo;
        this.objectMapper = objectMapper;
        this.progressSink = progressSink;
        this.sseBridge = sseBridge;
    }

    /**
     * 启动评估运行（异步）。
     * <p>
     * 所有前置校验（数据集存在、配置类型、数据项非空）在落库前完成——run 记录一旦标记
     * running 就必须有执行方接管，校验失败时直接 400，不留无人执行的 running 记录
     * （那种记录只能等 sweeper 超时回收）。校验通过后创建 run 记录并 fire-and-forget 提交，
     * 实际评测在虚拟线程上执行。客户端可通过 {@code GET /{runId}/events} 订阅 SSE 进度，或轮询 run 状态。
     * </p>
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> startRun(@RequestBody StartRunRequest request) {
        if (request.datasetId() == null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "datasetId 不能为空");
        }

        // 验证数据集存在
        var dataset = datasetRepo.findDatasetById(request.datasetId());
        if (dataset.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Dataset not found: " + request.datasetId()));
        }

        // 前置校验（可能抛 ClientException → 400）：配置类型检查在 Service 层
        var config = executionService.buildEvalConfig(request.configOverride());

        // 数据项加载：空数据集拒绝启动，避免 total=0 的空跑
        List<EvaluationDatasetItem> items = datasetRepo.listItemsByDatasetId(request.datasetId());
        if (items.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Dataset has no items: " + request.datasetId()));
        }

        String name = request.name() != null && !request.name().isBlank()
                ? request.name()
                : "run-" + System.currentTimeMillis();

        // 校验全部通过，创建运行记录（同步，标记 running）并异步提交（fire-and-forget）
        EvaluationRun run = executionService.createRun(request.datasetId(), name, request.configOverride());
        executionService.submitRun(run, items, config);

        return ResponseEntity.accepted().body(Map.of(
                "runId", run.id(),
                "status", run.status().getValue(),
                "message", "Evaluation submitted; subscribe GET /api/evaluation/runs/" + run.id() + "/events for progress"
        ));
    }

    /**
     * 订阅某次运行的 SSE 进度流。
     * <p>
     * 推送 per-item 进度事件（{@code event: progress}），结束时发 {@code event: done}。
     * 即使订阅晚于任务启动，也能收到最近 20 条历史进度（replay）。
     * run 不存在返回 404；已结束（completed/failed）直接回放终态收尾——
     * sink 已随任务结束移除，subscribe 只会得到立即完成的空流，终态直连能携带完整状态信息。
     * </p>
     */
    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamProgress(@PathVariable long runId) {
        var run = resultRepo.findRunById(runId);
        if (run.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (run.get().status() != EvaluationRunStatus.RUNNING) {
            return ResponseEntity.ok(sseBridge.bridgeTerminated(run.get()));
        }
        return ResponseEntity.ok(sseBridge.bridge(progressSink.subscribe(runId)));
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
     * 查看运行结果（分页，page ≥ 0，size 1..500）
     */
    @GetMapping("/{runId}/results")
    public ResponseEntity<Map<String, Object>> listResults(
            @PathVariable long runId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        List<Map<String, Object>> results = resultRepo.listResultsByRunId(runId, safePage, safeSize);
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
    public ResponseEntity<Map<String, Object>> compareRuns(@RequestBody CompareRunsRequest request) {
        List<Long> runIds = request.runIds();
        if (runIds == null || runIds.isEmpty()) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "runIds 不能为空");
        }
        if (runIds.size() > MAX_COMPARE_RUNS) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST,
                    "单次最多对比 " + MAX_COMPARE_RUNS + " 个运行");
        }

        Map<String, Object> comparison = new LinkedHashMap<>();
        for (Long runId : runIds) {
            var run = resultRepo.findRunById(runId);
            if (run.isEmpty()) {
                continue;
            }
            EvaluationRun r = run.get();
            // runId 作 key：name 是用户可控显示字段、不唯一，同名 run 互相覆盖会静默丢列
            // （前端 compare-tab 按 Object.values + entry.runId 反查，不依赖 key 语义）
            comparison.put(String.valueOf(r.id()), buildComparisonEntry(r));
        }
        return ResponseEntity.ok(Map.of("comparison", comparison));
    }

    /**
     * 构建单个 run 的对比条目。summary 可能为 null（run 未结束），因此用 HashMap 而非 Map.of——
     * Map.of 不允许 null value，否则未结束的 run 会把整个 /compare 打成 500。
     */
    private Map<String, Object> buildComparisonEntry(EvaluationRun r) {
        Object summaryObj = null;
        if (r.summary() != null && !r.summary().isBlank()) {
            // summary 列存的是 jsonb 字符串，解析为对象避免双重转义
            try {
                summaryObj = objectMapper.readValue(r.summary(), Map.class);
            } catch (Exception e) {
                log.warn("Failed to parse summary for run {}", r.id(), e);
                summaryObj = r.summary(); // 降级为原始字符串
            }
        }
        Map<String, Object> entry = new HashMap<>();
        entry.put("runId", r.id());
        entry.put("summary", summaryObj);
        entry.put("metrics", resultRepo.aggregateMetricsByRunId(r.id()));
        return entry;
    }

    /** startRun 请求体 */
    public record StartRunRequest(Long datasetId, String name, Map<String, Object> configOverride) {
    }

    /** compareRuns 请求体 */
    public record CompareRunsRequest(List<Long> runIds) {
    }
}
