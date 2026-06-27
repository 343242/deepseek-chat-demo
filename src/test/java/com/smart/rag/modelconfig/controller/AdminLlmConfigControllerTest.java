package com.smart.rag.modelconfig.controller;

import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.modelconfig.entity.LlmModelConfig;
import com.smart.rag.modelconfig.service.LlmModelConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminLlmConfigController 单元测试 — 仅 GET 任意 userId 配置（脱敏），无写入方法。
 */
@ExtendWith(MockitoExtension.class)
class AdminLlmConfigControllerTest {

    @Mock private LlmModelConfigService service;

    @InjectMocks private AdminLlmConfigController controller;

    @Test
    void list_returns_any_user_configs_masked() {
        LlmModelConfig e = new LlmModelConfig();
        e.setId(1L);
        e.setUserId(99L);
        when(service.selectAll(99L, LlmCapability.CHAT)).thenReturn(List.of(e));

        controller.list(99L, "CHAT");

        verify(service).selectAll(99L, LlmCapability.CHAT);
        verify(service).maskKey(e); // admin 看到的也是脱敏（无明文 key）
    }

    @Test
    void list_capability_default_chat() {
        when(service.selectAll(7L, LlmCapability.CHAT)).thenReturn(List.of());

        controller.list(7L, "CHAT");

        verify(service).selectAll(7L, LlmCapability.CHAT);
    }
}
