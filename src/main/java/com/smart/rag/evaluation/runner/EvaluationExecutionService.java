package com.smart.rag.evaluation.runner;

import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.evaluation.dataset.DatasetRepository;
import com.smart.rag.evaluation.dataset.EvaluationDatasetItem;
import com.smart.rag.evaluation.result.EvaluationResult;
import com.smart.rag.evaluation.result.EvaluationResultRepository;
import com.smart.rag.evaluation.runner.EvaluationRunner.EvalConfig;
import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 评估执行服务
 * <p>
 * 封装评估运行的核心编排逻辑。{@link #submitRun} 以 fire-and-forget 方式在虚拟线程上异步执行，
 * 进度通过 {@link EvaluationProgressSink} 推送到 SSE 订阅者；{@link #executeRun} 保留同步语义供复用/单测。
 * </p>
 */
@Service
public class EvaluationExecutionService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationExecutionService.class);

    /** configOverride.topK 上限（下限 1；检索侧无更大硬限制，此为防滥用护栏） */
    private static final int MAX_TOPK = 100;

    private final EvaluationRunner evaluationRunner;
    private final EvaluationResultRepository resultRepo;
    private final DatasetRepository datasetRepo;
    private final EvaluationProperties evalProps;
    private final ObjectMapper objectMapper;
    private final ExecutorService evalExecutor;
    private final Semaphore evalRunSemaphore;
    private final EvaluationProgressSink progressSink;
    private final ScopedTasks scopedTasks;

    public EvaluationExecutionService(EvaluationRunner evaluationRunner,
                                      EvaluationResultRepository resultRepo,
                                      DatasetRepository datasetRepo,
                                      EvaluationProperties evalProps,
                                      ObjectMapper objectMapper,
                                      @Qualifier("evalExecutor") ExecutorService evalExecutor,
                                      @Qualifier("evalRunSemaphore") Semaphore evalRunSemaphore,
                                      EvaluationProgressSink progressSink,
                                      ScopedTasks scopedTasks) {
        this.evaluationRunner = evaluationRunner;
        this.resultRepo = resultRepo;
        this.datasetRepo = datasetRepo;
        this.evalProps = evalProps;
        this.objectMapper = objectMapper;
        this.evalExecutor = evalExecutor;
        this.evalRunSemaphore = evalRunSemaphore;
        this.progressSink = progressSink;
        this.scopedTasks = scopedTasks;
    }

    /**
     * 创建评估运行记录
     */
    public EvaluationRun createRun(Long datasetId, String name, Map<String, Object> configOverride) {
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
        return run;
    }

    /**
     * 异步提交评估运行（fire-and-forget）。
     * <p>
     * 立即在虚拟线程上排队执行，不阻塞调用方（HTTP 线程）。执行流程：
     * <ol>
     *   <li>尝试获取信号量（背压，超时 {@code app.evaluation.runner.timeout-seconds}）——
     *       超时标记 run 为 FAILED 并完成 sink</li>
     *   <li>调用 {@link #executeRun} 执行实际评测</li>
     *   <li>异常时标记 run 为 FAILED，finally 释放信号量 + 完成 sink</li>
     * </ol>
     * 进度通过 {@link EvaluationProgressSink} 推送到 SSE 订阅者。
     * executor 拒绝（已关闭/饱和）时 {@code execute} 同步抛出——补偿标记 FAILED、完成 sink
     * 并抛 {@link ServiceException}，不留 running 孤儿（镜像 {@code GenerationJobService.submit}）。
     *
     * @param run     已创建并标记为 running 的运行记录
     * @param items   数据项列表
     * @param config  评估配置
     */
    public void submitRun(EvaluationRun run, List<EvaluationDatasetItem> items, EvalConfig config) {
        // 预创建 sink，确保 SSE 订阅（可能稍晚到达）能拿到所有进度
        progressSink.getOrCreate(run.id());

        try {
            evalExecutor.execute(() -> {
                long runId = run.id();
                long acquireTimeoutSeconds = evalProps.getRunner().getAcquireTimeoutSeconds();
                // 只有成功 acquire 才置位，finally 按位释放——否则超时拒绝路径会 release 未获取的许可，
                // 逐步击穿 max-concurrent-runs 背压
                boolean acquired = false;
                try {
                    if (!evalRunSemaphore.tryAcquire(acquireTimeoutSeconds, TimeUnit.SECONDS)) {
                        log.warn("Run {} rejected: concurrency limit (acquire timeout {}s)", runId, acquireTimeoutSeconds);
                        resultRepo.updateRunStatus(runId, EvaluationRunStatus.FAILED,
                                "{\"error\":\"concurrency limit exceeded, try again later\"}");
                        return;
                    }
                    acquired = true;
                    executeRun(run, items, config);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Run {} interrupted while acquiring semaphore", runId);
                    resultRepo.updateRunStatus(runId, EvaluationRunStatus.FAILED,
                            "{\"error\":\"interrupted\"}");
                } catch (Exception e) {
                    log.error("Run {} failed unexpectedly: {}", runId, e.getMessage(), e);
                    resultRepo.updateRunStatus(runId, EvaluationRunStatus.FAILED,
                            "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                } finally {
                    if (acquired) {
                        evalRunSemaphore.release();
                    }
                    progressSink.complete(runId);
                }
            });
        } catch (RejectedExecutionException e) {
            // run 此时已是 running 且 sink 已预创建：不补偿则记录无人接管、sink 残留
            // （sink 存活还会让 sweeper 误判"仍在执行"而不回收）
            log.error("Run {} submit rejected by executor: {}", run.id(), e.toString());
            resultRepo.updateRunStatus(run.id(), EvaluationRunStatus.FAILED,
                    "{\"error\":\"executor rejected submission\"}");
            progressSink.complete(run.id());
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR,
                    "评估任务提交失败：评估执行器不可用", e);
        }
    }

    /**
     * 执行评估运行（同步，可并发）。
     * <p>
     * 用 {@link ScopedTasks} fork 多个 item，并发度由 {@code app.evaluation.runner.concurrency} 控制
     * （默认 1=串行）。每个 item 完成后立即持久化并推送进度事件（计数用原子变量保证线程安全）。
     * 通常由 {@link #submitRun} 在虚拟线程上调用；保留 public 便于复用或单测。
     * </p>
     *
     * @param run     运行记录
     * @param items   数据项列表
     * @param config  评估配置
     * @return 运行摘要
     */
    public RunSummary executeRun(EvaluationRun run, List<EvaluationDatasetItem> items, EvalConfig config) {
        int total = items.size();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger processed = new AtomicInteger(0);
        AtomicLong totalLatency = new AtomicLong(0);

        int concurrency = Math.max(1, evalProps.getRunner().getConcurrency());
        ScopeOptions options = ScopeOptions.builder("eval-run-" + run.id())
                .policy(ScopePolicy.COLLECT_ALL)  // 单 item 失败不影响其它
                .maxConcurrency(concurrency)
                .defaultTimeout(Duration.ofSeconds(evalProps.getRunner().getItemTimeoutSeconds()))
                .build();

        try (TaskScope scope = scopedTasks.open("eval-run-" + run.id(), options)) {
            for (EvaluationDatasetItem item : items) {
                long itemStart = System.currentTimeMillis();
                scope.fork("item-" + item.id(), () -> {
                    try {
                        var result = evaluationRunner.evaluate(item, config);
                        resultRepo.insertResult(new EvaluationResult(
                                null, run.id(), result.itemId(), result.itemQuestionSnapshot(),
                                result.itemGroundTruthSnapshot(), result.itemRelevantChunkIdsSnapshot(),
                                result.queryRewritten(), result.retrievedDocIds(),
                                result.generatedAnswer(), result.stageSnapshots(),
                                result.retrievalMetrics(), result.generationMetrics(),
                                result.error(), result.latencyMs()));
                        long elapsed = System.currentTimeMillis() - itemStart;
                        int done = processed.incrementAndGet();
                        // evaluate() 内部吞掉所有异常并返回带 error 字段的 result（永不抛出），
                        // 因此必须检查 error 区分"评测逻辑失败"与"成功"——否则错误项会被计入 successCount
                        if (result.error() != null) {
                            int fc = failCount.incrementAndGet();
                            progressSink.emit(run.id(), EvaluationProgressEvent.failed(
                                    run.id(), done, total, successCount.get(), fc,
                                    item.id() != null ? item.id() : 0L, result.error(), elapsed));
                        } else {
                            int sc = successCount.incrementAndGet();
                            totalLatency.addAndGet(result.latencyMs());
                            progressSink.emit(run.id(), EvaluationProgressEvent.success(
                                    run.id(), done, total, sc, failCount.get(),
                                    item.id() != null ? item.id() : 0L, elapsed));
                        }
                    } catch (Exception e) {
                        // 仅持久化失败会走到这里（evaluate 不抛）
                        long elapsed = System.currentTimeMillis() - itemStart;
                        log.error("Failed to evaluate/persist item {}: {}", item.id(), e.getMessage(), e);
                        int done = processed.incrementAndGet();
                        int fc = failCount.incrementAndGet();
                        progressSink.emit(run.id(), EvaluationProgressEvent.failed(
                                run.id(), done, total, successCount.get(), fc,
                                item.id() != null ? item.id() : 0L, e.getMessage(), elapsed));
                    }
                    return null;
                });
            }
            scope.join();
        }

        // 汇总并更新状态（join 后所有 fork 已完成，安全读 final 值）
        int sc = successCount.get();
        int fc = failCount.get();
        long lat = totalLatency.get();

        EvaluationRunStatus status = fc == 0
                ? EvaluationRunStatus.COMPLETED
                : (sc > 0 ? EvaluationRunStatus.COMPLETED : EvaluationRunStatus.FAILED);

        Map<String, Object> summary = Map.of(
                "totalItems", total,
                "successCount", sc,
                "failCount", fc,
                "avgLatencyMs", sc > 0 ? lat / sc : 0
        );

        String summaryJson;
        try {
            summaryJson = objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            summaryJson = "{}";
        }

        resultRepo.updateRunStatus(run.id(), status, summaryJson);

        return new RunSummary(run.id(), status, summary);
    }

    /**
     * 根据请求覆盖项构建评估配置。
     * <p>
     * 类型校验集中在此处：JSON 反序列化后 configOverride 是弱类型 Map，类型不符抛
     * {@link ClientException}（HTTP 400），而非裸强转的 ClassCastException（500）。
     * </p>
     */
    public EvalConfig buildEvalConfig(Map<String, Object> override) {
        EvalConfig config = new EvalConfig();
        config.setVectorTopK(evalProps.getRunner().getDefaultK());
        config.setBm25TopK(evalProps.getRunner().getDefaultK());

        if (override == null) {
            return config;
        }
        if (override.containsKey("topK")) {
            int topK = requireInt(override.get("topK"), "topK");
            if (topK < 1 || topK > MAX_TOPK) {
                throw new ClientException(ClientErrorCode.BAD_REQUEST,
                        "topK 必须在 1 到 " + MAX_TOPK + " 之间");
            }
            config.setVectorTopK(topK);
            config.setBm25TopK(topK);
            config.setTopK(topK);
        }
        config.setRerankEnabled(requireBool(override, "rerankEnabled"));
        config.setMmrEnabled(requireBool(override, "mmrEnabled"));
        config.setQueryRewriteEnabled(requireBool(override, "queryRewriteEnabled"));
        config.setGenerationEnabled(requireBool(override, "generationEnabled"));
        config.setParentChildEnabled(requireBool(override, "parentChildEnabled"));
        if (override.containsKey("testUserId")) {
            Object v = override.get("testUserId");
            if (!(v instanceof Number n)) {
                throw new ClientException(ClientErrorCode.BAD_REQUEST, "testUserId 必须是数字");
            }
            config.setTestUserId(n.longValue());
        }
        return config;
    }

    private static int requireInt(Object value, String field) {
        if (!(value instanceof Number n)) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, field + " 必须是数字");
        }
        return n.intValue();
    }

    private static boolean requireBool(Map<String, Object> override, String field) {
        if (!override.containsKey(field)) {
            return true; // 未覆盖时保持默认开启
        }
        Object v = override.get(field);
        if (!(v instanceof Boolean b)) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, field + " 必须是布尔值");
        }
        return b;
    }

    /**
     * 最小化 JSON 字符串转义，用于把异常消息嵌入 summary jsonb。
     * 仅处理会破坏 JSON 结构的字符；不追求完整性，因为这只是错误信息展示用。
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * 运行摘要 record
     */
    public record RunSummary(
            Long runId,
            EvaluationRunStatus status,
            Map<String, Object> summary
    ) {}
}
