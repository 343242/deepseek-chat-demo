package com.smart.rag.evaluation.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.metrics.generation.GenerationPrompts;
import com.smart.rag.evaluation.util.JsonExtractorUtil;
import com.smart.rag.infrastructure.concurrent.ScopeJoiner;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM-as-Judge 实现
 * <p>
 * 基于 Spring AI ChatClient 调用 Judge 模型。温度策略对齐 ragas 0.4.3：
 * 单次生成 0.01（近确定性），reverse-question 多采样 0.3（等价
 * {@code BaseRagasLLM.get_temperature(n)}：n&gt;1 取 0.3）。
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

    /** ragas 单次 judge 生成温度（BaseRagasLLM.get_temperature(1)） */
    private static final double SINGLE_TEMPERATURE = 0.01;

    /** ragas 多采样温度（BaseRagasLLM.get_temperature(n>1)） */
    private static final double MULTI_TEMPERATURE = 0.3;

    private final ChatClient judgeClient;
    private final ObjectMapper objectMapper;
    private final ScopedTasks scopedTasks;

    public LlmJudgeImpl(ChatClient judgeClient,
                        ObjectMapper objectMapper,
                        ScopedTasks scopedTasks) {
        this.judgeClient = judgeClient;
        this.objectMapper = objectMapper;
        this.scopedTasks = scopedTasks;
    }

    @Override
    public JudgeVerdict evaluate(String prompt) {
        try {
            String response = judgeClient.prompt()
                    .user(prompt)
                    .options(ChatOptions.builder().temperature(SINGLE_TEMPERATURE).build())
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
    public List<GeneratedQuestion> generateQuestionsWithFlags(String answer, int strictness) {
        var prompt = GenerationPrompts.REVERSE_QUESTION_WITH_FLAG.formatted(answer);
        // strictness 次独立采样（每次一个 question + noncommittal），失败调用不贡献条目
        List<GeneratedQuestion> results = new ArrayList<>();
        try (TaskScope scope = scopedTasks.open("judge-reverse-question")) {
            for (int i = 0; i < strictness; i++) {
                scope.fork("sample-" + i, () -> generateOne(prompt));
            }
            @SuppressWarnings("unchecked")
            var sampled = (List<GeneratedQuestion>) (List<?>)
                    scope.join(ScopeJoiner.successfulResults(Object.class));
            sampled.forEach(q -> {
                if (q != null) {
                    results.add(q);
                }
            });
        }
        return results;
    }

    /** 单次采样：温度 0.3，解析失败返回 null（由调用方过滤）。 */
    private GeneratedQuestion generateOne(String prompt) {
        try {
            String response = judgeClient.prompt()
                    .user(prompt)
                    .options(ChatOptions.builder().temperature(MULTI_TEMPERATURE).build())
                    .call()
                    .content();
            if (response == null || response.isBlank()) {
                log.warn("Reverse question generation returned empty response");
                return null;
            }
            String json = JsonExtractorUtil.extractJson(response);
            Map<String, Object> parsed = objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            String question = parsed.get("question") instanceof String s ? s : "";
            boolean noncommittal = Boolean.TRUE.equals(parsed.get("noncommittal"))
                    || "true".equalsIgnoreCase(String.valueOf(parsed.get("noncommittal")));
            return new GeneratedQuestion(question, noncommittal);
        } catch (Exception e) {
            log.warn("Reverse question generation failed: {}", e);
            return null;
        }
    }
}
