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
    public LlmJudge llmJudge(ChatClient.Builder chatClientBuilder,
                             EvaluationProperties evaluationProperties,
                             ObjectMapper objectMapper) {
        log.info("LlmJudge initialized with model: {}", evaluationProperties.getJudgeModel());
        return new LlmJudgeImpl(chatClientBuilder, evaluationProperties, objectMapper);
    }
}
