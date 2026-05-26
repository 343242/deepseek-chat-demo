package com.smart.rag.rag.retrieval;

import com.smart.rag.rag.agent.service.HybridSearchService;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;

/**
 * 混合检索器 -- 向量检索 + BM25 全文检索 + RRF 融合 + 用户/团队隔离
 * <p>
 * 核心检索逻辑已提取到 {@link HybridSearchService}，本类作为 DocumentRetriever 接口的适配器。
 * Pipeline 模式（MULTI_TURN）通过本类使用混合检索，Agent 模式通过 HybridSearchTool 直接使用 Service。
 * <p>
 * 隔离规则：
 * <ul>
 *   <li>teamId != null -> 按 teamId 过滤（团队知识库检索）</li>
 *   <li>teamId == null -> 按 userId 过滤（个人知识库检索）</li>
 * </ul>
 */
public class HybridDocumentRetriever implements DocumentRetriever {

    private final HybridSearchService hybridSearchService;
    private final Long userId;
    @Nullable
    private final Long teamId;

    public HybridDocumentRetriever(HybridSearchService hybridSearchService,
                                   Long userId,
                                   @Nullable Long teamId) {
        this.hybridSearchService = hybridSearchService;
        this.userId = userId;
        this.teamId = teamId;
    }

    @Override
    public List<Document> retrieve(Query query) {
        return hybridSearchService.hybridSearch(query.text(), userId, teamId);
    }
}
