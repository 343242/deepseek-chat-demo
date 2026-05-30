package com.smart.rag.config;

import com.smart.rag.chat.service.ModelRegistryRefresher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 模型厂商统一自动配置
 * <p>
 * 集中管理所有 Provider 的配置属性绑定、RestClient Bean 和启动时模型初始化。
 * 每个厂商的 RestClient Bean 通过 {@code @Qualifier} 区分，避免冲突。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>OCP — 新增 Provider 只需：① 新增 Properties record ② 新增 RestClient Bean ③ 在 Provider 类中使用</li>
 *   <li>SRP — 每个 RestClient Bean 只负责自己厂商的 HTTP 配置</li>
 *   <li>统一模式 — 所有 Provider 的 Properties 和 RestClient 创建方式保持一致</li>
 * </ul>
 *
 * @see DeepSeekProperties
 * @see MiniMaxProperties
 * @see ZhipuProperties
 */
@Configuration
@EnableConfigurationProperties({
        DeepSeekProperties.class,
        MiniMaxProperties.class,
        ZhipuProperties.class
})
public class ModelProviderAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModelProviderAutoConfiguration.class);

    /** 启动模型初始化专用 executor，避免使用 ForkJoinPool.commonPool */
    private static final Executor INIT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    // ==================== RestClient Beans ====================

    /**
     * DeepSeek 专用 RestClient
     * <p>
     * 供 DeepSeekModelProvider 调用 /models API 使用。
     */
    @Bean("deepSeekRestClient")
    public RestClient deepSeekRestClient(DeepSeekProperties properties) {
        return buildProviderRestClient(properties.baseUrl(), properties.apiKey());
    }

    /**
     * MiniMax 专用 RestClient
     * <p>
     * 供 MiniMaxModelProvider 调用 /models API 使用。
     */
    @Bean("miniMaxRestClient")
    public RestClient miniMaxRestClient(MiniMaxProperties properties) {
        return buildProviderRestClient(properties.baseUrl(), properties.apiKey());
    }

    // ==================== Startup ====================

    /**
     * 启动时异步从所有 Provider 拉取模型列表并注册到 ChatClientRegistry
     * <p>
     * 使用 CompletableFuture 异步执行，不阻塞 Spring Boot 启动过程。
     */
    @Bean
    public ApplicationRunner modelInitializer(ModelRegistryRefresher refresher) {
        return args -> {
            log.info("Initializing models from all providers (async)...");
            CompletableFuture.runAsync(() -> {
                boolean success = refresher.refresh();
                if (success) {
                    log.info("Model initialization completed");
                } else {
                    log.warn("Model initialization failed, service may be partially available");
                }
            }, INIT_EXECUTOR).exceptionally(ex -> {
                log.warn("Model initialization error: {}", ex.getMessage());
                return null;
            });
        };
    }

    // ==================== Private Helpers ====================

    /**
     * 构建厂商通用 RestClient
     * <p>
     * 所有厂商共用相同的 HTTP 配置（连接超时 10s、读超时 60s、JSON Accept 头、Bearer Token 认证）。
     * 各厂商的差异通过 baseUrl 和 apiKey 参数注入。
     *
     * @param baseUrl 厂商 API 基础 URL
     * @param apiKey  厂商 API Key
     * @return 配置好的 RestClient 实例
     */
    private RestClient buildProviderRestClient(String baseUrl, String apiKey) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build()
        );
        requestFactory.setReadTimeout(Duration.ofSeconds(60));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }
}
