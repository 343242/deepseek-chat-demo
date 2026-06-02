package com.smart.rag.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ModelProviderAutoConfiguration")
class ModelProviderAutoConfigurationTest {

    @Test
    @DisplayName("中心配置不声明具体模型厂商 Properties")
    void centralConfigurationDoesNotEnableProviderSpecificProperties() {
        EnableConfigurationProperties annotation =
                ModelProviderAutoConfiguration.class.getAnnotation(EnableConfigurationProperties.class);

        assertThat(annotation).isNull();
    }

    @Test
    @DisplayName("中心配置不声明具体模型厂商 RestClient Bean")
    void centralConfigurationDoesNotDeclareProviderSpecificRestClients() {
        var beanMethods = Arrays.stream(ModelProviderAutoConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .map(method -> method.getName().toLowerCase())
                .toList();

        assertThat(beanMethods)
                .doesNotContain("deepseekrestclient", "minimaxrestclient");
    }
}
