package com.smart.rag.modelconfig.event;

import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.modelconfig.mapper.LlmModelConfigMapper;
import com.smart.rag.user.event.UserDeletedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmUserLifecycleListener 单元测试（design §14.1 R2）— 用户删除触发缓存失效 + 配置逻辑删除。
 */
@ExtendWith(MockitoExtension.class)
class LlmUserLifecycleListenerTest {

    @Mock private LlmClientRegistry registry;
    @Mock private LlmModelConfigMapper mapper;

    @InjectMocks private LlmUserLifecycleListener listener;

    @Test
    void onUserDeleted_invalidates_cache_and_soft_deletes_config() {
        when(mapper.markDeletedByUser(7L)).thenReturn(3);

        listener.onUserDeleted(new UserDeletedEvent(7L));

        verify(registry).invalidateUser(7L);
        verify(mapper).markDeletedByUser(7L);
    }
}
