package com.smart.rag.evaluation.runner;

import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.evaluation.dataset.DatasetRepository;
import com.smart.rag.evaluation.dataset.EvaluationDatasetItem;
import com.smart.rag.evaluation.result.EvaluationResult;
import com.smart.rag.evaluation.result.EvaluationResultRepository;
import com.smart.rag.evaluation.runner.EvaluationRunner.EvalConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 评估执行服务
 * <p>
 * 封装评估运行的核心编排逻辑。{@link #submitRun} 以 fire-and-forget 方式在虚拟线程上异步执行，
 * 进度通过 {@link EvaluationProgressSink} 推送到 SSE 订阅者；{@link #executeRun} 保留同步语义供复用/单测。
 * </p>
 */
@Service
@Profile("evaluation")
public class EvaluationExecutionService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationExecutionService.class);

    private final EvaluationRunner evaluationRunner;
    private final EvaluationResultRepository resultRepo;
    private final DatasetRepository datasetRepo;
    private final EvaluationProperties evalProps;
    private final ObjectMapper objectMapper;
    private final ExecutorService evalExecutor;
    private final Semaphore evalRunSemaphore;
    private final EvaluationProgressSink progressSink;

    public EvaluationExecutionService(EvaluationRunner evaluationRunner,
                                      EvaluationResultRepository resultRepo,
                                      DatasetRepository datasetRepo,
                                      EvaluationProperties evalProps,
                                      ObjectMapper objectMapper,
                                      @Qualifier("evalExecutor") ExecutorService evalExecutor,
                                      @Qualifier("evalRunSemaphore") Semaphore evalRunSemaphore,
                                      EvaluationProgressSink progressSink) {
        this.evaluationRunner = evaluationRunner;
        this.resultRepo = resultRepo;
        this.datasetRepo = datasetRepo;
        this.evalProps = evalProps;
        this.objectMapper = objectMapper;
        this.evalExecutor = evalExecutor;
        this.evalRunSemaphore = evalRunSemaphore;
        this.progressSink = progressSink;
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
     *
     * @param run     已创建并标记为 running 的运行记录
     * @param items   数据项列表
     * @param config  评估配置
     */
    public void submitRun(EvaluationRun run, List<EvaluationDatasetItem> items, EvalConfig config) {
        // 预创建 sink，确保 SSE 订阅（可能稍晚到达）能拿到所有进度
        progressSink.getOrCreate(run.id());

        evalExecutor.submit(() -> {
            long runId = run.id();
            long timeoutSeconds = evalProps.getRunner().getTimeoutSeconds();
            try {
                if (!evalRunSemaphore.tryAcquire(timeoutSeconds, TimeUnit.SECONDS)) {
                    log.warn("Run {} rejected: concurrency limit (acquire timeout {}s)", runId, timeoutSeconds);
                    resultRepo.updateRunStatus(runId, EvaluationRunStatus.FAILED,
                            "{\"error\":\"concurrency limit exceeded, try again later\"}");
                    return;
                }
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
                evalRunSemaphore.release();
                progressSink.complete(runId);
            }
        });
    }

    /**
     * 执行评估运行（同步）。
     * <p>
     * 串行执行所有数据项的评估，写入结果，每完成一项推送进度事件，最后汇总状态。
     * 通常由 {@link #submitRun} 在虚拟线程上调用；保留 public 便于复用或单测。
     * </p>
     *
     * @param run     运行记录
     * @param items   数据项列表
     * @param config  评估配置
     * @return 运行摘要
     */
    public RunSummary executeRun(EvaluationRun run, List<EvaluationDatasetItem> items, EvalConfig config) {
        int successCount = 0;
        int failCount = 0;
        long totalLatency = 0;
        int total = items.size();

        for (int idx = 0; idx < items.size(); idx++) {
            EvaluationDatasetItem item = items.get(idx);
            long itemStart = System.currentTimeMillis();
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
                int processed = idx + 1;
                // evaluate() 内部吞掉所有异常并返回带 error 字段的 result（永不抛出），
                // 因此必须检查 error 区分"评测逻辑失败"与"成功"——否则错误项会被计入 successCount
                if (result.error() != null) {
                    failCount++;
                    progressSink.emit(run.id(), EvaluationProgressEvent.failed(
                            run.id(), processed, total, successCount, failCount,
                            item.id() != null ? item.id() : 0L, result.error(), elapsed));
                } else {
                    successCount++;
                    totalLatency += result.latencyMs();
                    progressSink.emit(run.id(), EvaluationProgressEvent.success(
                            run.id(), processed, total, successCount, failCount,
                            item.id() != null ? item.id() : 0L, elapsed));
                }
            } catch (Exception e) {
                // 仅持久化失败会走到这里（evaluate 不抛）
                long elapsed = System.currentTimeMillis() - itemStart;
                log.error("Failed to persist result for item {}: {}", item.id(), e.getMessage(), e);
                failCount++;
                progressSink.emit(run.id(), EvaluationProgressEvent.failed(
                        run.id(), idx + 1, total, successCount, failCount,
                        item.id() != null ? item.id() : 0L, e.getMessage(), elapsed));
            }
        }

        // 汇总并更新状态
        EvaluationRunStatus status = failCount == 0
                ? EvaluationRunStatus.COMPLETED
                : (successCount > 0 ? EvaluationRunStatus.COMPLETED : EvaluationRunStatus.FAILED);

        Map<String, Object> summary = Map.of(
                "totalItems", items.size(),
                "successCount", successCount,
                "failCount", failCount,
                "avgLatencyMs", successCount > 0 ? totalLatency / successCount : 0
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
