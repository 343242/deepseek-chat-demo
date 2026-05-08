package com.demo.deepseekchat.chat.provider;

import com.demo.deepseekchat.chat.dto.ModelInfo;
import com.demo.deepseekchat.chat.dto.ProviderModelInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProviderModelInfo 单元测试")
class ProviderModelInfoTest {

    @Test
    @DisplayName("from(ModelInfo, providerId, providerName) 正确构建")
    void from_modelInfo_correctMapping() {
        ModelInfo modelInfo = new ModelInfo("deepseek-chat", "model", 1234567890L, "deepseek");

        ProviderModelInfo result = ProviderModelInfo.from(modelInfo, "deepseek", "DeepSeek");

        assertEquals("deepseek-chat", result.id());
        assertEquals("deepseek", result.providerId());
        assertEquals("DeepSeek", result.providerName());
        assertEquals("deepseek/deepseek-chat", result.compositeId());
        assertEquals("deepseek", result.ownedBy());
        assertEquals(1234567890L, result.created());
    }

    @Test
    @DisplayName("from 不同厂商的 ModelInfo")
    void from_differentProvider() {
        ModelInfo modelInfo = new ModelInfo("glm-4-air", "model", 0L, "zhipu");

        ProviderModelInfo result = ProviderModelInfo.from(modelInfo, "zhipu", "智谱 AI");

        assertEquals("glm-4-air", result.id());
        assertEquals("zhipu", result.providerId());
        assertEquals("智谱 AI", result.providerName());
        assertEquals("zhipu/glm-4-air", result.compositeId());
    }
}
