package com.smart.rag.evaluation.config;

import com.smart.rag.evaluation.judge.LlmJudge;
import com.smart.rag.evaluation.judge.LlmJudgeImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
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
 * <p>
 * Judge 模型完全独立于 Provider 路由体系（ChatClientRegistry），
 * 通过 app.evaluation.judge.* 配置直连厂商 API，评估模块作为数据孤岛。
 * </p>
 */
@Configuration
@Profile("evaluation")
@ConditionalOnProperty(name = "app.evaluation.enabled", havingValue = "true")
public class EvaluationConfig {

    private static final Logger log = LoggerFactory.getLogger(EvaluationConfig.class);

    /**
     * 创建 Judge 专用 ChatClient
     * <p>
     * 直接通过 ZhiPuAiApi 创建，不经过 ChatClientRegistry / ProviderRegistry。
     * temperature=0 确保评分确定性。
     */
    @Bean("judgeChatClient")
    public ChatClient judgeChatClient(EvaluationProperties evaluationProperties) {
        EvaluationProperties.Judge judgeConfig = evaluationProperties.getJudge();

        log.info("Initializing isolated Judge ChatClient: model={}, baseUrl={}",
                judgeConfig.getModel(), judgeConfig.getBaseUrl());

        ZhiPuAiApi api = ZhiPuAiApi.builder()
                .baseUrl(judgeConfig.getBaseUrl())
                .apiKey(judgeConfig.getApiKey())
                .build();

        ZhiPuAiChatOptions options = ZhiPuAiChatOptions.builder()
                .model(judgeConfig.getModel())
                .temperature(0.0)
                .build();

        ZhiPuAiChatModel chatModel = new ZhiPuAiChatModel(api, options);

        return ChatClient.builder(chatModel).build();
    }

    /**
     * 注册 LLM Judge Bean
     * <p>
     * Judge 模型独立于生成模型，用于生成侧指标的客观评估。
     * </p>
     */
    @Bean
    public LlmJudge llmJudge(ChatClient judgeChatClient,
                             EvaluationProperties evaluationProperties,
                             ObjectMapper objectMapper) {
        log.info("LlmJudge initialized with model: {}", evaluationProperties.getJudgeModel());
        return new LlmJudgeImpl(judgeChatClient, evaluationProperties, objectMapper);
    }
}
