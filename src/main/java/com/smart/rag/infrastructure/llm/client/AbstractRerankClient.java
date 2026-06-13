package com.smart.rag.infrastructure.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Rerank 客户端抽象基类
 * <p>
 * 子类只需实现 {@link #rerank(RerankRequest)}。
 * 带 topN 截断的重排序有默认实现。
 * <p>
 * <b>不包含重试/熔断逻辑</b>——由 {@code ResilientRerankClient} 装饰器在外部施加。
 */
public abstract class AbstractRerankClient implements RerankCapable {

    protected final ModelCandidate candidate;
    protected final String providerId;

    protected AbstractRerankClient(ModelCandidate candidate, String providerId) {
        this.candidate = candidate;
        this.providerId = providerId;
    }

    @Override
    public final String candidateId() { return candidate.id(); }

    @Override
    public final String providerId() { return providerId; }

    @Override
    public final String modelName() { return candidate.model(); }

    @Override
    public final LlmCapability capability() { return candidate.capability(); }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public abstract List<RerankResult> rerank(RerankRequest request);

    @Override
    public List<RerankResult> rerank(RerankRequest request, int topN) {
        return rerank(request).stream()
            .limit(topN)
            .toList();
    }

    /**
     * 解析 rerank API 的 JSON 响应为 {@link RerankResult} 列表。
     * <p>
     * 适用于 OpenAI 兼容格式的 rerank 响应：
     * {@code { results: [{ index, relevance_score }, ...] }}
     *
     * @param objectMapper JSON 解析器
     * @param json         API 原始响应
     * @param documents    原始文档列表（用于回填文档文本）
     * @return 解析后的 rerank 结果列表
     */
    protected static List<RerankResult> parseRerankResponse(ObjectMapper objectMapper,
                                                             String json,
                                                             List<String> documents) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                return List.of();
            }

            List<RerankResult> rerankResults = new ArrayList<>(results.size());
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                double score = item.path("relevance_score").asDouble(0.0);
                if (index >= 0 && index < documents.size()) {
                    rerankResults.add(new RerankResult(index, score, documents.get(index)));
                }
            }
            return rerankResults;
        } catch (IOException e) {
            // LLM_STREAM_ERROR used as catch-all for parse failures (no dedicated parse error code)
            throw new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
                "Failed to parse rerank response: " + e.getMessage(), e);
        }
    }
}
