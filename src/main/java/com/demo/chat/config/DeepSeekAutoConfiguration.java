package com.demo.chat.config;

import com.demo.chat.chat.service.ModelRegistryRefresher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 模型自动配置
 * <p>
 * 职责：配置属性绑定、DeepSeek RestClient、启动时模型初始化。
 * 模型拉取和注册逻辑委托给 {@link ModelRegistryRefresher}。
 */
@Configuration
@EnableConfigurationProperties(DeepSeekProperties.class)
public class DeepSeekAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekAutoConfiguration.class);

    /**
     * DeepSeek 专用 RestClient
     * <p>
     * 供 DeepSeekModelProvider 调用 /models API 使用。
     * Bean 命名为 "deepSeekRestClient" 以避免与其他 Provider 的 RestClient 冲突。
     */
    @Bean("deepSeekRestClient")
    public RestClient deepSeekRestClient(DeepSeekProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build()
        );
        requestFactory.setReadTimeout(Duration.ofSeconds(60));

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                .build();
    }

    @Bean
    public CommandLineRunner modelInitializer(ModelRegistryRefresher refresher) {
        return args -> {
            log.info("Initializing models from all providers...");
            boolean success = refresher.refresh();
            if (success) {
                log.info("Model initialization completed");
            } else {
                log.warn("Model initialization failed, service may be partially available");
            }
        };
    }
}
