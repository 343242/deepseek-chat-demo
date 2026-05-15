package com.demo.chat.rag.evaluation.config;

import com.demo.chat.rag.evaluation.judge.LlmJudge;
import com.demo.chat.rag.evaluation.judge.LlmJudgeImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 评估模块配置
 * <p>
 * 仅在以下条件同时满足时激活：
 * <ul>
 *   <li>Spring Profile 包含 "evaluation"</li>
 *   <li>app.evaluation.enabled = true</li>
 * </ul>
 * 生产环境默认关闭，确保零侵入。
 * </p>
 */
@Configuration
@Profile("evaluation")
@ConditionalOnProperty(name = "app.evaluation.enabled", havingValue = "true")
public class EvaluationConfig {

    private static final Logger log = LoggerFactory.getLogger(EvaluationConfig.class);

    /**
     * 注册 LLM Judge Bean
     * <p>
     * Judge 模型独立于生成模型，用于生成侧指标的客观评估。
     * temperature=0 确保评分确定性。
     * </p>
     */
    @Bean
    public LlmJudge llmJudge(ChatClient judgeChatClient,
                             EvaluationProperties evaluationProperties,
                             ObjectMapper objectMapper) {
        log.info("LlmJudge initialized with model: {}", evaluationProperties.getJudgeModel());
        return new LlmJudgeImpl(judgeChatClient, evaluationProperties, objectMapper);
    }

    /**
     * 通过 ChatClientRegistry 获取指定 Judge 模型的 ChatClient（可选）。
     * <p>
     * 如果 Registry 中有该模型，优先使用 Registry 的实例（走 Provider 路由）。
     * 否则回退到 ChatClient.Builder 构建（使用 Spring AI 默认模型）。
     * </p>
     */
    @Bean("judgeChatClient")
    public ChatClient judgeChatClient(ChatClient.Builder chatClientBuilder,
                                     EvaluationProperties evaluationProperties,
                                     com.demo.chat.chat.client.ChatClientRegistry chatClientRegistry) {
        String judgeModel = evaluationProperties.getJudgeModel();
        if (chatClientRegistry.contains(judgeModel)) {
            log.info("Judge model '{}' found in ChatClientRegistry, using Provider routing", judgeModel);
            return chatClientRegistry.get(judgeModel);
        }
        log.warn("Judge model '{}' not found in ChatClientRegistry, falling back to ChatClient.Builder (may use default model)", judgeModel);
        return chatClientBuilder.build();
    }
}
