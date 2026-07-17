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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 评估执行服务
 * <p>
 * 封装评估运行的核心编排逻辑，确保事务一致性。
 * Controller 只负责参数解析和响应构建。
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

    public EvaluationExecutionService(EvaluationRunner evaluationRunner,
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
     * 执行评估运行（事务性）
     * <p>
     * 串行执行所有数据项的评估，写入结果，并汇总状态。
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

        for (EvaluationDatasetItem item : items) {
            try {
                var result = evaluationRunner.evaluate(item, config);
                resultRepo.insertResult(new EvaluationResult(
                        null, run.id(), result.itemId(), result.itemQuestionSnapshot(),
                        result.itemGroundTruthSnapshot(), result.itemRelevantChunkIdsSnapshot(),
                        result.queryRewritten(), result.retrievedDocIds(),
                        result.generatedAnswer(), result.stageSnapshots(),
                        result.retrievalMetrics(), result.generationMetrics(),
                        result.error(), result.latencyMs()));
                // evaluate() 内部吞掉所有异常并返回带 error 字段的 result（永不抛出），
                // 因此必须检查 error 区分"评测逻辑失败"与"成功"——否则错误项会被计入 successCount
                if (result.error() != null) {
                    failCount++;
                } else {
                    successCount++;
                    totalLatency += result.latencyMs();
                }
            } catch (Exception e) {
                // 仅持久化失败会走到这里（evaluate 不抛）
                log.error("Failed to persist result for item {}: {}", item.id(), e.getMessage(), e);
                failCount++;
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
     * 运行摘要 record
     */
    public record RunSummary(
            Long runId,
            EvaluationRunStatus status,
            Map<String, Object> summary
    ) {}
}
