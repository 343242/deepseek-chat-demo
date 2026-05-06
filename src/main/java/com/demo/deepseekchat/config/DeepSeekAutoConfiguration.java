package com.demo.deepseekchat.config;

import com.demo.deepseekchat.service.ModelRegistryRefresher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * DeepSeek 自动配置
 * <p>
 * 职责：配置属性绑定、RestClient 创建、启动时模型初始化。
 * 模型拉取和注册逻辑委托给 {@link ModelRegistryRefresher}，消除重复代码。
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
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                .build();
    }

    @Bean
    public CommandLineRunner modelInitializer(ModelRegistryRefresher refresher) {
        return args -> {
            log.info("Initializing DeepSeek models...");
            boolean success = refresher.refresh();
            if (success) {
                log.info("DeepSeek model initialization completed");
            } else {
                log.warn("DeepSeek model initialization failed, service may be partially available");
            }
        };
    }
}
