package com.demo.chat.rag.chunk;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 文档分块策略接口
 * <p>
 * 每种切分策略实现此接口，由 {@link com.demo.chat.rag.chunk.ChunkStrategyFactory} 按 YAML 配置路由。
 * 新增策略只需实现此接口 + 注册为 Spring Bean，符合 OCP。
 * </p>
 */
public interface ChunkStrategy {

    /**
     * 策略标识（如 "token", "paragraph", "parent-child"）
     */
    String strategyName();

    /**
     * 对文档列表进行分块
     *
     * @param documents       原始文档列表（来自 Parser）
     * @param sourceFileName  来源文件名（用于元数据）
     * @return 分块后的文档列表（仅子切分进入向量库）
     */
    List<Document> chunk(List<Document> documents, String sourceFileName);
}
