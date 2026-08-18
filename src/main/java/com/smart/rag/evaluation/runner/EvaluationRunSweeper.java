package com.smart.rag.evaluation.runner;

import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.evaluation.result.EvaluationResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 卡死运行清理器。
 * <p>
 * 定期扫描停留在 {@code running} 状态超过阈值的 run 记录，标记为 {@code FAILED}，
 * 应对 JVM 崩溃 / kill -9 / 异常退出导致的 stuck-running 记录（这些 run 永远不会自然结束）。
 *
 * <h3>判定依据</h3>
 * 用 {@code started_at} 字段判断（{@code evaluation_run} 表无 {@code updated_at} 列）：
 * {@code status='running' AND started_at < NOW() - staleRunMinutes}。
 * 超时阈值由 {@code app.evaluation.runner.stale-run-minutes} 配置，默认 30 分钟。
 *
 * <h3>对齐项目惯例</h3>
 * 复刻 {@code OrphanChunkCleaner} / {@code AgentEventCleanupTask} 的模式：
 * <ul>
 *   <li>{@code @Component} + {@code @Profile("evaluation")}（只在评测 profile 装载）</li>
 *   <li>{@code @Scheduled(fixedRate, initialDelay)}（{@code @EnableScheduling} 已在
 *       {@code AdvisorAutoConfiguration} 全局开启）</li>
 *   <li>无 {@code @Transactional}（单条 UPDATE 自动提交，符合项目惯例）</li>
 *   <li>异常吞掉只记日志，不毒化调度器</li>
 * </ul>
 *
 * <p>顺带清理 {@link EvaluationProgressSink} 中残留的 sink entry（JVM 崩溃后 sink 无人 complete）。
 */
@Component
@Profile("evaluation")
public class EvaluationRunSweeper {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunSweeper.class);

    private final EvaluationResultRepository resultRepo;
    private final EvaluationProgressSink progressSink;
    private final com.smart.rag.evaluation.testset.GenerationJobRepository genJobRepo;
    private final com.smart.rag.evaluation.testset.GenerationProgressSink genProgressSink;
    private final EvaluationProperties evalProps;

    public EvaluationRunSweeper(EvaluationResultRepository resultRepo,
                                EvaluationProgressSink progressSink,
                                com.smart.rag.evaluation.testset.GenerationJobRepository genJobRepo,
                                com.smart.rag.evaluation.testset.GenerationProgressSink genProgressSink,
                                EvaluationProperties evalProps) {
        this.resultRepo = resultRepo;
        this.progressSink = progressSink;
        this.genJobRepo = genJobRepo;
        this.genProgressSink = genProgressSink;
        this.evalProps = evalProps;
    }

    /**
     * 每 5 分钟扫描一次，启动 1 分钟后首跑（避免与应用启动争资源）。
     */
    @Scheduled(fixedRate = 5 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void reapStaleRuns() {
        try {
            int staleMinutes = evalProps.getRunner().getStaleRunMinutes();
            Instant cutoff = Instant.now().minusSeconds(staleMinutes * 60L);

            List<EvaluationRun> running = resultRepo.listRunsByStatus(EvaluationRunStatus.RUNNING);
            int reaped = 0;
            for (EvaluationRun run : running) {
                OffsetDateTime startedAt = run.startedAt();
                if (startedAt == null || !startedAt.toInstant().isBefore(cutoff)) {
                    continue;
                }
                // updateRunStatus 在 status=failed 时会自动设置 completed_at = NOW()
                resultRepo.updateRunStatus(run.id(), EvaluationRunStatus.FAILED,
                        "{\"error\":\"marked stale by sweeper (running over " + staleMinutes + " minutes)\"}");
                // 清理残留的 sink entry（JVM 崩溃前未走 finally 的 complete）
                progressSink.complete(run.id());
                reaped++;
                log.warn("Reaped stale run {}: startedAt={} exceeded {}min threshold",
                        run.id(), startedAt, staleMinutes);
            }
            if (reaped > 0) {
                log.info("Sweeper reaped {} stale run(s)", reaped);
            }
        } catch (Exception e) {
            // 吞掉异常，避免毒化调度器（下次调度继续尝试）
            log.error("Sweeper failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 测试集生成任务的 stale 清理（同阈值同模式，作用于 evaluation_dataset_gen_run）。
     */
    @Scheduled(fixedRate = 5 * 60 * 1000L, initialDelay = 90 * 1000L)
    public void reapStaleGenerationJobs() {
        try {
            int staleMinutes = evalProps.getRunner().getStaleRunMinutes();
            Instant cutoff = Instant.now().minusSeconds(staleMinutes * 60L);
            for (var job : genJobRepo.listByStatus("running")) {
                OffsetDateTime startedAt = job.startedAt();
                if (startedAt == null || !startedAt.toInstant().isBefore(cutoff)) {
                    continue;
                }
                genJobRepo.markFailed(job.id(),
                        "marked stale by sweeper (running over " + staleMinutes + " minutes)");
                genProgressSink.complete(job.id());
                log.warn("Reaped stale generation job {}: startedAt={} exceeded {}min threshold",
                        job.id(), startedAt, staleMinutes);
            }
        } catch (Exception e) {
            log.error("Generation job sweeper failed: {}", e.getMessage(), e);
        }
    }
}
