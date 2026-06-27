package com.smart.rag.modelconfig.controller;

import com.smart.rag.chat.service.UserContextProvider;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.modelconfig.dto.UpsertLlmConfigRequest;
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
 * UserLlmConfigController 单元测试 — owner-only：userId 从 UserContextProvider 取（禁 query param 越权）、
 * 脱敏 VO 回显、upsert/delete 透传当前用户。
 */
@ExtendWith(MockitoExtension.class)
class UserLlmConfigControllerTest {

    @Mock private LlmModelConfigService service;
    @Mock private UserContextProvider userContextProvider;

    @InjectMocks private UserLlmConfigController controller;

    private LlmModelConfig entityWithId(long id) {
        LlmModelConfig e = new LlmModelConfig();
        e.setId(id);
        e.setUserId(7L);
        return e;
    }

    @Test
    void list_uses_current_user_id_and_masks_keys() {
        when(userContextProvider.getCurrentUserId()).thenReturn(7L);
        LlmModelConfig e = entityWithId(1L);
        when(service.selectAll(7L, LlmCapability.CHAT)).thenReturn(List.of(e));

        controller.list("CHAT");

        // userId 来自 SecurityContext（UserContextProvider），不是 query param
        verify(service).selectAll(7L, LlmCapability.CHAT);
        verify(service).maskKey(e); // 回显前脱敏
    }

    @Test
    void list_accepts_capability_param_case_insensitive() {
        when(userContextProvider.getCurrentUserId()).thenReturn(7L);
        when(service.selectAll(7L, LlmCapability.EMBEDDING)).thenReturn(List.of());

        controller.list("embedding");

        verify(service).selectAll(7L, LlmCapability.EMBEDDING);
    }

    @Test
    void upsert_passes_current_user_id_to_service() {
        when(userContextProvider.getCurrentUserId()).thenReturn(7L);
        UpsertLlmConfigRequest req = new UpsertLlmConfigRequest();
        LlmModelConfig saved = entityWithId(1L);
        when(service.upsert(7L, req)).thenReturn(saved);

        controller.upsert(req);

        verify(service).upsert(7L, req); // owner userId 透传
        verify(service).maskKey(saved);  // 返回前脱敏
    }

    @Test
    void delete_passes_current_user_id_and_path_id() {
        when(userContextProvider.getCurrentUserId()).thenReturn(7L);

        controller.delete(10L);

        verify(service).delete(7L, 10L);
    }

    @Test
    void get_returns_owned_single_config_masked() {
        when(userContextProvider.getCurrentUserId()).thenReturn(7L);
        LlmModelConfig e = entityWithId(10L);
        when(service.getOwned(7L, 10L)).thenReturn(e);

        controller.get(10L);

        verify(service).getOwned(7L, 10L);
        verify(service).maskKey(e);
    }
}
