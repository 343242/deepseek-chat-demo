package com.smart.rag.evaluation.judge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import com.smart.rag.evaluation.metrics.generation.GenerationPrompts;
import com.smart.rag.evaluation.util.JsonExtractorUtil;

import java.util.Collections;
import java.util.List;

/**
 * LLM-as-Judge 实现
 * <p>
 * 基于 Spring AI ChatClient 调用 Judge 模型。
 * 关键设计：
 * <ul>
 *   <li>底层 ChatClient 由 LlmClientRegistry 解析，重试 / 熔断 / fallback 透明复用</li>
 *   <li>本类不再叠加二次重试，仅捕获异常并降级为 JudgeVerdict.failed，保证单条评估不中断</li>
 *   <li>三层 JSON 解析容错：raw → ```json``` → 正则</li>
 * </ul>
 * </p>
 */
public class LlmJudgeImpl implements LlmJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeImpl.class);

    private final ChatClient judgeClient;
    private final ObjectMapper objectMapper;

    public LlmJudgeImpl(ChatClient judgeClient,
                        ObjectMapper objectMapper) {
        this.judgeClient = judgeClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public JudgeVerdict evaluate(String prompt) {
        try {
            String response = judgeClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            if (response == null || response.isBlank()) {
                return JudgeVerdict.failed("Judge returned empty response");
            }
            return JudgeVerdict.ok(response);
        } catch (Exception e) {
            // 网络层错误已由 ResilientChatClient 重试，此处仅兜底降级，避免中断整条评测
            log.warn("Judge invocation failed: {}", e);
            return JudgeVerdict.failed(e.getMessage());
        }
    }

    @Override
    public List<String> generateQuestions(String answer) {
        var prompt = GenerationPrompts.REVERSE_QUESTION_GENERATION.formatted(answer);

        JudgeVerdict verdict = evaluate(prompt);
        if (!verdict.success()) {
            log.warn("Failed to generate questions: {}", verdict.errorMessage());
            return Collections.emptyList();
        }

        try {
            String json = JsonExtractorUtil.extractJson(verdict.rawJson());
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse generated questions: {}", e);
            return Collections.emptyList();
        }
    }
}
