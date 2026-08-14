package com.smart.rag.rag.retrieval.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.rag.config.RagEntityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PC1：query → LLM 抽取 seed entities（§6.1）。
 * <p>
 * SRP：仅负责"query → seed entity 名称列表"，不含向量匹配/排序/回链。
 * DIP：依赖 {@link ChatCapable} 接口（经 {@link LlmClientRegistry} 解析），不注入具体客户端。
 * <p>
 * 失败隔离（§8.3）：LLM 调用失败或解析失败返回空列表，不阻塞 query（Path A/B 仍可召回）。
 */
@Component
@ConditionalOnProperty(prefix = "app.rag.entity", name = "enabled", havingValue = "true")
public class EntitySeedExtractor {

    private static final Logger log = LoggerFactory.getLogger(EntitySeedExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Seed 抽取 prompt（§4.2 entities 子集，只取实体名不取 event）。
     * 输出 JSON 数组：["entity1", "entity2", ...]
     */
    private static final String SEED_SYSTEM_PROMPT = """
            你是一个实体抽取助手。从用户查询中提取关键实体（人名、地名、机构、技术、概念等）。
            仅输出 JSON 数组，不要解释。示例：
            查询："PostgreSQL 的向量检索与 MySQL 的全文检索对比"
            输出：["PostgreSQL", "向量检索", "MySQL", "全文检索"]
            无实体时输出空数组 []。""";

    private final LlmClientRegistry llmClientRegistry;
    private final RagEntityProperties properties;

    public EntitySeedExtractor(LlmClientRegistry llmClientRegistry, RagEntityProperties properties) {
        this.llmClientRegistry = llmClientRegistry;
        this.properties = properties;
    }

    /**
     * 抽取 seed entities。
     *
     * @param query 已规范化的查询文本
     * @return seed 实体名列表（可能为空）
     */
    public List<String> extract(String query) {
        try {
            ChatCapable chatClient;
            String model = properties.extractionModel();
            if (model != null && !model.isBlank()) {
                chatClient = llmClientRegistry.get(model, ChatCapable.class);
            } else {
                chatClient = llmClientRegistry.getDefault(LlmCapability.CHAT, ChatCapable.class);
            }

            ChatRequest request = ChatRequest.withSystem(SEED_SYSTEM_PROMPT, query);
            LlmResponse response = chatClient.chat(request);
            return parseEntities(response.content());
        } catch (RuntimeException e) {
            // 失败隔离（§8.3）：LLM 调用链（registry 解析 / chat / 解析）均只抛 unchecked 异常
            // （ChatCapable.chat 无 checked 声明），捕获 RuntimeException 即可覆盖全部失败面。
            log.warn("Seed entity extraction failed, returning empty list (Path A/B unaffected): {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析 LLM 返回的 JSON 数组为实体名列表。
     * 容错：非数组 JSON / 解析异常 → 空列表。
     */
    private List<String> parseEntities(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(json.trim());
            if (!root.isArray()) {
                return List.of();
            }
            List<String> entities = new ArrayList<>(root.size());
            for (JsonNode node : root) {
                String name = node.asText("").trim();
                if (!name.isEmpty()) {
                    entities.add(name);
                }
            }
            return entities;
        } catch (Exception e) {
            log.warn("Failed to parse seed entities JSON: {}", e.getMessage());
            return List.of();
        }
    }
}
