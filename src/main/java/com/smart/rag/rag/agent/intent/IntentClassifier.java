package com.smart.rag.rag.agent.intent;

import com.smart.rag.chat.client.ChatClientRegistry;
import com.smart.rag.rag.agent.config.AgentRagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private final ChatClient intentChatClient;

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
            String response = intentChatClient.prompt()
                .user(prompt)
                .call()
                .content();

            if (response == null || response.isBlank()) {
                throw new IllegalStateException("Intent classification returned empty response");
            }

            return parseResponse(response);
        } catch (Exception e) {
            throw new RuntimeException("Intent classification LLM call failed", e);
        }
    }

    private IntentResult parseResponse(String response) {
        // 简单 JSON 解析：提取 intent 和 confidence 字段
        String intentStr = extractJsonField(response, "intent");
        String confidenceStr = extractJsonField(response, "confidence");

        AgentIntent intent;
        try {
            intent = AgentIntent.valueOf(intentStr);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown intent '{}', falling back to DEEP_RETRIEVAL", intentStr);
            intent = AgentIntent.DEEP_RETRIEVAL;
        }

        double confidence = 0.0;
        try {
            confidence = Double.parseDouble(confidenceStr);
        } catch (NumberFormatException e) {
            log.debug("Could not parse confidence '{}', defaulting to 0.0", confidenceStr);
        }

        // 第一版 subQueries 始终为空列表
        return new IntentResult(intent, confidence, List.of());
    }

    private String extractJsonField(String json, String field) {
        // 使用正则匹配 "field"\s*:\s*"value" 或 "field"\s*:\s*value
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"?([^\",\\}]+)\"?");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
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
                            AgentRagProperties properties) {
        this.intentChatClient = chatClientRegistry.get(properties.intentModel());
    }
}
