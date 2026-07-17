package com.smart.rag.evaluation.config;

import com.smart.rag.evaluation.judge.LlmJudge;
import com.smart.rag.evaluation.judge.LlmJudgeImpl;
import com.smart.rag.infrastructure.llm.adapter.RewriteClientResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 评估模块配置
 * <p>
 * 仅在 Spring Profile 包含 "evaluation" 时激活，生产环境默认关闭，确保零侵入。
 * </p>
 * <p>
 * Judge 与生成模型均复用 {@code LlmClientRegistry} 的候选路由体系，
 * 通过 {@link RewriteClientResolver} 解析为 Spring AI {@link ChatClient}，
 * 不再绑定任何具体厂商 SDK。
 * </p>
 * <p>
 * 激活守卫说明：全模块（含本类及所有 Scorer/Calculator/Runner/Controller）统一只用
 * {@code @Profile("evaluation")}，不加 {@code @ConditionalOnProperty}。
 * 否则当 profile 开启但 {@code app.evaluation.enabled=false} 时，本类不装载 →
 * 无 {@code LlmJudge} bean → Scorer 构造失败导致启动崩溃。
 * </p>
 */
@Configuration
@Profile("evaluation")
public class EvaluationConfig {

    private static final Logger log = LoggerFactory.getLogger(EvaluationConfig.class);

    /**
     * 创建 Judge 专用 ChatClient
     * <p>
     * 通过 {@link RewriteClientResolver} 从 {@code LlmClientRegistry} 解析候选，
     * candidate id 取自 {@code app.evaluation.judge.candidate-id}，
     * 为空时回退到默认 chat 候选。复用注册表的重试 / 熔断 / fallback。
     */
    @Bean("judgeChatClient")
    public ChatClient judgeChatClient(EvaluationProperties evaluationProperties,
                                      RewriteClientResolver rewriteClientResolver) {
        String candidateId = evaluationProperties.getJudge().getCandidateId();
        log.info("Initializing Judge ChatClient via registry: candidateId={}",
                (candidateId == null || candidateId.isBlank()) ? "<default>" : candidateId);
        return rewriteClientResolver.resolve(candidateId);
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
        log.info("LlmJudge initialized with candidate: {}", evaluationProperties.getJudgeModel());
        return new LlmJudgeImpl(judgeChatClient, objectMapper);
    }
}
