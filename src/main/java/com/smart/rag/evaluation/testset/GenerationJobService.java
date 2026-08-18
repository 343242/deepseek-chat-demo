package com.smart.rag.evaluation.testset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * 测试集生成任务编排（镜像 {@code EvaluationExecutionService} 的异步生命周期）：
 * fire-and-forget 到 evalExecutor（虚拟线程），evalRunSemaphore 背压防打爆 LLM API，
 * 进度经 {@link GenerationProgressSink} 推送 SSE 并落库（断线可查）。
 */
@Service
@Profile("evaluation")
public class GenerationJobService {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobService.class);

    private final TestsetGeneratorService generator;
    private final GenerationJobRepository jobRepo;
    private final GenerationProgressSink progressSink;
    private final EvaluationProperties props;
    private final ObjectMapper objectMapper;
    private final ExecutorService evalExecutor;
    private final Semaphore evalRunSemaphore;

    public GenerationJobService(TestsetGeneratorService generator,
                                GenerationJobRepository jobRepo,
                                GenerationProgressSink progressSink,
                                EvaluationProperties props,
                                ObjectMapper objectMapper,
                                @Qualifier("evalExecutor") ExecutorService evalExecutor,
                                @Qualifier("evalRunSemaphore") Semaphore evalRunSemaphore) {
        this.generator = generator;
        this.jobRepo = jobRepo;
        this.progressSink = progressSink;
        this.props = props;
        this.objectMapper = objectMapper;
        this.evalExecutor = evalExecutor;
        this.evalRunSemaphore = evalRunSemaphore;
    }

    /**
     * 提交生成任务（202 语义）：插入 pending 记录并异步执行，立即返回 jobId。
     */
    public long submit(String name, long userId) {
        var configJson = toJson(Map.of(
                "name", name,
                "userId", userId,
                "size", props.getDataset().getSize(),
                "maxChunks", props.getDataset().getMaxChunks()));
        long jobId = jobRepo.insert(userId, name, configJson);
        evalExecutor.execute(() -> execute(jobId, name, userId));
        return jobId;
    }

    private void execute(long jobId, String name, long userId) {
        var runner = props.getRunner();
        boolean acquired = false;
        try {
            acquired = evalRunSemaphore.tryAcquire(
                    runner.getAcquireTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) {
                jobRepo.markFailed(jobId, "生成并发上限（max-concurrent-runs）等待超时");
                return;
            }
            jobRepo.markRunning(jobId);
            var dataset = generator.generate(name, userId,
                    (phase, current, total, message) -> {
                        var event = new GenerationProgressEvent(phase, current, total, message);
                        progressSink.emit(jobId, event);
                        jobRepo.updateProgress(jobId, toJson(event));
                    });
            jobRepo.markCompleted(jobId, dataset.id());
        } catch (Exception e) {
            log.error("生成任务 {} 失败: {}", jobId, e.getMessage(), e);
            jobRepo.markFailed(jobId, e.getMessage());
        } finally {
            if (acquired) {
                evalRunSemaphore.release();
            }
            progressSink.complete(jobId);
        }
    }

    public GenerationJobRecord getJob(long jobId) {
        return jobRepo.find(jobId).orElseThrow(() ->
                new ServiceException(ServiceErrorCode.NOT_FOUND,
                        "Generation job not found: " + jobId));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
