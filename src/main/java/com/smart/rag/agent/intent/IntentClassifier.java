package com.smart.rag.agent.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.chat.client.ChatClientRegistry;
import com.smart.rag.exception.BusinessException;
import com.smart.rag.agent.config.AgentRagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 意图分类器 -- 独立 LLM 调用，对用户查询做意图分类
 * <p>
 * 使用 Spring AI Structured Output 映射到 IntentResult。
 * 低 temperature（0.1），分类任务追求确定性。
 * <p>
 * 容错策略：失败时降级为 DEEP_RETRIEVAL，重试 2 次 + 5s 超时。
 * <p>
 * 第一版：只做意图分类，subQueries 始终为空列表。
 */
@Component
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    /** 最大重试次数 */
    private static final int MAX_RETRIES = 2;

    /** 安全默认值：降级到 DEEP_RETRIEVAL（暴露全量 Tool，宁可多检索不漏检） */
    private static final IntentResult SAFE_FALLBACK = new IntentResult(
        AgentIntent.DEEP_RETRIEVAL, 0.0, Collections.emptyList()
    );

    private final ChatClientRegistry chatClientRegistry;
    private final String intentModelId;
    private final ObjectMapper objectMapper;

    /** 懒解析的 ChatClient，首次 classify() 时从 Registry 获取 */
    private volatile ChatClient intentChatClient;

    /**
     * 分类意图 + 分解查询
     * <p>
     * 容错策略：2 次重试 -> 降级 DEEP_RETRIEVAL
     *
     * @param query 用户查询文本
     * @return 意图分类结果
     */
    public IntentResult classify(String query) {
        // 1. 空查询保护
        if (query == null || query.isBlank()) {
            return SAFE_FALLBACK;
        }

        // 2. 带重试的 LLM 调用
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                IntentResult result = doClassify(query);
                return validate(result);
            } catch (Exception e) {
                log.warn("Intent classification failed (attempt {}): {}", attempt, e.getMessage());
            }
        }

        log.warn("Intent classification failed after {} retries, falling back to {}",
            MAX_RETRIES, SAFE_FALLBACK.intent());
        return SAFE_FALLBACK;
    }

    private IntentResult doClassify(String query) {
        String prompt = buildPrompt(query);

        // 第一版：只请求 intent 和 confidence，subQueries 始终为空
        // 使用 ChatClient 的 Structured Output 功能映射到 IntentResult
        // Spring AI 会自动将 LLM 返回的 JSON 反序列化为 IntentResult record
        try {
            String response = resolveChatClient().prompt()
                .user(prompt)
                .call()
                .content();

            if (response == null || response.isBlank()) {
                throw new IllegalStateException("Intent classification returned empty response");
            }

            return parseResponse(response);
        } catch (Exception e) {
            throw new BusinessException("Intent classification LLM call failed", e);
        }
    }

    private IntentResult parseResponse(String response) {
        String json = extractJson(response);
        try {
            JsonNode node = objectMapper.readTree(json);
            String intentStr = node.has("intent") ? node.get("intent").asText() : "";
            double confidence = node.has("confidence") ? node.get("confidence").asDouble(0.0) : 0.0;

            AgentIntent intent = parseIntent(intentStr);
            return new IntentResult(intent, confidence, List.of());
        } catch (Exception e) {
            log.warn("Failed to parse intent response as JSON, falling back: {}", e.getMessage());
            return SAFE_FALLBACK;
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 对象文本。
     * <p>
     * 处理常见 LLM 输出变体：fenced code block（```json ... ```）、
     * 前后多余文本、空白字符等。
     */
    private String extractJson(String response) {
        String trimmed = response.trim();
        // Strip fenced code blocks (```json ... ``` or ``` ... ```)
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastBacktick = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastBacktick > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastBacktick).trim();
            }
        }
        // Find JSON object boundaries
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    /**
     * 将字符串转换为 AgentIntent，大小写不敏感。
     * <p>
     * 未知值降级为 DEEP_RETRIEVAL（宁可多检不漏检）。
     */
    private AgentIntent parseIntent(String value) {
        if (value == null || value.isBlank()) {
            return AgentIntent.DEEP_RETRIEVAL;
        }
        try {
            return AgentIntent.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown intent '{}', falling back to DEEP_RETRIEVAL", value);
            return AgentIntent.DEEP_RETRIEVAL;
        }
    }

    private IntentResult validate(IntentResult result) {
        if (result.intent() == null) {
            return SAFE_FALLBACK;
        }
        List<String> queries = result.subQueries() != null
            ? result.subQueries() : Collections.emptyList();
        if (queries.size() > 5) {
            queries = queries.subList(0, 5);
        }
        return new IntentResult(result.intent(), result.confidence(), queries);
    }

    private String buildPrompt(String query) {
        return """
            分析用户查询，完成意图分类任务。

            意图分类：
            - DIRECT_ANSWER: 通用知识、闲聊、简单问答，不需要知识库
            - RETRIEVAL: 需要知识库检索，单次检索即可满足，问题单一明确
            - DEEP_RETRIEVAL: 复杂问题，需要多轮检索、查询改写、语义精排
            - GENERAL_TOOL: 需要数学计算、日期查询、代码执行等工具

            示例：
              "今天天气怎么样" → DIRECT_ANSWER
              "Spring Boot 的自动装配原理" → RETRIEVAL
              "对比 RAG 和 Fine-tuning 在知识更新场景的优劣" → DEEP_RETRIEVAL
              "123 * 456 等于多少" → GENERAL_TOOL
              "你好" → DIRECT_ANSWER

            输出格式（JSON）：
            {
              "intent": "DIRECT_ANSWER|RETRIEVAL|DEEP_RETRIEVAL|GENERAL_TOOL",
              "confidence": 0.95
            }

            用户查询：%s

            请直接输出 JSON，不要包含其他内容。""".formatted(query);
    }

    public IntentClassifier(ChatClientRegistry chatClientRegistry,
                            AgentRagProperties properties,
                            ObjectMapper objectMapper) {
        this.chatClientRegistry = chatClientRegistry;
        this.intentModelId = properties.intentModel();
        this.objectMapper = objectMapper;
    }

    private ChatClient resolveChatClient() {
        ChatClient client = intentChatClient;
        if (client == null) {
            synchronized (this) {
                client = intentChatClient;
                if (client == null) {
                    client = chatClientRegistry.get(intentModelId);
                    intentChatClient = client;
                }
            }
        }
        return client;
    }
}
