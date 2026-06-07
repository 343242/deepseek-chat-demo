package com.smart.rag.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ModelProviderAutoConfiguration")
class ModelProviderAutoConfigurationTest {

    @Test
    @DisplayName("中心配置绑定所有已知厂商 Properties")
    void centralConfigurationEnablesAllProviderProperties() {
        EnableConfigurationProperties annotation =
                ModelProviderAutoConfiguration.class.getAnnotation(EnableConfigurationProperties.class);

        assertThat(annotation).isNotNull();
        List<String> boundProperties = Arrays.stream(annotation.value())
                .map(Class::getSimpleName)
                .toList();
        assertThat(boundProperties).contains("DeepSeekProperties", "MiniMaxProperties", "ZhipuProperties");
    }

    @Test
    @DisplayName("中心配置为各厂商提供 RestClient Bean")
    void centralConfigurationDeclaresProviderRestClients() {
        var beanMethods = Arrays.stream(ModelProviderAutoConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .map(method -> method.getName().toLowerCase())
                .toList();

        assertThat(beanMethods).contains("deepseekrestclient", "minimaxrestclient");
    }
}
