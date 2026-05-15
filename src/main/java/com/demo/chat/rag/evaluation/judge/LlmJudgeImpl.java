package com.demo.chat.rag.evaluation.judge;

import com.demo.chat.rag.evaluation.config.EvaluationProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * LLM-as-Judge 实现
 * <p>
 * 基于 Spring AI ChatClient 调用 Judge 模型。
 * 关键设计：
 * <ul>
 *   <li>temperature=0：确保评分确定性</li>
 *   <li>最多重试 2 次：应对 API 临时错误</li>
 *   <li>三层 JSON 解析容错：raw → ```json``` → 正则</li>
 * </ul>
 * </p>
 */
public class LlmJudgeImpl implements LlmJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeImpl.class);
    private static final int MAX_RETRIES = 2;

    private final ChatClient judgeClient;
    private final String judgeModel;
    private final ObjectMapper objectMapper;

    public LlmJudgeImpl(ChatClient judgeClient,
                        EvaluationProperties props,
                        ObjectMapper objectMapper) {
        this.judgeClient = judgeClient;
        this.judgeModel = props.getJudgeModel();
        this.objectMapper = objectMapper;
    }

    @Override
    public JudgeVerdict evaluate(String prompt) {
        Exception lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                String response = judgeClient.prompt()
                        .user(prompt)
                        .call()
                        .content();
                if (response == null || response.isBlank()) {
                    throw new IllegalStateException("Judge returned empty response");
                }
                return JudgeVerdict.ok(response);
            } catch (Exception e) {
                lastError = e;
                log.warn("Judge attempt {} failed: {}", attempt + 1, e.getMessage());
            }
        }
        return JudgeVerdict.failed(lastError.getMessage());
    }

    @Override
    public List<String> generateQuestions(String answer) {
        String prompt = """
                给定以下回答，生成 3 个该回答可能回应的问题。
                问题应该简洁、具体。

                回答：
                %s

                输出 JSON 数组（不要输出其他内容）：
                [
                  "问题1",
                  "问题2",
                  "问题3"
                ]
                """.formatted(answer);

        JudgeVerdict verdict = evaluate(prompt);
        if (!verdict.success()) {
            log.warn("Failed to generate questions: {}", verdict.errorMessage());
            return Collections.emptyList();
        }

        try {
            String json = extractJson(verdict.rawJson());
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse generated questions: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 多层 JSON 提取策略：
     * 1. 直接解析 raw JSON
     * 2. 提取 ```json ... ``` 代码块
     * 3. 正则提取最外层 { ... } 或 [ ... ]
     */
    private String extractJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        // 尝试提取 markdown 代码块
        var matcher = Pattern.compile("```json\\s*\\n([\\s\\S]*?)\\n\\s*```").matcher(raw);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        // 尝试提取 { ... }
        int startBrace = raw.indexOf('{');
        int endBrace = raw.lastIndexOf('}');
        if (startBrace >= 0 && endBrace > startBrace) {
            return raw.substring(startBrace, endBrace + 1);
        }
        // 尝试提取 [ ... ]
        int startBracket = raw.indexOf('[');
        int endBracket = raw.lastIndexOf(']');
        if (startBracket >= 0 && endBracket > startBracket) {
            return raw.substring(startBracket, endBracket + 1);
        }
        return trimmed;
    }
}
