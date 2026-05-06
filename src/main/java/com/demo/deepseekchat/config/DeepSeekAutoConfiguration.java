package com.demo.deepseekchat.config;

import com.demo.deepseekchat.chat.ChatClientFactory;
import com.demo.deepseekchat.chat.ChatClientRegistry;
import com.demo.deepseekchat.model.dto.ModelInfo;
import com.demo.deepseekchat.model.dto.ModelsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * DeepSeek 自动配置
 * <p>
 * 职责：启动时拉取模型列表，通过工厂创建 ChatClient，注册到 Registry。
 * 使用 CommandLineRunner 确保所有 Bean 初始化完成后再执行。
 */
@Configuration
@EnableConfigurationProperties(DeepSeekProperties.class)
public class DeepSeekAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekAutoConfiguration.class);

    @Bean
    public RestClient deepSeekRestClient(DeepSeekProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Bean
    public CommandLineRunner modelInitializer(
            DeepSeekProperties properties,
            ChatClientFactory factory,
            ChatClientRegistry registry,
            RestClient deepSeekRestClient) {
        return args -> initModels(properties, factory, registry, deepSeekRestClient);
    }

    private void initModels(DeepSeekProperties properties, ChatClientFactory factory,
                            ChatClientRegistry registry, RestClient restClient) {
        log.info("Fetching DeepSeek model list from {}...", properties.baseUrl());
        try {
            ModelsResponse response = restClient.get()
                    .uri("/models")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .retrieve()
                    .body(ModelsResponse.class);

            if (response == null || response.data() == null) {
                log.warn("Failed to fetch model list, response is null");
                return;
            }

            List<ModelInfo> models = response.data();
            registry.setCachedModels(models);
            log.info("Fetched {} models from DeepSeek", models.size());

            for (ModelInfo model : models) {
                registry.register(model.id(), factory.create(model.id()));
            }

            log.info("Registered {} ChatClients: {}", registry.size(), registry.getAvailableModelIds());
        } catch (Exception e) {
            log.error("Failed to initialize DeepSeek models", e);
        }
    }
}
