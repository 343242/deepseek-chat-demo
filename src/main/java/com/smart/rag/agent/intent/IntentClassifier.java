package com.smart.rag.agent.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.mode.AgentIntent;
import com.smart.rag.mode.IntentResult;
import com.smart.rag.infrastructure.llm.adapter.ChatModelAssembler;
import com.smart.rag.infrastructure.llm.usage.UsageScene;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.agent.config.AgentRagProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.util.Collections;
import java.util.List;

/**
 * 意图分类器 -- 独立 LLM 调用，对用户查询做意图分类
 * <p>
 * 容错策略：失败时降级为 DEEP_RETRIEVAL，重试 2 次。
 * 每次分类经 {@link ChatModelAssembler} 取 per-request ChatClient（scene=INTENT，带用户归因的用量采集）。
 */
@Component
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    private static final int MAX_RETRIES = 2;

    private static final IntentResult SAFE_FALLBACK = new IntentResult(
        AgentIntent.DEEP_RETRIEVAL, 0.0, Collections.emptyList()
    );

    private final ChatModelAssembler chatModelAssembler;
    private final String intentCandidateId;
    private final ObjectMapper objectMapper;

    public IntentClassifier(ChatModelAssembler chatModelAssembler,
                            AgentRagProperties properties,
                            ObjectMapper objectMapper) {
        this.chatModelAssembler = chatModelAssembler;
        this.intentCandidateId = properties.intentModel();
        this.objectMapper = objectMapper;
    }

    public IntentResult classify(Long userId, @Nullable String conversationId, String query) {
        if (query == null || query.isBlank()) {
            return SAFE_FALLBACK;
        }

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                IntentResult result = doClassify(userId, conversationId, query);
                return validate(result);
            } catch (Exception e) {
                log.warn("Intent classification failed (attempt {}): {}", attempt, e.getMessage());
            }
        }

        log.warn("Intent classification failed after {} retries, falling back to {}",
            MAX_RETRIES, SAFE_FALLBACK.intent());
        return SAFE_FALLBACK;
    }

    /**
     * 流式意图分类 -- LLM 调用走 chatStream 通道（聚合完整 JSON 再解析），返回 {@link Mono}。
     * <p>
     * 与 {@link #classify} 同源：复用 {@link #buildPrompt} / {@link #parseResponse} / {@link #validate}，
     * 区别仅在 LLM 调用走流式通道。语义上仍是"一次分类、异步产出"（Mono）-- 意图 JSON 必须收完整才能 parse，
     * 故流式化本身不降低意图判定的首字延迟；其价值在于与主响应统一走 chatStream（共用 ResilientChatClient
     * 流式降级链路），并为 Agent 流式（{@link com.smart.rag.agent.mode.AgentModeStrategy#executeStream}）铺路。
     * <p>
     * 容错策略与 {@link #classify} 一致：重试 {@value #MAX_RETRIES} 次，失败降级 {@link #SAFE_FALLBACK}。
     * 需意图模型声明 supports-streaming；不支持时 chatStream 抛异常 -> 重试耗尽 -> 降级 SAFE_FALLBACK。
     *
     * @param userId         发起用户（用量归因）
     * @param conversationId 会话 ID（用量归因，可 null）
     * @param query          用户查询
     * @return Mono，发出分类结果；query 为空时立即发出 SAFE_FALLBACK
     */
    public Mono<IntentResult> classifyStream(Long userId, @Nullable String conversationId, String query) {
        if (query == null || query.isBlank()) {
            return Mono.just(SAFE_FALLBACK);
        }
        return doClassifyStream(userId, conversationId, query)
            .map(this::validate)
            .retryWhen(Retry.max(MAX_RETRIES)
                .doAfterRetry(sig -> log.warn("Intent classification stream retry (attempt {}): {}",
                    sig.totalRetries(), sig.failure().getMessage())))
            .onErrorResume(e -> {
                log.warn("Intent classification stream failed after {} retries, falling back to {}",
                    MAX_RETRIES, SAFE_FALLBACK.intent());
                return Mono.just(SAFE_FALLBACK);
            });
    }

    private Mono<IntentResult> doClassifyStream(Long userId, @Nullable String conversationId, String query) {
        String prompt = buildPrompt(query);
        // Mono.defer 包裹：每次订阅（含 retryWhen 重试）都重新发起 LLM 调用，
        // 与阻塞版 doClassify 的 for-loop 重试语义一致 -- 否则 retry 只重订阅已组装的
        // Flux，不会真正重新调用 chatStream，部分成功后失败的请求无法真正重试。
        return Mono.defer(() -> resolveChatClient(userId, conversationId).prompt()
            .user(prompt)
            .stream()
            .content()
            .collectList()
            .map(chunks -> String.join("", chunks))
            .flatMap(response -> {
                if (response == null || response.isBlank()) {
                    return Mono.error(new IllegalStateException(
                        "Intent classification returned empty response"));
                }
                return Mono.just(parseResponse(response));
            }));
    }

    private IntentResult doClassify(Long userId, @Nullable String conversationId, String query) {
        String prompt = buildPrompt(query);

        try {
            String response = resolveChatClient(userId, conversationId).prompt()
                .user(prompt)
                .call()
                .content();

            if (response == null || response.isBlank()) {
                throw new IllegalStateException("Intent classification returned empty response");
            }

            return parseResponse(response);
        } catch (Exception e) {
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "Intent classification LLM call failed", e);
        }
    }

    /** per-request 解析（scene=INTENT，带用户/会话归因的用量采集；重试各次调用独立采样） */
    private ChatClient resolveChatClient(Long userId, @Nullable String conversationId) {
        return chatModelAssembler.chatClient(userId, intentCandidateId, UsageScene.INTENT, conversationId);
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

    private String extractJson(String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastBacktick = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastBacktick > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastBacktick).trim();
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

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
}
