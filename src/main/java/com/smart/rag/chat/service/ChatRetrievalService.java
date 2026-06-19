package com.smart.rag.chat.service;

import com.smart.rag.rag.config.RagAdvisorFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Chat 路径检索服务（方案 A：拆 Advisor 壳，复用 RagAdvisorFactory 的检索组件）。
 * <p>
 * 返回编号/格式化前的原始 {@link Document} 列表；编号 + 拼 {@code <<REF>>} 块 + 产 references
 * 由 {@link ChatReferenceCollector} 负责。隔离（userId/teamId）由 RagAdvisorFactory 保证。
 */
@Service
public class ChatRetrievalService {

    private final RagAdvisorFactory ragAdvisorFactory;

    public ChatRetrievalService(RagAdvisorFactory ragAdvisorFactory) {
        this.ragAdvisorFactory = ragAdvisorFactory;
    }

    /**
     * 执行隔离检索（query-transform → 检索 → MMR/Rerank/Parent），返回原始文档列表。
     *
     * @param query  用户查询原文
     * @param userId 当前用户 ID（隔离依据）
     * @param teamId 团队 ID（null = 个人知识库）
     * @return 检索到的文档列表（可能为空）
     */
    public List<Document> retrieve(String query, Long userId, @Nullable Long teamId) {
        return ragAdvisorFactory.retrieve(query, userId, teamId);
    }
}
