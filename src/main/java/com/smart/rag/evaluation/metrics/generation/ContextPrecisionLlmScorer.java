package com.smart.rag.evaluation.metrics.generation;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 上下文精度评分器 LLM 版（翻译 ragas ContextPrecision（LLMContextPrecisionWithReference））：
 * 逐片段判断"该片段对推导出给定（参考）答案是否有用"，按平均精度（Average Precision）
 * 聚合——相关片段排得越靠前分数越高。判决经 {@link ChunkVerdictSupport} 批量执行。
 * 哨兵约定：Judge 失败 -1；无片段 0。
 */
@Component
public class ContextPrecisionLlmScorer {

    private final ChunkVerdictSupport chunkVerdicts;

    public ContextPrecisionLlmScorer(ChunkVerdictSupport chunkVerdicts) {
        this.chunkVerdicts = chunkVerdicts;
    }

    /**
     * @param question          用户问题
     * @param groundTruthAnswer 标准答案
     * @param contextDocs       检索到的文档片段（有序，排名即输入顺序）
     * @return 平均精度（0-1），Judge 失败 -1，无片段 0
     */
    public double score(String question, String groundTruthAnswer, List<Document> contextDocs) {
        if (contextDocs == null || contextDocs.isEmpty()) {
            return 0;
        }
        var verdicts = chunkVerdicts.verdicts(
                GenerationPrompts.CHUNK_VERDICT_FOR_REFERENCE, question, groundTruthAnswer,
                contextDocs);
        if (verdicts == null) {
            return -1;
        }
        return ChunkVerdictSupport.averagePrecision(verdicts);
    }
}
