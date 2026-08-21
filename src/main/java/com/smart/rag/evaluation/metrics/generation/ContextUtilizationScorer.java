package com.smart.rag.evaluation.metrics.generation;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 上下文利用率评分器（翻译 ragas ContextUtilization / LLMContextPrecisionWithoutReference）：
 * reference-free 版上下文精度——判决对象从参考答案换成系统自己生成的回答，
 * 逐片段判断"该片段对得出该回答是否有用"，按同一 Average Precision 公式聚合。
 * 评测排序质量相对系统自身行为，无需 GT（判决质量受生成回答质量牵连，
 * ragas 因此单独命名以区别于有参考版）。
 * 哨兵约定：Judge 失败 -1；无片段 0。
 */
@Component
public class ContextUtilizationScorer {

    private final ChunkVerdictSupport chunkVerdicts;

    public ContextUtilizationScorer(ChunkVerdictSupport chunkVerdicts) {
        this.chunkVerdicts = chunkVerdicts;
    }

    /**
     * @param question   用户问题
     * @param answer     生成的回答（判决对象）
     * @param contextDocs 检索到的文档片段（有序）
     * @return 平均精度（0-1），Judge 失败 -1，无片段 0
     */
    public double score(String question, String answer, List<Document> contextDocs) {
        if (contextDocs == null || contextDocs.isEmpty()) {
            return 0;
        }
        var verdicts = chunkVerdicts.verdicts(
                GenerationPrompts.CHUNK_VERDICT_FOR_ANSWER, question, answer, contextDocs);
        if (verdicts == null) {
            return -1;
        }
        return ChunkVerdictSupport.averagePrecision(verdicts);
    }
}
